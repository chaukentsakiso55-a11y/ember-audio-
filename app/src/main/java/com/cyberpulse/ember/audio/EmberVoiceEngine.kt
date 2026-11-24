package com.cyberpulse.ember.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class EmberVoiceEngine(
    private val context: Context,
    private val modelDirectory: File,
    private val onEvent: (String, String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var worker: Thread? = null
    @Volatile private var awaitingCommand = false

    fun start() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required"
        }
        if (running.get()) return
        check(modelDirectory.isDirectory) { "Offline speech model is not installed" }
        running.set(true)
        worker = Thread({ captureLoop() }, "Ember-Vosk-Audio").also { it.start() }
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            model = Model(modelDirectory.absolutePath)
            recognizer = Recognizer(model, 16000.0f)
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "This device does not support the required microphone format" }
            val bufferSize = maxOf(min * 2, 8192)
            val audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone input" }
            recorder = audio
            audio.startRecording()
            onEvent("listening", "Listening for Hey Ember")
            val buffer = ByteArray(bufferSize / 2)
            while (running.get()) {
                val n = audio.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val r = recognizer ?: continue
                if (r.acceptWaveForm(buffer, n)) {
                    val text = JSONObject(r.result).optString("text").trim().lowercase(Locale.US)
                    if (text.isNotBlank()) handleFinalText(text)
                } else {
                    val partial = JSONObject(r.partialResult).optString("partial").trim().lowercase(Locale.US)
                    if (!awaitingCommand && partial.contains("hey ember")) {
                        awaitingCommand = true
                        onEvent("wake", "Hey Ember")
                    }
                }
            }
        } catch (e: Exception) {
            onEvent("error", e.message ?: "Voice engine stopped")
        } finally {
            running.set(false)
            try { recorder?.stop() } catch (_: Exception) {}
            recorder?.release()
            recorder = null
            recognizer?.close()
            recognizer = null
            model?.close()
            model = null
            onEvent("stopped", "Voice engine stopped")
        }
    }

    private fun handleFinalText(text: String) {
        val wakeIndex = text.indexOf("hey ember")
        if (wakeIndex >= 0) {
            val command = text.substring(wakeIndex + "hey ember".length).trim()
            awaitingCommand = true
            onEvent("wake", "Hey Ember")
            if (command.isNotBlank()) {
                awaitingCommand = false
                onEvent("command", command)
            }
            return
        }
        if (awaitingCommand) {
            awaitingCommand = false
            onEvent("command", text)
        }
    }

    fun stop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        worker?.join(1200)
        worker = null
    }
}
