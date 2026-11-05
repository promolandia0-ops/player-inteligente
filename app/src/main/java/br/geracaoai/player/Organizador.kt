package br.geracaoai.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*

/**
 * Varredura rápida sob demanda ("Organizar galeria"): ~8–12 keyframes/vídeo,
 * para no 1º hit EXPOSTO. Álbum virtual **Putaria** = nudez exposta de verdade;
 * resto → **Sem nudez**. Não move/copia arquivos.
 *
 * Classes que contam (NudeNet): FEMALE_BREAST_EXPOSED(3), BUTTOCKS_EXPOSED(2),
 * FEMALE_GENITALIA_EXPOSED(4), ANUS_EXPOSED(6), MALE_GENITALIA_EXPOSED(14).
 * *_COVERED nunca contam.
 */
class OrganizarWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val NOME = "organizar"
        const val CANAL = "organizar"
        const val LIMIAR = 0.40f
        const val FRAMES = 10
        /** Índices NudeNet 320n que qualificam Putaria (só EXPOSED). */
        val CLASSES_EXPOSTAS = intArrayOf(2, 3, 4, 6, 14)

        fun iniciar(ctx: Context, bucketId: String? = null) {
            val req = OneTimeWorkRequestBuilder<OrganizarWorker>()
                .setInputData(workDataOf("bucketId" to (bucketId ?: "")))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOME, ExistingWorkPolicy.REPLACE, req)
        }
        fun cancelar(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NOME)
    }

    private fun notif(p: Int, texto: String) = NotificationCompat.Builder(applicationContext, CANAL)
        .setSmallIcon(R.drawable.ic_notif).setContentTitle("Organizando galeria…").setContentText(texto)
        .setProgress(100, p, false).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .addAction(0, "Cancelar", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()

    private fun fg(p: Int, t: String) = ForegroundInfo(43, notif(p, t), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    override suspend fun getForegroundInfo() = fg(0, "Preparando")

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Organizar galeria", NotificationManager.IMPORTANCE_LOW))
        runCatching { setForeground(fg(0, "Preparando")) }
        val bucketFiltro = inputData.getString("bucketId")?.takeIf { it.isNotBlank() }
        val db = GaleriaDb(ctx)
        val prefs = Prefs(ctx)
        val videos = listarVideos(ctx, bucketFiltro)
        if (videos.isEmpty()) return Result.success()
        val existentes = db.todos()
        val pendentes = videos.filter { v ->
            val ant = existentes[v.uri.toString()]
            ant == null || ant.mtime != v.dataMod || ant.classif == ClassifNudez.PENDENTE
        }
        return try {
            Detector(ctx, prefs.acelerador).use { det ->
                val total = pendentes.size.coerceAtLeast(1)
                pendentes.forEachIndexed { i, v ->
                    if (isStopped) return Result.failure()
                    val (classif, cats) = classificar(ctx, v, det)
                    db.salvar(v.uri.toString(), classif, cats, v.dataMod)
                    val p = ((i + 1) * 100) / total
                    val txt = "${i + 1}/$total · ${v.nome.take(28)}"
                    setProgress(workDataOf("p" to (i + 1).toFloat() / total, "txt" to txt, "feitos" to (i + 1), "total" to total))
                    nm.notify(43, notif(p, txt))
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("Organizar", "falhou", e)
            Result.failure(workDataOf("erro" to (e.message ?: e.toString())))
        }
    }

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

    private fun classificar(ctx: Context, v: Video, det: Detector): Pair<ClassifNudez, Set<Categoria>> {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, v.uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: v.duracao
            if (dur <= 0L) return ClassifNudez.LIMPO to emptySet()
            val hits = linkedSetOf<Categoria>()
            for (i in 0 until FRAMES) {
                if (isStopped) break
                val t = (dur * i) / (FRAMES - 1).coerceAtLeast(1)
                val bmp: Bitmap = mmr.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320) ?: continue
                try {
                    val (uiCats, putaria) = det.pontuarExposto(bmp)
                    if (putaria) {
                        hits.addAll(uiCats)
                        break
                    }
                } finally { bmp.recycle() }
            }
            if (hits.isEmpty()) ClassifNudez.LIMPO to emptySet() else ClassifNudez.NUDE to hits
        } catch (e: Exception) {
            Log.w("Organizar", "skip ${v.uri}", e)
            ClassifNudez.PENDENTE to emptySet()
        } finally { runCatching { mmr.release() } }
    }
}
