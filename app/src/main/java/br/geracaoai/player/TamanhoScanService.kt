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
        private const val FRAMES = 16

        const val PROG_REORG = "__reorg"
        private fun metricPrefs(ctx: Context) = ctx.getSharedPreferences("tamanho_metric", Context.MODE_PRIVATE)
        fun metricVersao(ctx: Context) = metricPrefs(ctx).getInt("v", 1)
        fun marcarMetricVersao(ctx: Context) =
            metricPrefs(ctx).edit().putInt("v", TamanhoLogica.METRIC_VERSAO).apply()
        /** Se a métrica mudou, zera áreas salvas para forçar re-medida no Reorganizar. */
        fun garantirMetricV(ctx: Context): Boolean {
            if (metricVersao(ctx) >= TamanhoLogica.METRIC_VERSAO) return false
            runCatching { GaleriaDb(ctx).zerarAreas() }
            metricPrefs(ctx).edit().putInt("v", 0).apply() // marca “em migração” até terminar um scan
            return true
        }

        /** Bloqueia se análise por vídeo, org da galeria ou outro tamanho estiver ativo. */
        private fun ocupado(ctx: Context): String? {
            if (AnaliseWorker.rodando(ctx)) return "Espere a análise do vídeo terminar"
            if (rodando(ctx)) return "Já estou medindo tamanho — aguarde"
            if (OrgPrefs(ctx).ativo && !OrgPrefs(ctx).pausado) return "Espere a organização da galeria terminar"
            return null
        }

        private fun setProgPar(ctx: Context, alvoId: String, frac: Float, txt: String) {
            for (id in TamanhoProgresso.idsDoPar(alvoId.ifBlank { PROG_REORG })) {
                TamanhoProgresso.set(ctx, id, frac, txt)
            }
        }

        private fun clearProgPar(ctx: Context, alvoId: String) {
            for (id in TamanhoProgresso.idsDoPar(alvoId.ifBlank { PROG_REORG })) {
                TamanhoProgresso.clear(ctx, id)
            }
        }

        fun iniciar(ctx: Context, alvoId: String = "") {
            ocupado(ctx)?.let {
                android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val alvo = TamanhoLogica.Alvo.deId(alvoId)
            val nude = runCatching {
                GaleriaDb(ctx).todos().values.filter { it.classif == ClassifNudez.NUDE }
            }.getOrDefault(emptyList())
            val (total, pend) = TamanhoProgresso.candidatos(nude, alvo)
            if (total == 0) {
                val msg = "Nenhum vídeo em Putaria ainda: toque em Organizar primeiro"
                if (alvoId.isNotEmpty()) setProgPar(ctx, alvoId, 1f, msg)
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                return
            }
            if (pend == 0) {
                val msg = "Nada novo para medir nesta pasta"
                if (alvoId.isNotEmpty()) setProgPar(ctx, alvoId, 1f, msg)
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            // Feedback imediato no tile (antes do FGS subir).
            if (alvoId.isNotEmpty()) setProgPar(ctx, alvoId, 0.02f, "Analisando 0 de $pend")
            runCatching { OrganizarScanService.yieldToAnalise(ctx) }
            val i = Intent(ctx, TamanhoScanService::class.java).setAction(ACTION_START)
                .putExtra("alvo", alvoId)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Throwable) {
                App.gravarErro(ctx, "tamanho-iniciar", e)
                if (alvoId.isNotEmpty()) clearProgPar(ctx, alvoId)
                android.widget.Toast.makeText(
                    ctx, "Não iniciei a medição: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }

        /**
         * Reorganiza pastas Por tamanho: re-split instantâneo das métricas já salvas;
         * mede só Putaria sem métrica. [alvoId] vazio = os 6; senão só o par do tile.
         */
        fun reorganizar(ctx: Context, alvoId: String = "") {
            ocupado(ctx)?.let {
                android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val chave = alvoId.ifBlank { PROG_REORG }
            val migrou = garantirMetricV(ctx)
            val alvo = TamanhoLogica.Alvo.deId(alvoId)
            // Após bump de métrica: remede tudo do alvo (áreas zeradas).
            val nude = runCatching {
                GaleriaDb(ctx).todos().values.filter { it.classif == ClassifNudez.NUDE }
            }.getOrDefault(emptyList())
            val (total, pend) = TamanhoProgresso.candidatos(nude, alvo)
            if (total == 0) {
                val msg = "Nenhum vídeo em Putaria ainda: toque em Organizar primeiro"
                setProgPar(ctx, chave, 1f, msg)
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                return
            }
            if (pend == 0 && !migrou) {
                setProgPar(ctx, chave, 1f, "Pastas atualizadas")
                android.widget.Toast.makeText(
                    ctx, "Pastas por tamanho atualizadas (sem novos a medir)", android.widget.Toast.LENGTH_SHORT,
                ).show()
                return
            }
            val n = if (pend == 0) total else pend
            setProgPar(ctx, chave, 0.02f, if (migrou) "Nova métrica: 0 de $n" else "Reorganizando 0 de $n")
            runCatching { OrganizarScanService.yieldToAnalise(ctx) }
            val i = Intent(ctx, TamanhoScanService::class.java).setAction(ACTION_START)
                .putExtra("alvo", if (alvoId.isBlank()) "" else alvoId)
                .putExtra("prog", chave)
                .putExtra("forcar", migrou)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Throwable) {
                App.gravarErro(ctx, "tamanho-reorg", e)
                clearProgPar(ctx, chave)
                android.widget.Toast.makeText(
                    ctx, "Não iniciei: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG,
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
    private var progKey: String = ""

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
                clearProgPar(this, progKey.ifBlank { alvoAtual.ifBlank { PROG_REORG } })
                stopSelf()
            }
            ACTION_START -> {
                stop.set(false)
                alvoAtual = intent.getStringExtra("alvo") ?: ""
                progKey = intent.getStringExtra("prog")
                    ?: alvoAtual.ifBlank { PROG_REORG }
                subir(2, "Medindo…")
                val snap = TamanhoProgresso.get(this, progKey)
                setProgPar(this, progKey, 0.02f, snap?.txt?.ifBlank { "Analisando…" } ?: "Analisando…")
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
        val pk = progKey.ifBlank { alvoId.ifBlank { PROG_REORG } }
        try {
            Exclusao.esperarScanParar(ctx, 3_000L)
            val nude = db.todos().filter { it.value.classif == ClassifNudez.NUDE }
            val pend = nude.filter { (_, r) ->
                when (alvo) {
                    TamanhoLogica.Alvo.PEITUDA, TamanhoLogica.Alvo.PEITINHO -> TamanhoLogica.areaPendente(r.areaPeito)
                    TamanhoLogica.Alvo.RABUDA, TamanhoLogica.Alvo.RABINHO -> TamanhoLogica.areaPendente(r.areaBunda)
                    TamanhoLogica.Alvo.BUCETUDA, TamanhoLogica.Alvo.BUCETINHA -> TamanhoLogica.areaPendente(r.areaBuceta)
                    null -> TamanhoLogica.areaPendente(r.areaPeito) ||
                        TamanhoLogica.areaPendente(r.areaBunda) ||
                        TamanhoLogica.areaPendente(r.areaBuceta)
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
                setProgPar(ctx, pk, 1f, msg)
                subir(100, msg)
                marcarMetricVersao(ctx)
                stopSelf(); return
            }
            setProgPar(ctx, pk, 0.02f, "Analisando 0 de ${lista.size}")
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
                    setProgPar(ctx, pk, frac.coerceAtMost(0.99f), txt)
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
            setProgPar(ctx, pk, 1f, "Pronto")
            marcarMetricVersao(ctx)
        } catch (e: Throwable) {
            Log.e(TAG, "falhou", e)
            App.gravarErro(ctx, "tamanho", e)
            setProgPar(ctx, pk, 1f, "Erro: ${e.message ?: e.javaClass.simpleName}")
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
            // Mediana consistente (≥3 frames com referência); sem isso → 0 (incerto).
            acc.copy(
                areaPeito = TamanhoLogica.metricaConsistente(peitos, 3),
                areaBunda = TamanhoLogica.metricaConsistente(bundas, 3),
                areaBuceta = TamanhoLogica.metricaConsistente(bucetas, 3),
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
