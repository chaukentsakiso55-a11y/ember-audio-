package com.cyberpulse.ember.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class EmberAudioService : Service() {
    private var engine: EmberVoiceEngine? = null
    private var audioManager: AudioManager? = null
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            sendVoiceEvent("device", addedDevices.joinToString { it.productName?.toString() ?: "Audio device connected" })
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            sendVoiceEvent("device", removedDevices.joinToString { it.productName?.toString() ?: "Audio device disconnected" })
        }
    }

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
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop listening",
                PendingIntent.getService(
                    this,
                    42,
                    Intent(this, EmberAudioService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .build()
        startForeground(41, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!VoiceModelInstaller.isInstalled(this)) {
            sendVoiceEvent("error", "Offline speech model is not installed. Open Ember and install the voice pack first.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (audioManager == null) {
            audioManager = getSystemService(AudioManager::class.java)
            audioManager?.registerAudioDeviceCallback(deviceCallback, null)
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
        try { audioManager?.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        audioManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_VOICE_EVENT = "com.cyberpulse.ember.VOICE_EVENT"
        private const val ACTION_STOP = "com.cyberpulse.ember.STOP_AUDIO"
        private const val CHANNEL = "ember_audio_service"
    }
}
