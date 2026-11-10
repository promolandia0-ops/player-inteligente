package br.geracaoai.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Progresso do job Pessoas (lido pela UI a cada ~1 s). */
class PessoasProg(ctx: Context) {
    private val p = ctx.getSharedPreferences("pessoas_prog", Context.MODE_PRIVATE)
    var ativo: Boolean get() = p.getBoolean("ativo", false); set(v) = p.edit().putBoolean("ativo", v).apply()
    var feitos: Int get() = p.getInt("feitos", 0); set(v) = p.edit().putInt("feitos", v).apply()
    var total: Int get() = p.getInt("total", 0); set(v) = p.edit().putInt("total", v).apply()
    var txt: String get() = p.getString("txt", "") ?: ""; set(v) = p.edit().putString("txt", v).apply()
}

/**
 * Job em segundo plano do módulo Pessoas: baixa prioridade, cancelável, retomável (só lê vídeos ainda não feitos),
 * cede ao player aberto e à Análise de vídeo. Não mexe na galeria/DB da galeria.
 */
class PessoasWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val NOME = "pessoas"
        private const val CANAL = "pessoas"
        private const val TAG = "Pessoas"
        const val QUADROS_POR_VIDEO = 8
        /** Vídeos lidos ao mesmo tempo (2 threads de background). */
        const val PARALELO = 2

        fun iniciar(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<PessoasWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOME, ExistingWorkPolicy.KEEP, req)
            PessoasProg(ctx).apply { ativo = true; txt = "Preparando…" }
        }
        fun cancelar(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NOME)
            PessoasProg(ctx).ativo = false
        }
    }

    private fun notif(p: Int, texto: String) = NotificationCompat.Builder(applicationContext, CANAL)
        .setSmallIcon(R.drawable.ic_notif).setContentTitle("Agrupando pessoas").setContentText(texto)
        .setProgress(100, p, false).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .addAction(0, "Parar", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()

    private fun fg(p: Int, t: String) = ForegroundInfo(43, notif(p, t), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    override suspend fun getForegroundInfo() = fg(0, "Preparando")

    private class Lido(val v: Video, val obs: List<Agrupamento.Obs>, val miniaturas: List<Bitmap?>)

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val ctx = applicationContext
        val prog = PessoasProg(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Agrupar pessoas", NotificationManager.IMPORTANCE_LOW))
        runCatching { setForeground(fg(0, "Preparando")) }
        prog.ativo = true
        var motor: RostoMotor? = null
        val db = PessoasDb(ctx)
        // 2 threads de leitura/inferência em PRIORIDADE DE BACKGROUND (a UI e o player não sentem).
        val executor = Executors.newFixedThreadPool(PARALELO) { r -> Thread { runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }; r.run() } }
        try {
            val (videos, feitos) = withContext(Dispatchers.IO) { VideosMedia.listar(ctx) to db.videosFeitos() }
            val pend = videos.filter { VideosMedia.chaveEstavel(it.uri.toString()) !in feitos }.sortedByDescending { it.dataMod }
            val total = pend.size + feitos.size
            prog.total = total; prog.feitos = feitos.size
            if (pend.isEmpty()) { prog.txt = "Tudo analisado"; return@withContext Result.success() }

            runCatching { System.loadLibrary("onnxruntime4j_jni") }   // mesmo cuidado do Detector (JNI Microsoft, não o do sherpa)
            val m = withContext(Dispatchers.IO) {
                RostoMotor(ctx.assets.open("face_det_yunet.onnx").use { it.readBytes() }, ctx.assets.open("face_rec_sface_int8.onnx").use { it.readBytes() })
            }
            motor = m
            val somas = withContext(Dispatchers.IO) { db.somasPorPessoa() }
            var feitosN = feitos.size
            val ini = System.currentTimeMillis(); val inicial = feitosN
            val proximo = AtomicInteger(0)
            val canal = Channel<Lido>(PARALELO)

            coroutineScope {
                // Leitores: cada um pega o próximo vídeo pendente (retomável: só os que faltam), lê rostos e manda p/ o consumidor.
                val leitores = List(PARALELO) {
                    launch(executor.asCoroutineDispatcher()) {
                        while (!isStopped) {
                            val idx = proximo.getAndIncrement(); if (idx >= pend.size) break
                            // Cede ao player aberto / Análise: espera (sem gastar CPU) em vez de competir.
                            while (!isStopped && (Exclusao.playerEstaAberto(ctx) || AnaliseWorker.rodando(ctx))) { prog.txt = "Aguardando player/análise…"; delay(3000) }
                            if (isStopped) break
                            val v = pend[idx]
                            val obs = ArrayList<Agrupamento.Obs>(); val mini = ArrayList<Bitmap?>()
                            try { lerRostos(ctx, v, m, obs, mini) } catch (e: Throwable) { Log.w(TAG, "vídeo ${v.nome}", e) }
                            canal.send(Lido(v, obs, mini))
                        }
                    }
                }
                launch { leitores.forEach { it.join() }; canal.close() }

                // Consumidor único: atribui a pessoas e grava (DB/somas nunca em paralelo).
                for (lido in canal) {
                    if (isStopped) { lido.miniaturas.forEach { it?.recycle() }; continue }
                    val v = lido.v
                    val chave = VideosMedia.chaveEstavel(v.uri.toString())
                    try {
                        val trilhas = Agrupamento.tracar(lido.obs)
                        val gravar = withContext(Dispatchers.IO) {
                            trilhas.map { t ->
                                var p = Agrupamento.melhorPessoa(somas, t.emb)
                                if (p == null) p = db.novaPessoa()
                                val s = somas.getOrPut(p) { FloatArray(t.emb.size) }; for (k in t.emb.indices) s[k] += t.emb[k]
                                val arq = "f_${System.nanoTime()}.jpg"
                                val ok = lido.miniaturas.getOrNull(t.melhor.ref)?.let { salvarJpeg(it, File(db.pasta, arq)) } == true
                                Triple(t, p, if (ok) arq else null)
                            }
                        }
                        withContext(Dispatchers.IO) { db.salvarVideo(chave, v.uri.toString(), trilhas.size, gravar) }
                    } finally { lido.miniaturas.forEach { if (it != null && !it.isRecycled) it.recycle() } }
                    feitosN++
                    prog.feitos = feitosN
                    val el = (System.currentTimeMillis() - ini) / 1000
                    val rest = if (feitosN > inicial) el * (total - feitosN) / (feitosN - inicial) else 0L
                    val txt = "$feitosN de $total vídeos · faltam ~${rest / 60}m"
                    prog.txt = txt
                    if (feitosN % 3 == 0) {
                        setProgress(workDataOf("p" to feitosN.toFloat() / total))
                        nm.notify(43, notif(feitosN * 100 / total, txt))
                    }
                }
            }
            if (isStopped) return@withContext Result.failure()
            prog.txt = "Concluído"
            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "falhou", e); App.gravarErro(ctx, "pessoas", e)
            prog.txt = "Erro: ${e.javaClass.simpleName}"
            Result.failure()
        } finally {
            runCatching { executor.shutdownNow() }
            runCatching { motor?.close() }; runCatching { db.close() }
            prog.ativo = false
            runCatching { nm.cancel(43) }
        }
    }

    /**
     * Quadros espaçados (keyframes: rápido), 640 px; ADAPTATIVO: para assim que já há rosto suficiente
     * ([Agrupamento.podeParar]) em vez de sempre ler [QUADROS_POR_VIDEO]. Guarda rostos bons + miniatura de cada um.
     */
    private fun lerRostos(ctx: Context, v: Video, motor: RostoMotor, obs: MutableList<Agrupamento.Obs>, miniaturas: MutableList<Bitmap?>) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, v.uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: v.duracao
            if (dur <= 0L) return
            var lidos = 0
            for (i in Agrupamento.ORDEM_QUADROS) {
                if (isStopped) return
                val t = dur * (2 * i + 1) / (2 * QUADROS_POR_VIDEO)
                lidos++
                val bmp = mmr.getScaledFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                if (bmp != null) {
                    try {
                        val w = bmp.width; val h = bmp.height
                        val pix = IntArray(w * h); bmp.getPixels(pix, 0, w, 0, 0, w, h)
                        for (r in motor.analisar(pix, w, h)) {
                            obs += Agrupamento.Obs(r.emb, r.rosto.score, r.nitidez, miniaturas.size)
                            miniaturas += recorteRosto(bmp, r.rosto)
                        }
                    } finally { if (!bmp.isRecycled) bmp.recycle() }
                }
                if (Agrupamento.podeParar(obs, lidos)) return
            }
        } finally { runCatching { mmr.release() } }
    }

    /** Recorte quadrado em volta do rosto (×1,7), 112 px. */
    private fun recorteRosto(bmp: Bitmap, r: RostoMat.Rosto): Bitmap? = runCatching {
        val cx = (r.x0 + r.x1) / 2f; val cy = (r.y0 + r.y1) / 2f
        val lado = (maxOf(r.x1 - r.x0, r.y1 - r.y0) * 1.7f).coerceAtMost(minOf(bmp.width, bmp.height).toFloat()).coerceAtLeast(8f)
        val x = (cx - lado / 2).coerceIn(0f, bmp.width - lado).toInt(); val y = (cy - lado / 2).coerceIn(0f, bmp.height - lado).toInt()
        val rec = Bitmap.createBitmap(bmp, x, y, lado.toInt().coerceAtMost(bmp.width - x), lado.toInt().coerceAtMost(bmp.height - y))
        Bitmap.createScaledBitmap(rec, 112, 112, true).also { if (rec !== bmp && it !== rec) rec.recycle() }
    }.getOrNull()

    private fun salvarJpeg(b: Bitmap, f: File): Boolean = runCatching { FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }.getOrDefault(false)
}
