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
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
            // Prioridade absoluta: pausa/cancela galeria; não espera forever.
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

        // Não espera a galeria forever: máx. 3 s e segue (o yield já pediu para ela parar).
        withContext(Dispatchers.IO) { Exclusao.esperarScanParar(ctx, 3_000L) }

        val mmr = MediaMetadataRetriever()
        var concluido = false
        return@withContext try {
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@withContext Result.failure(workDataOf("erro" to "Não li a duração do vídeo"))
            if (dur <= 0L) return@withContext Result.failure(workDataOf("erro" to "Vídeo sem duração"))

            val fps = prefs.fps.coerceIn(1, 4)
            val grosso = AnalisePlano.passoGrosso(fps); val fino = AnalisePlano.passoFino(fps)
            val chave = Cache.chave(ctx, uri)
            // Resultados crescem em ordem de leitura (consumidores ordenam por tempo).
            val tempos = ArrayList<Long>()
            val sc = Categoria.entries.associateWith { ArrayList<Float>() }
            val feitos = HashSet<Long>()
            fun snapshot(parcial: Boolean): Analise {
                val n = tempos.size
                return Analise(dur, LongArray(n) { tempos[it] }, sc.mapValues { (_, l) -> FloatArray(n) { l[it] } }, parcial = parcial)
            }

            // Confirma o asset do modelo antes de gastar tempo no vídeo.
            try {
                Detector.carregarModelo(ctx)
            } catch (e: Throwable) {
                Log.e(TAG, "modelo", e)
                App.gravarErro(ctx, "modelo", e)
                setProgress(workDataOf("p" to 0f, "uri" to uri.toString(), "txt" to "Erro no modelo"))
                return@withContext Result.failure(workDataOf("erro" to errMsg(e)))
            }

            val syncs = withContext(Dispatchers.IO) { AnalisePlano.lerKeyframes(ctx, uri) }
            Log.i(TAG, "keyframes=${syncs?.size ?: -1} fps=$fps grosso=$grosso fino=$fino")

            // Sempre CPU — NNAPI derrubava o app em vários aparelhos.
            Detector(ctx, false).use { det ->
                val ini = System.currentTimeMillis()
                var feitosTotal = 0
                var prevAss: IntArray? = null
                var prevMapa: Map<Categoria, Float>? = null

                /** Lê [pedidos] numa thread (decodifica o próximo enquanto o detector trabalha no atual). */
                suspend fun fase(pedidos: List<AnalisePlano.Pedido>, base: Float, faixa: Float): Boolean {
                    if (pedidos.isEmpty()) return true
                    val canal = Channel<Pair<AnalisePlano.Pedido, Bitmap?>>(2)
                    val parar = java.util.concurrent.atomic.AtomicBoolean(false)
                    return coroutineScope {
                        val prod = launch(Dispatchers.IO) {
                            try {
                                for (p in pedidos) {
                                    if (parar.get()) break
                                    val bmp = try {
                                        mmr.getScaledFrameAtTime(p.tMs * 1000, if (p.exato) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                                    } catch (e: Throwable) { Log.w(TAG, "frame ${p.tMs}", e); null }
                                    canal.send(p to bmp)
                                }
                            } finally { canal.close() }
                        }
                        var i = 0
                        for ((p, bmp) in canal) {
                            if (isStopped) parar.set(true)
                            if (parar.get()) { bmp?.recycle(); continue }
                            var mapa: Map<Categoria, Float>? = null
                            if (bmp != null) {
                                try {
                                    val ass = AnalisePlano.assinatura(bmp)
                                    mapa = if (AnalisePlano.quaseIgual(prevAss, ass)) prevMapa else det.pontuar(bmp)
                                    prevAss = ass; prevMapa = mapa
                                } catch (e: Throwable) { Log.w(TAG, "pontuar", e) }
                                finally { if (!bmp.isRecycled) bmp.recycle() }
                            }
                            tempos += p.tMs; feitos += p.tMs
                            for ((c, l) in sc) l += mapa?.get(c) ?: 0f
                            feitosTotal++; i++
                            if (i % 4 == 0 || i == pedidos.size) {
                                val frac = base + faixa * i / pedidos.size
                                val pct = (frac * 100).toInt().coerceIn(0, 99)
                                val el = (System.currentTimeMillis() - ini) / 1000
                                val rest = if (frac > 0.02f) (el * (1f - frac) / frac).toLong() else 0L
                                val txt = "$pct% · ${det.backend} · faltam ~${rest / 60}m${rest % 60}s"
                                setProgress(workDataOf("p" to frac, "uri" to uri.toString(), "txt" to txt))
                                AnaliseProgresso.set(ctx, uri.toString(), frac)
                                nm.notify(42, notif(pct, txt))
                                if (i % 60 == 0) Cache.salvar(ctx, chave, snapshot(true), fino)
                            }
                        }
                        prod.join()
                        !parar.get()
                    }
                }

                // 1) passada grossa
                val p1 = AnalisePlano.resolver(AnalisePlano.alvosGrossos(startMs, dur, grosso), syncs, grosso * 3 / 4)
                var ok = fase(p1, 0f, 0.85f)
                // 2) refino só onde há acerto (ou quase)
                if (ok) {
                    val n1 = tempos.size
                    val ts = LongArray(n1) { tempos[it] }
                    val mx = FloatArray(n1) { i -> sc.values.maxOf { it[i] } }
                    val alvos = AnalisePlano.alvosRefino(ts, mx, prefs.limiar * 0.6f, grosso, fino, dur)
                    ok = fase(AnalisePlano.resolver(alvos, syncs, fino * 3 / 4, feitos), 0.85f, 0.15f)
                }
                if (!ok) {
                    Cache.salvar(ctx, chave, snapshot(true), fino)
                    return@withContext Result.failure()
                }
            }

            Cache.salvar(ctx, chave, snapshot(false), fino)
            AnaliseProgresso.set(ctx, uri.toString(), 1f)
            concluido = true
            // limpa badge depois de um tempo curto — UI trata 1f como ✓
            Log.i(TAG, "ok frames=${tempos.size} dur=${dur}ms")
            Result.success(workDataOf("uri" to uri.toString()))
        } catch (e: Throwable) {
            Log.e(TAG, "falhou", e)
            App.gravarErro(ctx, "analise", e)
            AnaliseProgresso.clear(ctx, uri.toString())
            Result.failure(workDataOf("erro" to errMsg(e)))
        } finally {
            runCatching { mmr.release() }
            // Cancelado (notificação/Parar/novo vídeo) ou falhou: não deixa badge "x%" órfão na grade por horas.
            if (!concluido) runCatching { AnaliseProgresso.clear(ctx, uri.toString()) }
            // Se o player já fechou, a galeria pode voltar.
            if (!Exclusao.playerEstaAberto(ctx)) Exclusao.retomarGaleriaSePreciso(ctx)
        }
    }
}


/**
 * Núcleo testável da análise por vídeo (mesmo fluxo da v1.1.3 / v1.0.0):
 * pontua quadro a quadro, reporta progresso e monta o mapa de scores.
 */
private fun errMsg(e: Throwable): String {
    val parts = ArrayList<String>()
    var c: Throwable? = e
    var i = 0
    while (c != null && i++ < 4) {
        val m = c.message?.takeIf { it.isNotBlank() }
        parts += if (m != null) "${c.javaClass.simpleName}: $m" else c.javaClass.simpleName
        c = c.cause
    }
    return parts.joinToString(" → ").take(240)
}

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
