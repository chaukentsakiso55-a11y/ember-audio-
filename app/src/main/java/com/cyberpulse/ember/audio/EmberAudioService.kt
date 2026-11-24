package com.cyberpulse.ember.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class EmberAudioService : Service() {
    private var engine: EmberVoiceEngine? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ember voice assistant", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Ember is listening")
            .setContentText("Wake-word detection is active. Stop it from Ember.")
            .setOngoing(true)
            .build()
        startForeground(41, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!VoiceModelInstaller.isInstalled(this)) {
            sendVoiceEvent("error", "Offline speech model is not installed. Open Ember and install the voice pack first.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine == null) {
            engine = EmberVoiceEngine(this, VoiceModelInstaller.modelDirectory(this)) { type, text ->
                sendVoiceEvent(type, text)
            }
            try {
                engine?.start()
            } catch (e: Exception) {
                sendVoiceEvent("error", e.message ?: "Could not start microphone")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun sendVoiceEvent(type: String, text: String) {
        sendBroadcast(Intent(ACTION_VOICE_EVENT).setPackage(packageName).apply {
            putExtra("type", type)
            putExtra("text", text)
        })
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_VOICE_EVENT = "com.cyberpulse.ember.VOICE_EVENT"
        private const val CHANNEL = "ember_audio_service"
    }
}
