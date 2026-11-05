package br.geracaoai.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mede área peito/bunda/buceta SÓ em Putaria, por alvo (Peituda/…).
 * Sequencial, só mulheres, cede a player/análise por vídeo; pausa a galeria.
 */
class TamanhoScanService : Service() {
    companion object {
        const val CANAL = "tamanho"
        const val NOTIF = 56
        const val ACTION_START = "br.geracaoai.player.TAMANHO_START"
        const val ACTION_STOP = "br.geracaoai.player.TAMANHO_STOP"
        private const val TAG = "TamanhoScan"
        private const val FRAME_MAX = 320
        private const val FRAMES = 5

        fun iniciar(ctx: Context, alvoId: String = "") {
            if (AnaliseWorker.rodando(ctx)) {
                android.widget.Toast.makeText(ctx, "Espere a análise do vídeo terminar", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val alvo = TamanhoLogica.Alvo.deId(alvoId)
            val nude = runCatching {
                GaleriaDb(ctx).todos().values.filter { it.classif == ClassifNudez.NUDE }
            }.getOrDefault(emptyList())
            val (total, pend) = TamanhoProgresso.candidatos(nude, alvo)
            if (total == 0) {
                val msg = "Nenhum vídeo em Putaria ainda: toque em Organizar primeiro"
                if (alvoId.isNotEmpty()) TamanhoProgresso.set(ctx, alvoId, 1f, msg)
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                return
            }
            if (pend == 0) {
                val msg = "Nada novo para medir nesta pasta"
                if (alvoId.isNotEmpty()) TamanhoProgresso.set(ctx, alvoId, 1f, msg)
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            // Feedback imediato no tile (antes do FGS subir).
            if (alvoId.isNotEmpty()) {
                TamanhoProgresso.set(ctx, alvoId, 0.02f, "Analisando 0 de $pend")
            }
            runCatching { OrganizarScanService.yieldToAnalise(ctx) }
            val i = Intent(ctx, TamanhoScanService::class.java).setAction(ACTION_START)
                .putExtra("alvo", alvoId)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Throwable) {
                App.gravarErro(ctx, "tamanho-iniciar", e)
                if (alvoId.isNotEmpty()) TamanhoProgresso.clear(ctx, alvoId)
                android.widget.Toast.makeText(
                    ctx, "Não iniciei a medição: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        fun parar(ctx: Context) {
            ctx.startService(Intent(ctx, TamanhoScanService::class.java).setAction(ACTION_STOP))
        }
        fun rodando(ctx: Context): Boolean {
            val p = TamanhoProgresso.todosAtivos(ctx)
            return p.values.any { it.frac in 0.001f..0.999f }
        }
    }

    private val stop = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "tam-scan").also { t -> t.priority = Thread.NORM_PRIORITY - 1 } }
    private var job: Future<*>? = null
    private var alvoAtual: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android 14+: startForeground antes do timeout (evita ForegroundServiceDidNotStartInTimeException).
        runCatching { subir(0, "Preparando…") }
            .onFailure { e ->
                App.gravarErro(this, "tamanho-onCreate", e)
                stopSelf()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stop.set(true); job?.cancel(true)
                if (alvoAtual.isNotEmpty()) TamanhoProgresso.clear(this, alvoAtual)
                stopSelf()
            }
            ACTION_START -> {
                stop.set(false)
                alvoAtual = intent.getStringExtra("alvo") ?: ""
                subir(2, "Medindo…")
                if (alvoAtual.isNotEmpty()) {
                    val snap = TamanhoProgresso.get(this, alvoAtual)
                    TamanhoProgresso.set(this, alvoAtual, 0.02f, snap?.txt?.ifBlank { "Analisando…" } ?: "Analisando…")
                }
                // Sempre (re)inicia para o alvo pedido — senão o 2º toque “não faz nada”.
                job?.cancel(true)
                job = exec.submit { rodar(alvoAtual) }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun subir(p: Int, txt: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Medir tamanho", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle("Medindo Putaria…")
            .setContentText(txt).setProgress(100, p, p <= 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 35)
                startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Throwable) { startForeground(NOTIF, n) }
        nm.notify(NOTIF, n)
    }

    private fun rodar(alvoId: String) {
        val ctx = applicationContext
        val db = GaleriaDb(ctx)
        val alvo = TamanhoLogica.Alvo.deId(alvoId)
        try {
            Exclusao.esperarScanParar(ctx, 3_000L)
            val nude = db.todos().filter { it.value.classif == ClassifNudez.NUDE }
            val pend = nude.filter { (_, r) ->
                when (alvo) {
                    TamanhoLogica.Alvo.PEITUDA, TamanhoLogica.Alvo.PEITINHO -> TamanhoLogica.areaPendente(r.areaPeito)
                    TamanhoLogica.Alvo.RABUDA, TamanhoLogica.Alvo.RABINHO -> TamanhoLogica.areaPendente(r.areaBunda)
                    TamanhoLogica.Alvo.BUCETUDA, TamanhoLogica.Alvo.BUCETINHA -> TamanhoLogica.areaPendente(r.areaBuceta)
                    null -> r.areaPeito == 0f && r.areaBunda == 0f && r.areaBuceta == 0f
                }
            }.ifEmpty {
                // Nada pendente para este alvo: não remede tudo
                emptyMap()
            }
            val lista = pend.entries.toList()
            if (lista.isEmpty()) {
                val msg = if (nude.isEmpty())
                    "Nenhum vídeo em Putaria ainda: toque em Organizar primeiro"
                else "Nada novo para medir nesta pasta"
                if (alvoId.isNotEmpty()) TamanhoProgresso.set(ctx, alvoId, 1f, msg)
                subir(100, msg)
                stopSelf(); return
            }
            if (alvoId.isNotEmpty()) {
                TamanhoProgresso.set(ctx, alvoId, 0.02f, "Analisando 0 de ${lista.size}")
            }
            Detector(ctx, false).use { det ->
                for (i in lista.indices) {
                    val (uriStr, r) = lista[i]
                    if (stop.get() || Exclusao.playerEstaAberto(ctx) || AnaliseWorker.rodando(ctx)) {
                        Log.i(TAG, "cedendo"); break
                    }
                    val frac = (i + 1).toFloat() / lista.size
                    val p = (frac * 100).toInt()
                    val txt = "Analisando ${i + 1} de ${lista.size}"
                    subir(p, txt)
                    if (alvoId.isNotEmpty()) TamanhoProgresso.set(ctx, alvoId, frac.coerceAtMost(0.99f), txt)
                    val ev = medir(ctx, Uri.parse(uriStr), det)
                    val chave = VideosMedia.chaveEstavel(uriStr)
                    fun merge(novo: Float, velho: Float) = when {
                        novo > 0f -> novo
                        velho != 0f -> velho
                        else -> 0f
                    }
                    if (alvo != null) {
                        if (TamanhoLogica.aceita(alvo, ev)) {
                            db.salvarAreas(
                                chave,
                                merge(ev.areaPeito, r.areaPeito),
                                merge(ev.areaBunda, r.areaBunda),
                                merge(ev.areaBuceta, r.areaBuceta),
                            )
                        } else {
                            val ap = merge(0f, r.areaPeito)
                            val ab = merge(0f, r.areaBunda)
                            val abu = merge(0f, r.areaBuceta)
                            when (alvo) {
                                TamanhoLogica.Alvo.PEITUDA, TamanhoLogica.Alvo.PEITINHO ->
                                    db.salvarAreas(chave, -1f, ab, abu)
                                TamanhoLogica.Alvo.RABUDA, TamanhoLogica.Alvo.RABINHO ->
                                    db.salvarAreas(chave, ap, -1f, abu)
                                TamanhoLogica.Alvo.BUCETUDA, TamanhoLogica.Alvo.BUCETINHA ->
                                    db.salvarAreas(chave, ap, ab, -1f)
                            }
                        }
                    } else {
                        if (TamanhoLogica.masculinoDominante(ev)) {
                            db.salvarAreas(chave, -1f, -1f, -1f)
                        } else {
                            db.salvarAreas(chave, ev.areaPeito, ev.areaBunda, ev.areaBuceta)
                        }
                    }
                }
            }
            subir(100, "Pronto")
            if (alvoId.isNotEmpty()) TamanhoProgresso.set(ctx, alvoId, 1f, "Pronto")
        } catch (e: Throwable) {
            Log.e(TAG, "falhou", e)
            App.gravarErro(ctx, "tamanho", e)
            if (alvoId.isNotEmpty()) {
                TamanhoProgresso.set(ctx, alvoId, 1f, "Erro: ${e.message ?: e.javaClass.simpleName}")
            }
        } finally {
            getSystemService(NotificationManager::class.java).cancel(NOTIF)
            stopSelf()
        }
    }

    private fun medir(ctx: Context, uri: Uri, det: Detector): TamanhoLogica.Evidencia {
        val mmr = MediaMetadataRetriever()
        var acc = TamanhoLogica.Evidencia()
        val peitos = ArrayList<Float>()
        val bundas = ArrayList<Float>()
        val bucetas = ArrayList<Float>()
        return try {
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return acc
            if (dur <= 0) return acc
            for (i in 0 until FRAMES) {
                if (stop.get()) break
                val t = (dur * (2 * i + 1)) / (2 * FRAMES)
                var bmp: Bitmap? = null
                try {
                    bmp = mmr.getScaledFrameAtTime(
                        t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_MAX, FRAME_MAX,
                    ) ?: continue
                    val ev = det.pontuarDetalhe(bmp, OrganizarWorker.LIMIAR)
                    acc = TamanhoLogica.acumular(acc, ev)
                    if (ev.areaPeito > 0f) peitos.add(ev.areaPeito)
                    if (ev.areaBunda > 0f) bundas.add(ev.areaBunda)
                    if (ev.areaBuceta > 0f) bucetas.add(ev.areaBuceta)
                } finally { bmp?.let { if (!it.isRecycled) it.recycle() } }
            }
            // Métrica bruta = mediana nos frames (estável); hits já somados no acumular.
            acc.copy(
                areaPeito = TamanhoLogica.medianaPositiva(peitos),
                areaBunda = TamanhoLogica.medianaPositiva(bundas),
                areaBuceta = TamanhoLogica.medianaPositiva(bucetas),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "skip $uri", e); acc
        } finally { runCatching { mmr.release() } }
    }

    override fun onDestroy() {
        stop.set(true); exec.shutdownNow()
        super.onDestroy()
    }
}
