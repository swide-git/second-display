package io.github.swiderekartmax.seconddisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Persistent Android Foreground Service that keeps the native UxPlay AirPlay
 * receiver alive and discoverable via mDNS even when MainActivity is closed or minimized.
 */
class ReceiverService : Service() {
    companion object {
        private const val TAG = "ReceiverService"
        const val CHANNEL_ID = "receiver_service"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "io.github.swiderekartmax.seconddisplay.action.START"
        const val ACTION_STOP = "io.github.swiderekartmax.seconddisplay.action.STOP"
        const val ACTION_DISCONNECT = "io.github.swiderekartmax.seconddisplay.action.DISCONNECT"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java).apply {
                action = ACTION_START
            }
            // Android 12+ refuses foreground-service starts from the background outside the
            // exempt paths (activity in front, boot, package replaced); never crash the caller.
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Foreground service start not allowed now: ${e.message}")
            }
        }

        fun stop(context: Context) {
            // Starting a stopped service just to stop it would try to enter the foreground
            // from the background, which Android 12+ refuses.
            if (!isRunning) return
            val intent = Intent(context, ReceiverService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Service stop not allowed now: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "Creating ReceiverService")
        ReceiverTileService.refresh(this)

        createNotificationChannel()
        enterForeground("Initializing receiver…")

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "seconddisplay:receiver")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        ReceiverServer.init(applicationContext)
        // Single launch path: ReceiverServer fires this when a mirroring or audio-only session starts while the activity isn't resumed.
        ReceiverServer.onIncomingStreamBackgroundCallback = {
            launchMainActivity()
        }
        ReceiverServer.startServer()

        // Observe server state changes to update notification
        ReceiverServer.isAudioStreaming.onEach {
            updateNotification(ReceiverServer.statusText.value, isMirroring = ReceiverServer.isMirroring.value)
        }.launchIn(serviceScope)
        ReceiverServer.isMirroring.onEach { mirroring ->
            val client = ReceiverServer.clientName.value
            val text = if (mirroring) {
                "Mirroring ${client ?: "client"}"
            } else {
                ReceiverServer.idleStatus
            }
            updateNotification(text, isMirroring = mirroring)
        }.launchIn(serviceScope)

        ReceiverServer.statusText.onEach { status ->
            if (!ReceiverServer.isMirroring.value) {
                updateNotification(status, isMirroring = false)
            }
        }.launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            // Notification button: end the session, keep the receiver.
            ReceiverServer.disconnectClient("notification")
            return if (ReceiverServer.runInBackground) START_STICKY else START_NOT_STICKY
        }
        // A null intent is Android restarting a sticky service after killing the process: only
        // wanted when the user allowed the receiver to run in the background.
        val restartWithoutApp = intent == null && !ReceiverServer.runInBackground
        if (intent?.action == ACTION_STOP || restartWithoutApp) {
            Log.i(TAG, if (restartWithoutApp) "Sticky restart with background off -> stopping" else "Received ACTION_STOP -> stopping service")
            ReceiverServer.stopServer()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // Not stopSelf(): a start sent right after this stop (the user came straight back)
            // keeps the service alive.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // A start may follow a stop that already left the foreground; every
        // startForegroundService() must be answered with startForeground().
        enterForeground(ReceiverServer.statusText.value)
        ReceiverServer.init(applicationContext)
        ReceiverServer.startServer()
        return if (ReceiverServer.runInBackground) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroying ReceiverService")
        isRunning = false
        ReceiverTileService.refresh(this)
        serviceScope.cancel()
        ReceiverServer.onIncomingStreamBackgroundCallback = null

        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground(contentText: String) {
        // Android 14+ requires the type at runtime to match the manifest declaration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(contentText),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(contentText))
        }
    }

    private fun launchMainActivity() {
        Log.i(TAG, "Incoming stream active in background: launching MainActivity")
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "\"Display over other apps\" not granted; Android 10+ will block this background launch")
        }
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity from background", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Second Display Receiver",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Second Display active and discoverable in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String, isMirroring: Boolean = false): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("Second Display Receiver")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF904D00.toInt())
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        // Disconnect during a session; Stop turns the receiver off until the app is opened again.
        if (ReceiverServer.isMirroring.value || ReceiverServer.isAudioStreaming.value) {
            builder.addAction(serviceAction(ACTION_DISCONNECT, "Disconnect", requestCode = 1))
        }
        builder.addAction(serviceAction(ACTION_STOP, "Stop", requestCode = 2))

        if (isMirroring) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        return builder.build()
    }

    private fun serviceAction(action: String, title: String, requestCode: Int): Notification.Action {
        val intent = PendingIntent.getService(
            this, requestCode,
            Intent(this, ReceiverService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification), title, intent
        ).build()
    }

    private fun updateNotification(contentText: String, isMirroring: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(contentText, isMirroring))
    }
}
