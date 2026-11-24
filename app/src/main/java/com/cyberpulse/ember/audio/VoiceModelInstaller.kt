package com.cyberpulse.ember.audio

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

object VoiceModelInstaller {
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    private val executor = Executors.newSingleThreadExecutor()

    fun modelDirectory(context: Context) = File(context.filesDir, MODEL_NAME)
    fun isInstalled(context: Context): Boolean {
        val dir = modelDirectory(context)
        return File(dir, "am/final.mdl").isFile && File(dir, "conf/model.conf").isFile
    }

    fun install(context: Context, progress: (Int) -> Unit, complete: (Result<File>) -> Unit) {
        executor.execute {
            try {
                if (isInstalled(context)) {
                    complete(Result.success(modelDirectory(context)))
                    return@execute
                }
                val zip = File(context.cacheDir, "$MODEL_NAME.zip")
                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 45000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                }
                try {
                    connection.connect()
                    check(connection.responseCode in 200..299) { "Model download failed: HTTP ${connection.responseCode}" }
                    val total = connection.contentLengthLong
                    BufferedInputStream(connection.inputStream).use { input ->
                        FileOutputStream(zip).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var count: Long = 0
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                count += n
                                if (total > 0) progress(((count * 100) / total).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                check(sha256(zip).equals(MODEL_SHA256, ignoreCase = true)) { "Downloaded model checksum did not match. Please retry." }
                val staging = File(context.filesDir, "$MODEL_NAME-installing")
                staging.deleteRecursively()
                staging.mkdirs()
                ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val target = File(staging, entry.name)
                        val canonicalRoot = staging.canonicalPath + File.separator
                        check(target.canonicalPath.startsWith(canonicalRoot) || target.canonicalPath == staging.canonicalPath) { "Invalid model archive path" }
                        if (entry.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                        input.closeEntry()
                    }
                }
                val extracted = File(staging, MODEL_NAME)
                check(File(extracted, "am/final.mdl").isFile && File(extracted, "conf/model.conf").isFile) { "Model archive is incomplete" }
                val destination = modelDirectory(context)
                destination.deleteRecursively()
                check(extracted.renameTo(destination)) { "Could not install speech model" }
                staging.deleteRecursively()
                zip.delete()
                progress(100)
                complete(Result.success(destination))
            } catch (e: Exception) {
                complete(Result.failure(e))
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
