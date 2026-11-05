package br.geracaoai.player

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import java.io.File

/**
 * Worker leve (processo UI): sobe [OrganizarScanService] no processo `:scan`,
 * observa OrgPrefs/DB e republica progresso no WorkManager.
 * KEEP + auto-retomada; Pausar/Continuar; cede prioridade à análise por vídeo.
 */
class OrganizarWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val NOME = "organizar"
        const val CANAL = "organizar"
        const val LIMIAR = 0.55f
        /** Quadros com parte EXPOSTA necessários para marcar Putaria. */
        const val CONFIRMACOES = 2
        const val FRAMES = 8
        val CLASSES_EXPOSTAS = intArrayOf(2, 3, 4, 6, 14)
        private const val TAG = "Organizar"
        private const val PAUSE_FILE = "org_paused.flag"

        fun iniciar(ctx: Context, bucketId: String? = null) {
            setPausado(ctx, false)
            OrgPrefs(ctx).apply {
                ativo = true; pausado = false; this.bucketId = bucketId
            }
            OrganizarScanService.start(ctx, bucketId)
            val req = OneTimeWorkRequestBuilder<OrganizarWorker>()
                .setInputData(workDataOf("bucketId" to (bucketId ?: "")))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOME, ExistingWorkPolicy.KEEP, req)
        }

        fun pausar(ctx: Context) {
            setPausado(ctx, true)
            OrgPrefs(ctx).pausado = true
            OrganizarScanService.pause(ctx)
            WorkManager.getInstance(ctx).cancelUniqueWork(NOME)
        }

        fun continuar(ctx: Context) {
            val prefs = OrgPrefs(ctx)
            setPausado(ctx, false)
            prefs.pausado = false
            prefs.ativo = true
            OrganizarScanService.start(ctx, prefs.bucketId)
            val req = OneTimeWorkRequestBuilder<OrganizarWorker>()
                .setInputData(workDataOf("bucketId" to (prefs.bucketId ?: "")))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOME, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelar(ctx: Context) {
            setPausado(ctx, false)
            OrgPrefs(ctx).apply { ativo = false; pausado = false }
            OrganizarScanService.stop(ctx)
            WorkManager.getInstance(ctx).cancelUniqueWork(NOME)
        }

        fun autoRetomarSePreciso(ctx: Context) {
            val prefs = OrgPrefs(ctx)
            if (prefs.ativo && !prefs.pausado && !estaPausado(ctx)) {
                Log.i(TAG, "auto-retomar varredura")
                iniciar(ctx, prefs.bucketId)
            }
        }

        fun reagendar(ctx: Context) {
            val prefs = OrgPrefs(ctx)
            if (!prefs.ativo || prefs.pausado) return
            val req = OneTimeWorkRequestBuilder<OrganizarWorker>()
                .setInputData(workDataOf("bucketId" to (prefs.bucketId ?: "")))
                .setInitialDelay(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOME, ExistingWorkPolicy.KEEP, req)
        }

        fun estaPausado(ctx: Context): Boolean =
            File(ctx.applicationContext.filesDir, PAUSE_FILE).exists() || OrgPrefs(ctx).pausado

        fun setPausado(ctx: Context, v: Boolean) {
            val f = File(ctx.applicationContext.filesDir, PAUSE_FILE)
            if (v) runCatching { f.writeText("1") } else runCatching { f.delete() }
        }
    }

    private fun notif(p: Int, texto: String) = NotificationCompat.Builder(applicationContext, CANAL)
        .setSmallIcon(R.drawable.ic_notif).setContentTitle("Organizando galeria…").setContentText(texto)
        .setProgress(100, p, false).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .build()

    private fun fg(p: Int, t: String) =
        ForegroundInfo(43, notif(p, t), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun getForegroundInfo() = fg(0, "Preparando")

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = OrgPrefs(ctx)
        runCatching { setForeground(fg(0, prefs.txt.ifBlank { "Preparando" })) }

        // Garante que o serviço :scan está de pé.
        if (prefs.ativo && !prefs.pausado) {
            OrganizarScanService.start(ctx, prefs.bucketId ?: inputData.getString("bucketId"))
        }

        // Observa até concluir / pausar / cancelar (máx ~ several hours).
        var idle = 0
        var lastFeitos = -1
        while (!isStopped) {
            if (estaPausado(ctx) || prefs.pausado) {
                return Result.success()
            }
            if (!prefs.ativo) {
                val feitos = prefs.feitos; val total = prefs.total.coerceAtLeast(1)
                val frac = feitos.toFloat() / total
                setProgress(workDataOf("p" to frac, "txt" to prefs.txt, "feitos" to feitos, "total" to total))
                return Result.success()
            }
            val feitos = prefs.feitos
            val total = prefs.total.coerceAtLeast(1)
            val frac = feitos.toFloat() / total
            val txt = prefs.txt.ifBlank { "$feitos de $total" }
            setProgress(workDataOf("p" to frac, "txt" to txt, "feitos" to feitos, "total" to total))
            runCatching { setForeground(fg(((frac * 100).toInt()).coerceIn(0, 100), txt)) }

            if (feitos == lastFeitos) idle++ else idle = 0
            lastFeitos = feitos
            // Serviço morreu sem marcar ativo=false → reinicia.
            if (idle >= 20 && prefs.ativo && !prefs.pausado) {
                Log.w(TAG, "sem progresso — reiniciando :scan")
                OrganizarScanService.start(ctx, prefs.bucketId)
                idle = 0
            }
            delay(500)
        }
        return Result.success()
    }
}
