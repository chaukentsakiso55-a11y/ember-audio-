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
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    installNativeUiHooks(view)
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(bridge, "EmberNative")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        registerVoiceReceiver()
        requestRuntimePermissions()
    }

    private fun installNativeUiHooks(view: WebView) {
        val script = """
            (function(){
              if(window.__emberNativeHooksInstalled) return;
              window.__emberNativeHooksInstalled = true;
              function notice(message) {
                var status = document.getElementById('ost');
                if(status) status.textContent = message;
                var toast = document.getElementById('toast');
                if(toast) { toast.textContent = message; toast.style.display = 'block'; }
              }
              function runCommand(command) {
                if(!command || !window.EmberNative) return;
                try {
                  var result = JSON.parse(window.EmberNative.executeCommand(command));
                  notice(result.text || 'Command completed');
                  window.EmberNative.speak(result.text || '');
                  var chat = document.getElementById('chat');
                  if(chat) {
                    var you = document.createElement('div');
                    you.className = 'm you'; you.textContent = command; chat.appendChild(you);
                    var ember = document.createElement('div');
                    ember.className = 'm ember'; ember.textContent = result.text || ''; chat.appendChild(ember);
                    chat.scrollTop = chat.scrollHeight;
                  }
                } catch(e) { notice('Native command failed: ' + e.message); }
              }
              document.addEventListener('click', function(event) {
                var button = event.target.closest && event.target.closest('[data-a]');
                if(!button) return;
                var action = button.dataset.a;
                if(action === 'orb' || (action === 'go' && button.dataset.k === 'voice')) {
                  setTimeout(function(){ if(window.EmberNative) window.EmberNative.startListening(); }, 300);
                  return;
                }
                if(action === 'send' || action === 'chip') {
                  var input = document.getElementById('cmd');
                  var command = action === 'chip' ? (button.dataset.k || '') : (input ? input.value : '');
                  if(!command.trim()) return;
                  event.preventDefault(); event.stopImmediatePropagation();
                  if(input) input.value = '';
                  runCommand(command);
                  return;
                }
                if(action === 'tlisten' || action === 'tspeak') {
                  event.preventDefault(); event.stopImmediatePropagation();
                  notice('Live translation is not implemented in this build yet.');
                  return;
                }
                if(action === 'press' || action === 'conn' || action === 'disc' || action === 'simc' || action === 'simd' || action === 'dl' || action === 'chk' || action === 'runa') {
                  event.preventDefault(); event.stopImmediatePropagation();
                  notice('This control is not connected to a real Android feature yet.');
                }
              }, true);
              document.addEventListener('keydown', function(event) {
                if(event.key === 'Enter' && event.target && event.target.id === 'cmd') {
                  event.preventDefault(); event.stopImmediatePropagation();
                  var command = event.target.value; event.target.value = ''; runCommand(command);
                }
              }, true);
              window.addEventListener('ember-native-event', function(event) {
                var d = event.detail || {};
                if(d.type === 'wake') notice('Hey Ember detected. Listening for your command…');
                else if(d.type === 'command') notice('Processing: ' + d.text);
                else if(d.type === 'error') notice(d.text);
                else if(d.text) notice(d.text);
              });
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
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
