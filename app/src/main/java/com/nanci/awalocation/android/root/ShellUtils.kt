package com.nanci.awalocation.android.root

import kotlin.concurrent.thread

object ShellUtils {
    fun hasRoot(): Boolean {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su").redirectErrorStream(true).start()
            process.outputStream.use { out ->
                out.write("exit\n".toByteArray())
                out.flush()
            }
            process.inputStream.use { it.readBytes() }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    fun setEnforceMode(enabled: Boolean) {
        var process: Process? = null
        try {
            process = ProcessBuilder("su").redirectErrorStream(true).start()
            process.outputStream.use { out ->
                out.write("setenforce ${if (enabled) "1" else "0"}\n".toByteArray())
                out.write("exit\n".toByteArray())
                out.flush()
            }
            process.inputStream.use { it.readBytes() }
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            process?.destroy()
        }
    }

    fun executeCommand(command: String): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su").start()
            process.outputStream.use { out ->
                out.write("$command\n".toByteArray())
                out.write("exit\n".toByteArray())
                out.flush()
            }
            var stderrText = ""
            val errThread = thread(start = true) {
                stderrText = runCatching { process.errorStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            }
            val stdoutText = process.inputStream.bufferedReader().use { it.readText() }
            errThread.join()
            if (process.waitFor() != 0 && stderrText.isNotEmpty()) stderrText else stdoutText
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        } finally {
            process?.destroy()
        }
    }

    fun executeCommandToBytes(command: String): ByteArray {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su").start()
            process.outputStream.use { out ->
                out.write("$command\n".toByteArray())
                out.write("exit\n".toByteArray())
                out.flush()
            }
            var stderrBytes = ByteArray(0)
            val errThread = thread(start = true) {
                stderrBytes = runCatching { process.errorStream.use { it.readBytes() } }.getOrDefault(ByteArray(0))
            }
            val stdoutBytes = process.inputStream.use { it.readBytes() }
            errThread.join()
            if (process.waitFor() != 0 && stderrBytes.isNotEmpty()) stderrBytes else stdoutBytes
        } catch (e: Exception) {
            e.printStackTrace()
            ByteArray(0)
        } finally {
            process?.destroy()
        }
    }
}