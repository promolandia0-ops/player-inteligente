package br.geracaoai.player

import android.app.Application
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
        if (proc.endsWith(":scan")) return
        // Processo UI: retoma varredura se ficou marcada como interrompida.
        runCatching { OrganizarWorker.autoRetomarSePreciso(this) }
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
    }
}
