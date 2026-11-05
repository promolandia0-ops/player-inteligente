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
 * Mede área de peito/bunda/buceta SÓ nos vídeos já em Putaria.
 * Sequencial, resumível, cede ao player. Não rescaneia a galeria inteira.
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
            val i = Intent(ctx, TamanhoScanService::class.java).setAction(ACTION_START)
                .putExtra("alvo", alvoId)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun parar(ctx: Context) {
            ctx.startService(Intent(ctx, TamanhoScanService::class.java).setAction(ACTION_STOP))
        }
    }

    private val stop = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "tam-scan").also { t -> t.priority = Thread.NORM_PRIORITY - 1 } }
    private var job: Future<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stop.set(true); job?.cancel(true); stopSelf() }
            ACTION_START -> {
                stop.set(false)
                subir(0, "Medindo tamanhos…")
                if (job == null || job!!.isDone) job = exec.submit { rodar() }
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

    private fun rodar() {
        val ctx = applicationContext
        val db = GaleriaDb(ctx)
        try {
            // Só Putaria já classificada, sem área ainda (ou todas para refinar)
            val nude = db.todos().filter { it.value.classif == ClassifNudez.NUDE }
            val pend = nude.filter { (_, r) ->
                r.areaPeito <= 0f && r.areaBunda <= 0f && r.areaBuceta <= 0f
            }.ifEmpty { nude } // se já tem áreas, não refaz tudo — só pendentes
            val lista = pend.entries.toList()
            if (lista.isEmpty()) { stopSelf(); return }
            Detector(ctx, false).use { det ->
                for (i in lista.indices) {
                    val (uriStr, r) = lista[i]
                    if (stop.get() || Exclusao.playerEstaAberto(ctx) || AnaliseWorker.rodando(ctx)) {
                        Log.i(TAG, "cedendo"); break
                    }
                    val p = ((i + 1) * 100) / lista.size
                    subir(p, "${i + 1}/${lista.size}")
                    val (ap, ab, abu) = medir(ctx, Uri.parse(uriStr), det)
                    // UPDATE áreas sem apagar emb: update parcial
                    db.salvarAreas(uriStr, ap, ab, abu)
                }
            }
            subir(100, "Pronto")
        } catch (e: Throwable) {
            Log.e(TAG, "falhou", e)
            App.gravarErro(ctx, "tamanho", e)
        } finally {
            getSystemService(NotificationManager::class.java).cancel(NOTIF)
            stopSelf()
        }
    }

    private fun medir(ctx: Context, uri: Uri, det: Detector): Triple<Float, Float, Float> {
        val mmr = MediaMetadataRetriever()
        var maxP = 0f; var maxB = 0f; var maxU = 0f
        return try {
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return Triple(0f, 0f, 0f)
            if (dur <= 0) return Triple(0f, 0f, 0f)
            for (i in 0 until FRAMES) {
                if (stop.get()) break
                val t = (dur * (2 * i + 1)) / (2 * FRAMES)
                var bmp: Bitmap? = null
                try {
                    bmp = mmr.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_MAX, FRAME_MAX) ?: continue
                    val (_, _, areas) = det.pontuarExposto(bmp, OrganizarWorker.LIMIAR)
                    maxP = maxOf(maxP, areas[Categoria.PEITO] ?: 0f)
                    maxB = maxOf(maxB, areas[Categoria.BUNDA] ?: 0f)
                    maxU = maxOf(maxU, areas[Categoria.BUCETA] ?: 0f)
                } finally { bmp?.let { if (!it.isRecycled) it.recycle() } }
            }
            Triple(maxP, maxB, maxU)
        } catch (e: Throwable) {
            Log.w(TAG, "skip $uri", e); Triple(0f, 0f, 0f)
        } finally { runCatching { mmr.release() } }
    }

    override fun onDestroy() { stop.set(true); exec.shutdownNow(); super.onDestroy() }
}
