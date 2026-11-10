package br.geracaoai.player

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash log em arquivo + auto-retomada da organização de galeria.
 * O worker de scan roda em processo `:scan` (ver Manifest) para que um OOM
 * nele não derrube a UI.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instalarCrashLog()
        val proc = nomeProcesso()
        Log.i(TAG, "onCreate pid=${Process.myPid()} process=$proc")
        if (proc.contains(":")) return
        // Processo UI: recupera chaves DB (pós v1.3.0) e retoma varredura se interrompida.
        runCatching { OrgRecuperacao.rodarSePreciso(this) }
        runCatching { OrganizarWorker.autoRetomarSePreciso(this) }
        // v1.3.23: re-verificação rigorosa (1× após o upgrade) dos "Sem nudez" antigos — em segundo plano.
        Thread({ runCatching { ClassifMigracao.rodarSePreciso(this) } }, "classif-migra").also { it.priority = Thread.MIN_PRIORITY }.start()
    }

    private fun nomeProcesso(): String {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else {
                val f = File("/proc/self/cmdline")
                f.readText().trim { it <= ' ' || it == '\u0000' }
            }
        }.getOrDefault(packageName)
    }

    private fun instalarCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { gravarCrash(t, e) }
            prev?.uncaughtException(t, e)
        }
    }

    private fun gravarCrash(t: Thread, e: Throwable) {
        val dir = File(filesDir, "logs").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.name }?.dropLast(9)?.forEach { it.delete() }
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val body = buildString {
            appendLine("=== crash $ts ===")
            appendLine("thread=${t.name} pid=${Process.myPid()} process=${nomeProcesso()}")
            appendLine(sw.toString())
        }
        File(dir, "crash.log").appendText(body + "\n")
        File(dir, "crash-${System.currentTimeMillis()}.log").writeText(body)
        Log.e(TAG, "uncaught", e)
    }

    companion object {
        private const val TAG = "App"

        /** Erro tratado (não derrubou o app), mas útil para o usuário mandar. */
        fun gravarErro(ctx: Context, onde: String, e: Throwable) = runCatching {
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            File(dir, "erro.log").writeText("=== erro ($onde) $ts pid=${Process.myPid()} ===\n$sw")
        }

        /** Texto do último erro: crash Java, erro tratado e (Android 11+) motivo de saída dos processos, incl. crash nativo. */
        fun ultimoErro(ctx: Context): String = buildString {
            val dir = File(ctx.filesDir, "logs")
            val crash = dir.listFiles { f -> f.name.startsWith("crash-") }?.maxByOrNull { it.name }
            val erro = File(dir, "erro.log")
            appendLine("Player Inteligente ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            if (crash != null) { appendLine(crash.readText().take(12000)); appendLine() }
            if (erro.exists()) { appendLine(erro.readText().take(6000)); appendLine() }
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                val am = ctx.getSystemService(ActivityManager::class.java)
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val lista = am.getHistoricalProcessExitReasons(null, 0, 8)
                    .filter { it.reason != ApplicationExitInfo.REASON_USER_REQUESTED && it.reason != ApplicationExitInfo.REASON_EXIT_SELF }
                if (lista.isNotEmpty()) appendLine("=== saídas recentes dos processos ===")
                for (x in lista) {
                    appendLine("${fmt.format(Date(x.timestamp))} ${x.processName} motivo=${motivo(x.reason)} status=${x.status} ${x.description ?: ""}")
                    if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || x.reason == ApplicationExitInfo.REASON_ANR) {
                        runCatching { x.traceInputStream?.use { s -> appendLine(String(s.readBytes().copyOf(6000)).trimEnd('\u0000')) } }
                    }
                }
            }
            if (crash == null && !erro.exists()) appendLine("Nenhum erro Java registrado.")
        }

        private fun motivo(r: Int) = when (r) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH_JAVA"; ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVO"
            ApplicationExitInfo.REASON_ANR -> "ANR"; ApplicationExitInfo.REASON_LOW_MEMORY -> "POUCA_MEMORIA"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "USO_EXCESSIVO"; ApplicationExitInfo.REASON_SIGNALED -> "SINAL"
            ApplicationExitInfo.REASON_OTHER -> "OUTRO"; else -> r.toString()
        }
    }
}
