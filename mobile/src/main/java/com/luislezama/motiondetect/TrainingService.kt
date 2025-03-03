package com.luislezama.motiondetect

/*import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TrainingService : Service() {
    companion object {
        fun startTrainingService(context: Context) {
            val serviceIntent = Intent(context, TrainingService::class.java)
            context.startService(serviceIntent)
        }

        fun stopTrainingService(context: Context) {
            val serviceIntent = Intent(context, TrainingService::class.java)
            context.stopService(serviceIntent)
        }
    }


    private val CHANNEL_ID = "TrainingServiceChannel"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.train_status_receiving_notification))
            .setSmallIcon(R.drawable.ic_train)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .build()

        notification.flags = NotificationCompat.FLAG_ONGOING_EVENT

        startForeground(1, notification)

        return START_STICKY // Asegura que el servicio se reinicie si se detiene
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Canal de Servicio de Entrenamiento",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }
}*/