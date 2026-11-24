package com.cyberpulse.ember

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cyberpulse.ember.audio.EmberAudioService
import com.cyberpulse.ember.bridge.EmberBridge
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var bridge: EmberBridge
    private var receiverRegistered = false

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val text = intent.getStringExtra("text") ?: ""
            if (type == "command") {
                val response = JSONObject(bridge.executeCommand(text)).optString("text")
                bridge.speak(response)
                sendNativeEvent("response", response)
            } else {
                sendNativeEvent(type, text)
                if (type == "wake") bridge.speak("Yes?")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(3, 5, 12)
        window.navigationBarColor = android.graphics.Color.rgb(3, 5, 12)
        bridge = EmberBridge(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(bridge, "EmberNative")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        registerVoiceReceiver()
        requestRuntimePermissions()
    }

    private fun registerVoiceReceiver() {
        val filter = IntentFilter(EmberAudioService.ACTION_VOICE_EVENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(voiceReceiver, filter)
        }
        receiverRegistered = true
    }

    fun sendNativeEvent(type: String, text: String) {
        if (!::web.isInitialized) return
        val safe = JSONObject.quote(text)
        val kind = JSONObject.quote(type)
        runOnUiThread {
            if (!web.isDestroyed) web.evaluateJavascript(
                "(function(){var d={type:$kind,text:$safe};window.dispatchEvent(new CustomEvent('ember-native-event',{detail:d}));var s=document.getElementById('ost');if(s)s.textContent=$safe;})();",
                null
            )
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 42)
    }

    fun startAudioService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions()
            sendNativeEvent("error", "Allow microphone access before starting Ember.")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, EmberAudioService::class.java))
    }

    fun stopAudioService() {
        stopService(Intent(this, EmberAudioService::class.java))
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(voiceReceiver) } catch (_: Exception) {}
        }
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
