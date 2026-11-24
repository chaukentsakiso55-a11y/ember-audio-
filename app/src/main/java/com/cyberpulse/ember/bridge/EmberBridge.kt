package com.cyberpulse.ember.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.cyberpulse.ember.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class EmberBridge(private val activity: MainActivity) {
    private val context: Context get() = activity
    private var tts: TextToSpeech? = null
    @JavascriptInterface fun startListening() { activity.runOnUiThread { activity.startAudioService() } }
    @JavascriptInterface fun stopListening() { activity.runOnUiThread { activity.stopAudioService() } }
    @JavascriptInterface fun speak(text: String, language: String = "en-US") {
        activity.runOnUiThread {
            if (tts == null) tts = TextToSpeech(context) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.forLanguageTag(language) }
            tts?.speak(text.take(1200), TextToSpeech.QUEUE_FLUSH, null, "ember-${System.currentTimeMillis()}")
        }
    }
    @JavascriptInterface fun getAudioDevices(): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = JSONArray()
        val devices = try { manager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).toList() } catch (_: SecurityException) { emptyList() }
        devices.forEach { d -> result.put(JSONObject().apply {
            put("id", d.id); put("productName", d.productName?.toString() ?: "Audio device")
            put("type", typeName(d.type)); put("isSource", d.isSource); put("isSink", d.isSink)
        }) }
        return result.toString()
    }
    @JavascriptInterface fun hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    @JavascriptInterface fun getPlatformInfo(): String = JSONObject().apply {
        put("android", BuildVersion.sdk); put("manufacturer", android.os.Build.MANUFACTURER); put("model", android.os.Build.MODEL)
        put("wakeEngine", "Vosk dependency included; model pack must be installed")
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
    private object BuildVersion { val sdk get() = android.os.Build.VERSION.SDK_INT }
}
