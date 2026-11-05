package br.geracaoai.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.IBinder
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Varredura pesada em processo `:scan`. Um OOM/crash aqui não mata a UI.
 * Progresso e resultados vão para GaleriaDb + OrgPrefs; o Worker na UI só observa.
 */
class OrganizarScanService : Service() {
    companion object {
        const val CANAL = "organizar"
        const val NOTIF_ID = 43
        const val ACTION_START = "br.geracaoai.player.ORG_START"
        const val ACTION_PAUSE = "br.geracaoai.player.ORG_PAUSE"
        const val ACTION_STOP = "br.geracaoai.player.ORG_STOP"
        const val ACTION_YIELD = "br.geracaoai.player.ORG_YIELD"
        private const val TAG = "OrgScan"
        private const val FRAMES = 8
        private const val FRAME_MAX = 320
        private const val TIMEOUT_VIDEO_MS = 10_000L
        private const val LIMIAR = OrganizarWorker.LIMIAR

        fun start(ctx: Context, bucketId: String? = null) {
            val i = Intent(ctx, OrganizarScanService::class.java).setAction(ACTION_START)
            if (bucketId != null) i.putExtra("bucketId", bucketId)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun pause(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, OrganizarScanService::class.java).setAction(ACTION_PAUSE),
            )
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, OrganizarScanService::class.java).setAction(ACTION_STOP))
        }
        /** Cede à análise por vídeo sem marcar pausa do usuário. */
        fun yieldToAnalise(ctx: Context) {
            // Só acorda o :scan se a varredura estiver marcada ativa ou o heartbeat vivo.
            if (!OrgPrefs(ctx).ativo && !Exclusao.scanRodando(ctx)) return
            runCatching {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, OrganizarScanService::class.java).setAction(ACTION_YIELD),
                )
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "org-scan").also { it.priority = Thread.NORM_PRIORITY - 1 }
    }
    private val videoExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "org-video").also { it.priority = Thread.NORM_PRIORITY - 1 }
    }
    private var job: Future<*>? = null
    private val pauseFlag = AtomicBoolean(false)
    private val stopFlag = AtomicBoolean(false)
    private val travado = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate pid=${Process.myPid()}")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Organizar galeria", NotificationManager.IMPORTANCE_LOW))
        val n = notif(0, "Preparando")
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            startForeground(
                NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                pauseFlag.set(true)
                OrganizarWorker.setPausado(this, true)
                OrgPrefs(this).pausado = true
                Log.i(TAG, "pausado")
            }
            ACTION_STOP -> {
                stopFlag.set(true)
                pauseFlag.set(false)
                OrganizarWorker.setPausado(this, false)
                OrgPrefs(this).apply { ativo = false; pausado = false }
                job?.cancel(true)
                stopSelf()
            }
            ACTION_YIELD -> {
                // Interrompe o loop atual; ativo permanece true → Worker/retomada sobe de novo depois.
                stopFlag.set(true)
                Log.i(TAG, "yield à análise por vídeo")
                job?.cancel(true)
                stopSelf()
            }
            else -> {
                val bucket = intent?.getStringExtra("bucketId")
                pauseFlag.set(false)
                stopFlag.set(false)
                OrganizarWorker.setPausado(this, false)
                OrgPrefs(this).apply {
                    ativo = true; pausado = false
                    if (bucket != null) bucketId = bucket
                }
                if (job == null || job!!.isDone) {
                    job = executor.submit { rodar(bucket ?: OrgPrefs(this).bucketId) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopFlag.set(true)
        job?.cancel(true)
        executor.shutdownNow()
        videoExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun rodar(bucketId: String?) {
        val ctx = applicationContext
        val nm = getSystemService(NotificationManager::class.java)
        val db = GaleriaDb(ctx)
        val prefs = OrgPrefs(ctx)
        try {
            // Cede se análise por vídeo estiver rodando.
            if (analiseRodando(ctx) || Exclusao.playerEstaAberto(ctx)) {
                Log.i(TAG, "cedendo à análise por vídeo / player aberto")
                prefs.ativo = true
                atualizar(nm, prefs.feitos, prefs.total.coerceAtLeast(1), "Aguardando análise do vídeo…")
                // Reagenda via WorkManager (KEEP) para retomar depois.
                OrganizarWorker.reagendar(ctx)
                stopSelf()
                return
            }

            val videos = listarVideos(ctx, bucketId)
            if (videos.isEmpty()) {
                prefs.ativo = false
                stopSelf()
                return
            }
            // v1.1.2: regras de Putaria mais rígidas → reclassifica antigos marcados como Putaria (uma vez).
            val sp = ctx.getSharedPreferences("org_versao", MODE_PRIVATE)
            if (sp.getInt("classif_v", 1) < 2) { runCatching { db.limparNude() }; sp.edit().putInt("classif_v", 2).commit() }
            val feitosMap = HashMap<String, Long>()
            db.todos().forEach { (u, r) ->
                if (r.classif == ClassifNudez.NUDE || r.classif == ClassifNudez.LIMPO || r.classif == ClassifNudez.ERRO)
                    feitosMap[u] = r.mtime
            }
            val total = videos.size
            var feitos = videos.count { v ->
                val m = feitosMap[v.uri.toString()]; m != null && m == v.dataMod
            }
            prefs.total = total
            prefs.feitos = feitos
            atualizar(nm, feitos, total, "Retomando · $feitos de $total")

            Exclusao.scanBatida(ctx)
            Detector(ctx, Prefs(ctx).acelerador).use { det ->
                for (v in videos) {
                    if (stopFlag.get()) break
                    if (pauseFlag.get() || OrganizarWorker.estaPausado(ctx)) {
                        prefs.pausado = true
                        prefs.ativo = true
                        atualizar(nm, feitos, total, "Pausado · $feitos de $total")
                        break
                    }
                    Exclusao.scanBatida(ctx)
                    if (analiseRodando(ctx) || Exclusao.playerEstaAberto(ctx)) {
                        Log.i(TAG, "análise por vídeo — pausando galeria")
                        prefs.ativo = true
                        OrganizarWorker.reagendar(ctx)
                        break
                    }
                    val key = v.uri.toString()
                    val ant = feitosMap[key]
                    if (ant != null && ant == v.dataMod) continue

                    val (classif, cats) = classificarComTimeout(ctx, v, det)
                    try {
                        db.salvar(key, classif, cats, v.dataMod)
                    } catch (oom: OutOfMemoryError) {
                        Log.e(TAG, "OOM salvar", oom)
                        runCatching { db.salvar(key, ClassifNudez.ERRO, emptySet(), v.dataMod) }
                        System.gc()
                    }
                    feitosMap[key] = v.dataMod
                    feitos++
                    prefs.feitos = feitos
                    val txt = "$feitos de $total · ${v.nome.take(24)}"
                    prefs.txt = txt
                    atualizar(nm, feitos, total, txt)
                    if (feitos % 25 == 0) System.gc()
                    if (travado.get()) {
                        // Decoder preso numa thread: encerra o processo :scan limpo; retoma depois pulando este vídeo.
                        prefs.ativo = true
                        OrganizarWorker.reagendar(ctx)
                        stopSelf()
                        Process.killProcess(Process.myPid())
                    }
                }
            }
            if (!pauseFlag.get() && !stopFlag.get() && prefs.feitos >= total) {
                prefs.ativo = false
                prefs.pausado = false
                prefs.txt = "Concluído · $total de $total"
                atualizar(nm, total, total, prefs.txt)
            }
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM fatal", oom)
            prefs.ativo = true
            System.gc()
            OrganizarWorker.reagendar(ctx)
        } catch (e: Throwable) {
            Log.e(TAG, "falhou", e)
            runCatching { App.gravarErro(ctx, "scan", e) }
            prefs.ativo = true
            OrganizarWorker.reagendar(ctx)
        } finally {
            Exclusao.scanFim(ctx)
            stopSelf()
        }
    }

    private fun atualizar(nm: NotificationManager, feitos: Int, total: Int, txt: String) {
        val p = if (total <= 0) 0 else ((feitos * 100) / total).coerceIn(0, 100)
        nm.notify(NOTIF_ID, notif(p, txt))
        OrgPrefs(this).progresso(feitos, total, txt)
    }

    private fun notif(p: Int, texto: String) = NotificationCompat.Builder(this, CANAL)
        .setSmallIcon(R.drawable.ic_notif).setContentTitle("Organizando galeria…").setContentText(texto)
        .setProgress(100, p, false).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .build()

    private fun analiseRodando(ctx: Context): Boolean = try { AnaliseWorker.rodando(ctx) } catch (_: Throwable) { false }

    private fun listarVideos(ctx: Context, bucketId: String?): List<Video> {
        val out = ArrayList<Video>()
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
        val sel = if (bucketId != null) "${MediaStore.Video.Media.BUCKET_ID}=?" else null
        val args = if (bucketId != null) arrayOf(bucketId) else null
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, sel, args,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Video(
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                        c.getString(1) ?: "?", c.getLong(2), c.getLong(3),
                        runCatching { c.getLong(4).toString() }.getOrElse { c.getString(4) ?: "0" },
                        c.getString(5)?.takeIf { it.isNotBlank() } ?: "Outros",
                        c.getLong(6),
                    ),
                )
            }
        }
        return out
    }

    private fun classificarComTimeout(
        ctx: Context, v: Video, det: Detector,
    ): Pair<ClassifNudez, Set<Categoria>> {
        val cancel = AtomicBoolean(false)
        val fut = videoExecutor.submit<Pair<ClassifNudez, Set<Categoria>>> {
            classificar(ctx, v, det, cancel)
        }
        return try {
            fut.get(TIMEOUT_VIDEO_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            cancel.set(true)
            Log.w(TAG, "timeout/erro ${v.uri}: ${e.message}")
            // Estritamente sequencial: espera o vídeo travado liberar retriever/bitmaps antes do próximo
            // (nunca 2 decoders nem Detector usado após close()).
            var liberou = false
            for (i in 0 until 10) {
                Exclusao.scanBatida(ctx)
                liberou = runCatching { fut.get(3, TimeUnit.SECONDS); true }.getOrElse { it !is java.util.concurrent.TimeoutException }
                if (liberou) break
            }
            if (!liberou) { Log.e(TAG, "vídeo travado no decoder: ${v.uri}"); travado.set(true) }
            ClassifNudez.ERRO to emptySet()
        }
    }

    private fun classificar(
        ctx: Context, v: Video, det: Detector, cancel: AtomicBoolean,
    ): Pair<ClassifNudez, Set<Categoria>> {
        var mmr: MediaMetadataRetriever? = null
        return try {
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(ctx, v.uri)
            if (cancel.get()) return ClassifNudez.ERRO to emptySet()
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: v.duracao
            if (dur <= 0L) return ClassifNudez.LIMPO to emptySet()
            val hits = linkedSetOf<Categoria>()
            var confirmados = 0
            // Só classes *_EXPOSED contam (nunca COVERED/BELLY/FEET/ARMPITS/FACE); ≥2 quadros confirmam.
            fun testar(t: Long): Boolean? {
                var bmp: Bitmap? = null
                try {
                    bmp = mmr.getScaledFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_MAX, FRAME_MAX) ?: return null
                    if (bmp.width > FRAME_MAX || bmp.height > FRAME_MAX) {
                        val esc = FRAME_MAX.toFloat() / maxOf(bmp.width, bmp.height)
                        val s = Bitmap.createScaledBitmap(bmp, (bmp.width * esc).toInt().coerceAtLeast(1), (bmp.height * esc).toInt().coerceAtLeast(1), true)
                        if (s !== bmp) bmp.recycle()
                        bmp = s
                    }
                    val (uiCats, putaria) = det.pontuarExposto(bmp!!, LIMIAR)
                    if (putaria) hits.addAll(uiCats)
                    return putaria
                } finally { bmp?.let { if (!it.isRecycled) it.recycle() } }
            }
            val vistos = HashSet<Long>()
            for (i in 0 until FRAMES) {
                if (cancel.get() || stopFlag.get() || pauseFlag.get()) break
                val t = (dur * (2 * i + 1)) / (2 * FRAMES) // evita 1º/último quadro (capa/créditos)
                vistos.add(t)
                if (testar(t) != true) continue
                confirmados++
                if (confirmados >= OrganizarWorker.CONFIRMACOES) break
                // hit isolado: confere vizinhos (±1,5 s e ±3 s) antes de aceitar
                for (d in longArrayOf(1500, -1500, 3000, -3000)) {
                    if (cancel.get() || stopFlag.get()) break
                    val tv = (t + d).coerceIn(0L, dur)
                    if (!vistos.add(tv)) continue
                    if (testar(tv) == true) { confirmados++; break }
                }
                if (confirmados >= OrganizarWorker.CONFIRMACOES) break
            }
            if (confirmados < OrganizarWorker.CONFIRMACOES) hits.clear()
            if (hits.isEmpty()) ClassifNudez.LIMPO to emptySet() else ClassifNudez.NUDE to hits
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "OOM video", oom); System.gc(); ClassifNudez.ERRO to emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "skip ${v.uri}", e); ClassifNudez.ERRO to emptySet()
        } finally {
            runCatching { mmr?.release() }
        }
    }
}
