package br.geracaoai.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Análise por vídeo — mesmo caminho da v1.0.0 (WorkManager no processo principal +
 * MediaMetadataRetriever.getScaledFrameAtTime 320 px, um quadro por vez).
 * Diferenças vs v1.0.0: forçar CPU (sem NNAPI), catch Throwable + toast via output,
 * e pedir à galeria para ceder (no máx. 3 s, sem travar).
 */
class AnaliseWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val NOME = "analise"
        const val CANAL = "analise"
        private const val TAG = "Analise"

        fun iniciar(ctx: Context, uri: Uri, startMs: Long = 0L) {
            // Prioridade absoluta: pausa/cancela galeria e medição de tamanho; não espera forever.
            runCatching { TamanhoScanService.parar(ctx) }
            runCatching { OrganizarScanService.yieldToAnalise(ctx) }
            // Badge imediato na UI (evita "0% preso" sem feedback se WorkManager demorar a subir).
            AnaliseProgresso.set(ctx, uri.toString(), 0.01f)
            val req = OneTimeWorkRequestBuilder<AnaliseWorker>()
                .setInputData(workDataOf("uri" to uri.toString(), "startMs" to startMs))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOME, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelar(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NOME)
            // limpa badges de progresso órfãos
            AnaliseProgresso.todosAtivos(ctx).keys.forEach { AnaliseProgresso.clear(ctx, it) }
        }

        fun rodando(ctx: Context): Boolean = try {
            WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(NOME).get()
                .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        } catch (_: Throwable) { false }
    }

    private fun notif(p: Int, texto: String) = NotificationCompat.Builder(applicationContext, CANAL)
        .setSmallIcon(R.drawable.ic_notif).setContentTitle("Analisando vídeo").setContentText(texto)
        .setProgress(100, p, false).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .addAction(0, "Cancelar", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()

    private fun fg(p: Int, t: String) = ForegroundInfo(42, notif(p, t), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun getForegroundInfo() = fg(0, "Preparando")

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Análise de vídeo", NotificationManager.IMPORTANCE_LOW))
        runCatching { setForeground(fg(0, "Preparando")) }

        val uri = Uri.parse(inputData.getString("uri") ?: return@withContext Result.failure())
        AnaliseProgresso.set(applicationContext, uri.toString(), 0.02f)
        val startMs = inputData.getLong("startMs", 0L).coerceAtLeast(0L)
        val prefs = Prefs(ctx)
        val intervalo = 1000L / prefs.fps.coerceIn(1, 4)

        // Não espera a galeria forever: máx. 3 s e segue (o yield já pediu para ela parar).
        withContext(Dispatchers.IO) { Exclusao.esperarScanParar(ctx, 3_000L) }

        val mmr = MediaMetadataRetriever()
        return@withContext try {
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@withContext Result.failure(workDataOf("erro" to "Não li a duração do vídeo"))
            if (dur <= 0L) return@withContext Result.failure(workDataOf("erro" to "Vídeo sem duração"))

            // Quadros a partir da posição atual, depois do início (como a v1.0.0, mas começando onde o usuário está).
            val todos = ArrayList<Long>()
            var t = (startMs / intervalo) * intervalo
            while (t <= dur) { todos.add(t.coerceIn(0L, dur)); t += intervalo }
            t = 0L
            while (t < startMs) { if (t !in todos) todos.add(t); t += intervalo }
            if (todos.isEmpty()) todos.add(0L)
            val tempos = todos.toLongArray()
            val n = tempos.size
            val sc = Categoria.entries.associateWith { FloatArray(n) }.toMutableMap()
            val chave = Cache.chave(ctx, uri)

            // Confirma o asset do modelo antes de gastar tempo no vídeo.
            try {
                Detector.carregarModelo(ctx)
            } catch (e: Throwable) {
                Log.e(TAG, "modelo", e)
                App.gravarErro(ctx, "modelo", e)
                setProgress(workDataOf("p" to 0f, "uri" to uri.toString(), "txt" to "Erro no modelo"))
                return@withContext Result.failure(workDataOf("erro" to (e.message ?: "Modelo ONNX não carregou")))
            }

            // Sempre CPU — NNAPI derrubava o app em vários aparelhos.
            Detector(ctx, false).use { det ->
                val ini = System.currentTimeMillis()
                for (i in 0 until n) {
                    if (isStopped) {
                        Cache.salvar(ctx, chave, Analise(dur, tempos.copyOf(i), sc.mapValues { it.value.copyOf(i) }, parcial = true), intervalo)
                        return@withContext Result.failure()
                    }
                    val bmp = try {
                        mmr.getScaledFrameAtTime(tempos[i] * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320)
                    } catch (e: Throwable) {
                        Log.w(TAG, "frame ${tempos[i]}", e); null
                    }
                    if (bmp != null) {
                        try { det.pontuar(bmp).forEach { (c, v) -> sc[c]!![i] = v } }
                        catch (e: Throwable) { Log.w(TAG, "pontuar", e) }
                        finally { if (!bmp.isRecycled) bmp.recycle() }
                    }
                    if (i % 4 == 0 || i == n - 1) {
                        val p = (i + 1) * 100 / n
                        val el = (System.currentTimeMillis() - ini) / 1000
                        val rest = if (i > 0) el * (n - i - 1) / (i + 1) else 0
                        val txt = "$p% · ${det.backend} · faltam ~${rest / 60}m${rest % 60}s"
                        setProgress(workDataOf("p" to (i + 1).toFloat() / n, "uri" to uri.toString(), "txt" to txt))
                        AnaliseProgresso.set(ctx, uri.toString(), (i + 1).toFloat() / n)
                        nm.notify(42, notif(p, txt))
                        if (i % 20 == 0 && i > 0) {
                            Cache.salvar(ctx, chave,
                                Analise(dur, tempos.copyOf(i + 1), sc.mapValues { it.value.copyOf(i + 1) }, parcial = true), intervalo)
                        }
                    }
                }
            }
            runCatching {
                setProgress(workDataOf("p" to 0.95f, "uri" to uri.toString(), "txt" to "Áudio…"))
                AnaliseProgresso.set(ctx, uri.toString(), 0.95f)
                nm.notify(42, notif(95, "Gemido…"))
                val g = Gemido.analisar(ctx, uri, dur, intervalo)
                sc[Categoria.GEMIDO] = FloatArray(n) { i -> if (i < g.size) g[i] else 0f }
            }.onFailure { android.util.Log.w(TAG, "gemido", it) }

            Cache.salvar(ctx, chave, Analise(dur, tempos, sc), intervalo)
            AnaliseProgresso.set(ctx, uri.toString(), 1f)
            // limpa badge depois de um tempo curto — UI trata 1f como ✓
            Log.i(TAG, "ok frames=$n dur=${dur}ms")
            Result.success(workDataOf("uri" to uri.toString()))
        } catch (e: Throwable) {
            Log.e(TAG, "falhou", e)
            App.gravarErro(ctx, "analise", e)
            AnaliseProgresso.clear(ctx, uri.toString())
            Result.failure(workDataOf("erro" to (e.message ?: e.javaClass.simpleName)))
        } finally {
            runCatching { mmr.release() }
            // Se o player já fechou, a galeria pode voltar.
            if (!Exclusao.playerEstaAberto(ctx)) Exclusao.retomarGaleriaSePreciso(ctx)
        }
    }
}


/**
 * Núcleo testável da análise por vídeo (mesmo fluxo da v1.1.3 / v1.0.0):
 * pontua quadro a quadro, reporta progresso e monta o mapa de scores.
 */
object AnaliseMotor {
    fun processar(
        n: Int,
        pontuar: (indice: Int) -> Map<Categoria, Float>,
        onProgresso: (frac: Float) -> Unit,
        parado: () -> Boolean = { false },
    ): Map<Categoria, FloatArray> {
        require(n > 0)
        val sc = Categoria.entries.associateWith { FloatArray(n) }.toMutableMap()
        var last = -1
        for (i in 0 until n) {
            if (parado()) break
            pontuar(i).forEach { (c, v) -> sc[c]!![i] = v }
            if (i % 4 == 0 || i == n - 1) {
                val frac = (i + 1).toFloat() / n
                if ((frac * 100).toInt() != last) {
                    last = (frac * 100).toInt()
                    onProgresso(frac)
                }
            }
        }
        return sc
    }
}
