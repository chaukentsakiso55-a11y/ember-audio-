package com.cyberpulse.ember.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.cyberpulse.ember.MainActivity
import com.cyberpulse.ember.audio.EmberAudioService
import com.cyberpulse.ember.audio.VoiceModelInstaller
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EmberBridge(private val activity: MainActivity) {
    private val context: Context get() = activity
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @JavascriptInterface fun startListening() {
        activity.runOnUiThread {
            if (!VoiceModelInstaller.isInstalled(context)) {
                installVoiceModel()
            } else {
                activity.startAudioService()
            }
        }
    }

    @JavascriptInterface fun stopListening() {
        activity.runOnUiThread { activity.stopAudioService() }
    }

    @JavascriptInterface fun installVoiceModel() {
        if (VoiceModelInstaller.isInstalled(context)) {
            activity.sendNativeEvent("model-ready", "Offline English voice pack is installed")
            return
        }
        activity.sendNativeEvent("model-progress", "Downloading offline English voice pack…")
        VoiceModelInstaller.install(context, { percent ->
            activity.sendNativeEvent("model-progress", "Downloading voice pack: $percent%")
        }) { result ->
            activity.runOnUiThread {
                result.onSuccess {
                    activity.sendNativeEvent("model-ready", "Offline English voice pack installed")
                    activity.startAudioService()
                }.onFailure {
                    activity.sendNativeEvent("error", it.message ?: "Voice pack installation failed")
                }
            }
        }
    }

    @JavascriptInterface fun isVoiceModelInstalled(): Boolean = VoiceModelInstaller.isInstalled(context)

    @JavascriptInterface fun speak(text: String, language: String = "en-US") {
        activity.runOnUiThread {
            val safeText = text.take(1200)
            if (tts == null) {
                ttsReady = false
                tts = TextToSpeech(context) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        tts?.language = Locale.forLanguageTag(language)
                        tts?.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, "ember-${System.currentTimeMillis()}")
                    }
                }
            } else if (ttsReady) {
                tts?.language = Locale.forLanguageTag(language)
                tts?.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, "ember-${System.currentTimeMillis()}")
            }
        }
    }

    @JavascriptInterface fun executeCommand(raw: String): String {
        val q = raw.trim().lowercase(Locale.US)
        if (q.isBlank()) return reply("I didn't hear a command.")
        return when {
            q.contains("stop listening") || q.contains("turn off listening") -> {
                activity.runOnUiThread { activity.stopAudioService() }
                reply("Voice listening stopped.")
            }
            q.contains("open settings") || q == "settings" -> {
                activity.runOnUiThread { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                reply("Opening Android settings.")
            }
            q.contains("open ") -> openRequestedApp(q.removePrefix("open ").trim())
            q.contains("volume up") || q.contains("increase volume") || q.contains("louder") -> {
                audioManager().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                reply("Volume increased.")
            }
            q.contains("volume down") || q.contains("decrease volume") || q.contains("quieter") -> {
                audioManager().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                reply("Volume decreased.")
            }
            q.contains("mute") -> {
                audioManager().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                reply("Media muted.")
            }
            q.contains("unmute") -> {
                audioManager().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                reply("Media unmuted.")
            }
            q.contains("pause music") || q.contains("pause playback") || q == "pause" -> {
                sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                reply("Pause command sent to the active media app.")
            }
            q.contains("play music") || q.contains("resume music") || q == "play" -> {
                sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                reply("Play command sent to the active media app.")
            }
            q.contains("what time") || q == "time" -> reply("It is ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}.")
            q.contains("what date") || q.contains("what day") -> reply("Today is ${SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())}.")
            q.contains("phone audio") || q.contains("switch to phone") -> {
                if (android.os.Build.VERSION.SDK_INT >= 31) audioManager().clearCommunicationDevice()
                reply("Android will use its default phone audio route.")
            }
            q.contains("what can you do") || q.contains("help") -> reply("I can open apps, change media volume, send play and pause commands, tell the time and date, and listen for Hey Ember offline.")
            else -> reply("I heard: $raw. That command is not available yet. Online AI and live translation are not connected in this build.")
        }
    }

    private fun openRequestedApp(name: String): String {
        val apps = listOf(
            Triple("youtube", "com.google.android.youtube", "YouTube"),
            Triple("whatsapp", "com.whatsapp", "WhatsApp"),
            Triple("chrome", "com.android.chrome", "Chrome"),
            Triple("spotify", "com.spotify.music", "Spotify"),
            Triple("maps", "com.google.android.apps.maps", "Maps"),
            Triple("camera", "com.android.camera", "Camera"),
            Triple("calculator", "com.google.android.calculator", "Calculator"),
            Triple("messages", "com.google.android.apps.messaging", "Messages")
        )
        val match = apps.firstOrNull { name.contains(it.first) }
        if (match == null) return reply("I don't have an app match for $name.")
        val launch = context.packageManager.getLaunchIntentForPackage(match.second)
            ?: return reply("${match.third} is not installed or Android did not provide a launch activity.")
        activity.runOnUiThread { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return reply("Opening ${match.third}.")
    }

    private fun sendMediaKey(code: Int) {
        val manager = audioManager()
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code)
        manager.dispatchMediaKeyEvent(down)
        manager.dispatchMediaKeyEvent(up)
    }

    private fun audioManager() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun reply(text: String): String = JSONObject().put("ok", true).put("text", text).toString()

    @JavascriptInterface fun getAudioDevices(): String {
        val manager = audioManager()
        val result = JSONArray()
        val devices = try {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).toList()
        } catch (_: SecurityException) { emptyList() }
        devices.forEach { d ->
            result.put(JSONObject().apply {
                put("id", d.id)
                put("productName", d.productName?.toString() ?: "Audio device")
                put("type", typeName(d.type))
                put("isSource", d.isSource)
                put("isSink", d.isSink)
            })
        }
        return result.toString()
    }

    @JavascriptInterface fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface fun getPlatformInfo(): String = JSONObject().apply {
        put("android", android.os.Build.VERSION.SDK_INT)
        put("manufacturer", android.os.Build.MANUFACTURER)
        put("model", android.os.Build.MODEL)
        put("wakeEngine", "Vosk offline English model")
        put("voiceModelInstalled", VoiceModelInstaller.isInstalled(context))
    }.toString()

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "AUX / line out"
        else -> "Audio device ($type)"
    }
}
