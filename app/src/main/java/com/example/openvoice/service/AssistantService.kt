package com.example.openvoice.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.openvoice.ui.MainActivity
import com.example.openvoice.R
import com.example.openvoice.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AssistantService : Service() {

    companion object {
        const val CHANNEL_ID = "openvoice_assistant"
        private const val NOTIFY_ID = 1

        fun isRunning() = instance != null
        private var instance: AssistantService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AssistantService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFY_ID, buildNotification("Initializing..."))
        Logger.i("Assistant service created", "Service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification("Listening for 'Hey OpenVoice'")
        Logger.i("Assistant service running", "Service")
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        Logger.i("Assistant service destroyed", "Service")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "OpenVoice Assistant", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenVoice")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
