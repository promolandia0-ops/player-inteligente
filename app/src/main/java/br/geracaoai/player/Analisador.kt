package br.geracaoai.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.*

class AnaliseWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val NOME = "analise"
        const val CANAL = "analise"
        fun iniciar(ctx: Context, uri: Uri) {
            val req = OneTimeWorkRequestBuilder<AnaliseWorker>()
                .setInputData(workDataOf("uri" to uri.toString()))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOME, ExistingWorkPolicy.REPLACE, req)
        }
        fun cancelar(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NOME)
    }

    private fun notif(p: Int, texto: String) = NotificationCompat.Builder(applicationContext, CANAL)
        .setSmallIcon(R.drawable.ic_notif).setContentTitle("Buscando cenas…").setContentText(texto)
        .setProgress(100, p, false).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .addAction(0, "Cancelar", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()

    private fun fg(p: Int, t: String) = ForegroundInfo(42, notif(p, t), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun getForegroundInfo() = fg(0, "Preparando")

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Análise de vídeo", NotificationManager.IMPORTANCE_LOW))
        runCatching { setForeground(fg(0, "Preparando")) }
        val uri = Uri.parse(inputData.getString("uri") ?: return Result.failure())
        val prefs = Prefs(ctx)
        val intervalo = 1000L / prefs.fps.coerceIn(1, 4)
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return Result.failure()
            val n = (dur / intervalo).toInt() + 1
            val tempos = LongArray(n) { it * intervalo }
            val sc = Categoria.entries.associateWith { FloatArray(n) }
            Detector(ctx, prefs.acelerador).use { det ->
                val ini = System.currentTimeMillis()
                for (i in 0 until n) {
                    if (isStopped) { Cache.salvar(ctx, Cache.chave(ctx, uri), Analise(dur, tempos.copyOf(i), sc.mapValues { it.value.copyOf(i) }, parcial = true), intervalo); return Result.failure() }
                    val bmp = mmr.getScaledFrameAtTime(tempos[i] * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320)
                    if (bmp != null) { det.pontuar(bmp).forEach { (c, v) -> sc[c]!![i] = v }; bmp.recycle() }
                    if (i % 4 == 0 || i == n - 1) {
                        val p = (i + 1) * 100 / n
                        val el = (System.currentTimeMillis() - ini) / 1000
                        val rest = if (i > 0) el * (n - i - 1) / (i + 1) else 0
                        val txt = "$p% · ${det.backend} · faltam ~${rest / 60}m${rest % 60}s"
                        setProgress(workDataOf("p" to (i + 1).toFloat() / n, "uri" to uri.toString(), "txt" to txt))
                        nm.notify(42, notif(p, txt))
                        if (i % 20 == 0 && i > 0) Cache.salvar(ctx, Cache.chave(ctx, uri),
                            Analise(dur, tempos.copyOf(i + 1), sc.mapValues { it.value.copyOf(i + 1) }, parcial = true), intervalo)
                    }
                }
            }
            Cache.salvar(ctx, Cache.chave(ctx, uri), Analise(dur, tempos, sc), intervalo)
            Result.success(workDataOf("uri" to uri.toString()))
        } catch (e: Exception) {
            Result.failure(workDataOf("erro" to (e.message ?: e.toString())))
        } finally { runCatching { mmr.release() } }
    }
}
