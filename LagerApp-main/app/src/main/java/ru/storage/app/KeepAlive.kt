package ru.storage.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Работа не прерывается при выключении экрана: пока приложение читает сканы через ИИ, грузит каталог
 * или отправляет данные, держим процессор и Wi-Fi включёнными и показываем уведомление «Приложение работает»
 * (служба переднего плана — Android не усыпляет сеть и не убивает такое приложение).
 * Счётчик: несколько работ сразу — служба живёт, пока не закончится последняя.
 */
object KeepAlive {
    private val lock = Any()
    private var count = 0
    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private const val MAX_MS = 3 * 60 * 60 * 1000L

    @Volatile var text = "Идёт работа…"
    @Volatile private var app: Context? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** Начало работы. Обязательно парой с [end] (в finally). */
    @Suppress("DEPRECATION")
    fun begin(ctx: Context?, label: String) {
        val a = ctx?.applicationContext ?: app ?: return
        app = a
        synchronized(lock) {
            text = label
            count++
            try {
                val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
                val w = wl ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lager:keepalive").also {
                    it.setReferenceCounted(false)
                    wl = it
                }
                w.acquire(MAX_MS)
            } catch (e: Exception) {
            }
            try {
                val wm = a.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    val l = wifi ?: wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lager:keepalive").also {
                        it.setReferenceCounted(false)
                        wifi = it
                    }
                    if (!l.isHeld) l.acquire()
                }
            } catch (e: Exception) {
            }
            try {
                ContextCompat.startForegroundService(a, Intent(a, KeepAliveService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    fun end() {
        val a = app ?: return
        synchronized(lock) {
            if (count > 0) count--
            if (count == 0) {
                try { wl?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
                try { wifi?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
                try { a.stopService(Intent(a, KeepAliveService::class.java)) } catch (e: Exception) { }
            }
        }
    }

    suspend fun <T> run(ctx: Context?, label: String, block: suspend () -> T): T {
        begin(ctx, label)
        try {
            return block()
        } finally {
            end()
        }
    }
}

class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Работа в фоне", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Приложение работает")
            .setContentText(KeepAlive.text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "keepalive"
        const val NOTIF_ID = 4711
    }
}
