package it.agrilloit.turegistri.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import it.agrilloit.turegistri.ui.YouRecActivity
import it.agrilloit.turegistri.R
import it.agrilloit.turegistri.record.RecordingEngine

/**
 * Servizio foreground (tipo camera|microphone) che mantiene viva la registrazione
 * quando l'utente esce dall'app. La logica sta interamente nel [RecordingEngine]:
 * il servizio gestisce solo la notifica e i comandi.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "it.agrilloit.turegistri.action.START_SERVICE"
        const val ACTION_STOP = "it.agrilloit.turegistri.action.STOP_RECORDING"
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 42

        fun startIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                RecordingEngine.get(this).stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                val engine = RecordingEngine.get(this)
                startInForeground(engine.startedAtRealtimeMs)
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(chronometerBase: Long) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, YouRecActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rec_dot)
            .setContentTitle(getString(R.string.recording_in_progress))
            .setContentText("TuRegistri")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setUsesChronometer(chronometerBase > 0)
            .setWhen(if (chronometerBase > 0) chronometerBase else System.currentTimeMillis())
            .addAction(0, getString(R.string.rec_stop_action), stopIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        val engine = RecordingEngine.get(this)
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (engine.screenDisplayWanted) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_in_progress),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }
}
