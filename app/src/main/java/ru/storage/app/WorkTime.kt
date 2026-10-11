package ru.storage.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* Рабочее время по геолокации: настройки, журнал заходов в зону, фоновая служба. */

data class WorkCfg(
    val enabled: Boolean = false,
    val hasPoint: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radius: Int = 200,          // метров
    val fromMin: Int = 8 * 60,      // начало проверки, минут от полуночи
    val toMin: Int = 18 * 60,       // конец проверки
    val intervalMin: Int = 5,       // как часто проверять, минут
    val days: Set<Int> = setOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY
    )
)

class WorkSession(val start: Long, val end: Long) // end = 0 — запись идёт

object WorkStore {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("worktime", Context.MODE_PRIVATE)

    fun cfg(ctx: Context): WorkCfg {
        val s = sp(ctx)
        val d = WorkCfg()
        val days = (s.getString("days", null) ?: d.days.joinToString(","))
            .split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        return WorkCfg(
            enabled = s.getBoolean("enabled", false),
            hasPoint = s.getBoolean("hasPoint", false),
            lat = java.lang.Double.longBitsToDouble(s.getLong("lat", 0L)),
            lon = java.lang.Double.longBitsToDouble(s.getLong("lon", 0L)),
            radius = s.getInt("radius", d.radius),
            fromMin = s.getInt("from", d.fromMin),
            toMin = s.getInt("to", d.toMin),
            intervalMin = s.getInt("interval", d.intervalMin),
            days = days
        )
    }

    fun saveCfg(ctx: Context, c: WorkCfg) {
        sp(ctx).edit()
            .putBoolean("enabled", c.enabled)
            .putBoolean("hasPoint", c.hasPoint)
            .putLong("lat", java.lang.Double.doubleToRawLongBits(c.lat))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(c.lon))
            .putInt("radius", c.radius)
            .putInt("from", c.fromMin)
            .putInt("to", c.toMin)
            .putInt("interval", c.intervalMin)
            .putString("days", c.days.joinToString(","))
            .apply()
    }

    @Synchronized
    fun sessions(ctx: Context): List<WorkSession> {
        val raw = sp(ctx).getString("sessions", "[]") ?: "[]"
        val out = ArrayList<WorkSession>()
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(WorkSession(o.getLong("s"), o.optLong("e", 0L)))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun write(ctx: Context, list: List<WorkSession>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("s", it.start).put("e", it.end)) }
        sp(ctx).edit().putString("sessions", a.toString()).apply()
    }

    fun open(ctx: Context): WorkSession? = sessions(ctx).lastOrNull { it.end == 0L }

    @Synchronized
    fun startSession(ctx: Context, now: Long) {
        val list = sessions(ctx)
        if (list.any { it.end == 0L }) return
        write(ctx, list + WorkSession(now, 0L))
    }

    @Synchronized
    fun closeOpen(ctx: Context, end: Long) {
        val list = sessions(ctx).map { if (it.end == 0L) WorkSession(it.start, maxOf(end, it.start)) else it }
        write(ctx, list)
    }

    fun setStatus(ctx: Context, now: Long, dist: Float?, msg: String) {
        sp(ctx).edit().putLong("st_time", now).putFloat("st_dist", dist ?: -1f).putString("st_msg", msg).apply()
    }

    fun statusTime(ctx: Context): Long = sp(ctx).getLong("st_time", 0L)
    fun statusDist(ctx: Context): Float = sp(ctx).getFloat("st_dist", -1f)
    fun statusMsg(ctx: Context): String = sp(ctx).getString("st_msg", "") ?: ""

    // ------------------------------------------------------------ расчёты

    fun dayStart(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun plusDays(ms: Long, d: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.add(Calendar.DAY_OF_YEAR, d)
        return c.timeInMillis
    }

    fun minuteOfDay(ms: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Сколько миллисекунд записей попадает в промежуток [from, to). */
    fun overlap(list: List<WorkSession>, from: Long, to: Long, now: Long): Long {
        var sum = 0L
        list.forEach {
            val e = if (it.end == 0L) now else it.end
            val a = maxOf(it.start, from)
            val b = minOf(e, to)
            if (b > a) sum += b - a
        }
        return sum
    }

    fun fmt(ms: Long): String {
        val m = ms / 60000L
        if (m <= 0L) return "—"
        val h = m / 60
        val r = m % 60
        return if (h == 0L) "$r мин" else if (r == 0L) "$h ч" else "$h ч $r мин"
    }

    fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    fun minToText(m: Int): String = String.format(Locale.getDefault(), "%02d:%02d", m / 60, m % 60)
}

object WorkTracker {
    fun hasFine(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Сейчас идёт время проверки (нужный день и часы)? */
    fun isActive(c: WorkCfg, now: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val m = WorkStore.minuteOfDay(now)
        return if (c.fromMin <= c.toMin) {
            dow in c.days && m >= c.fromMin && m <= c.toMin
        } else {
            // окно через полночь
            if (m >= c.fromMin) dow in c.days
            else if (m <= c.toMin) {
                val prev = if (dow == Calendar.SUNDAY) Calendar.SATURDAY else dow - 1
                prev in c.days
            } else false
        }
    }

    /** Когда закончилось окно проверки, в котором началась запись [start]. */
    private fun windowEnd(c: WorkCfg, start: Long): Long {
        val ds = WorkStore.dayStart(start)
        val m = WorkStore.minuteOfDay(start)
        val base = ds + c.toMin * 60000L
        return if (c.toMin >= c.fromMin) base
        else if (m <= c.toMin) base
        else WorkStore.plusDays(ds, 1) + c.toMin * 60000L
    }

    fun check(ctx: Context, loc: Location?, now: Long) {
        val c = WorkStore.cfg(ctx)
        if (!c.enabled || !c.hasPoint) return
        var open = WorkStore.open(ctx)
        if (open != null) {
            val cap = windowEnd(c, open.start)
            if (now > cap) {
                WorkStore.closeOpen(ctx, cap)
                open = null
            }
        }
        if (!isActive(c, now)) {
            WorkStore.setStatus(ctx, now, null, "Вне расписания проверки")
            return
        }
        if (loc == null) {
            WorkStore.setStatus(ctx, now, null, "Не удалось определить местоположение")
            return
        }
        val dist = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, c.lat, c.lon, dist)
        val d = dist[0]
        val acc = if (loc.hasAccuracy()) loc.accuracy else 0f
        if (open == null) {
            if (d <= c.radius) {
                WorkStore.startSession(ctx, now)
                WorkStore.setStatus(ctx, now, d, "В рабочей зоне")
            } else {
                WorkStore.setStatus(ctx, now, d, "Вне рабочей зоны")
            }
        } else {
            // выход засчитываем, только когда точно за радиусом (с учётом погрешности GPS)
            if (d - minOf(acc, 150f) > c.radius) {
                WorkStore.closeOpen(ctx, now)
                WorkStore.setStatus(ctx, now, d, "Вне рабочей зоны")
            } else {
                WorkStore.setStatus(ctx, now, d, "В рабочей зоне")
            }
        }
    }

    @Suppress("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!hasFine(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).forEach {
            try {
                val l = lm.getLastKnownLocation(it)
                if (l != null && (best == null || l.time > best!!.time)) best = l
            } catch (e: Exception) {
            }
        }
        return best
    }

    /** Одно определение местоположения: ждём до 30 секунд, берём первую точность до 100 м или лучшую из пойманных. */
    @Suppress("MissingPermission")
    suspend fun fix(ctx: Context): Location? {
        if (!hasFine(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            try { lm.isProviderEnabled(it) } catch (e: Exception) { false }
        }
        if (providers.isEmpty()) return lastKnown(ctx)
        val good = CompletableDeferred<Location?>()
        var best: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val b = best
                val acc = if (l.hasAccuracy()) l.accuracy else 9999f
                val bAcc = if (b != null && b.hasAccuracy()) b.accuracy else 9999f
                if (b == null || acc < bAcc) best = l
                if (acc <= 100f) good.complete(l)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            withContext(Dispatchers.Main) {
                providers.forEach { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
            }
        } catch (e: Exception) {
            return lastKnown(ctx)
        }
        withTimeoutOrNull(30_000L) { good.await() }
        try {
            withContext(Dispatchers.Main) { lm.removeUpdates(listener) }
        } catch (e: Exception) {
        }
        return best ?: lastKnown(ctx)
    }

    fun ensureService(ctx: Context) {
        try {
            val c = WorkStore.cfg(ctx)
            if (!c.enabled || !c.hasPoint || !hasFine(ctx)) return
            ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, WorkService::class.java))
        } catch (e: Exception) {
        }
    }

    fun stopService(ctx: Context) {
        try {
            ctx.applicationContext.stopService(Intent(ctx.applicationContext, WorkService::class.java))
        } catch (e: Exception) {
        }
    }
}

class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Рабочее время", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Рабочее время")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun goForeground(text: String) {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification(text),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground("Учёт времени включён")
        if (job == null) job = scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        while (scope.isActive) {
            val c = WorkStore.cfg(this)
            if (!c.enabled || !c.hasPoint) {
                stopSelf()
                return
            }
            val now = System.currentTimeMillis()
            var wait: Long
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lager:worktime")
            try {
                wl.acquire(60_000L)
                if (WorkTracker.isActive(c, now)) {
                    val loc = try { WorkTracker.fix(this) } catch (e: Exception) { null }
                    WorkTracker.check(this, loc, System.currentTimeMillis())
                    wait = maxOf(1, c.intervalMin) * 60_000L
                } else {
                    WorkTracker.check(this, null, now)
                    wait = 60_000L
                }
            } catch (e: Exception) {
                wait = 60_000L
            } finally {
                try { if (wl.isHeld) wl.release() } catch (e: Exception) { }
            }
            val open = WorkStore.open(this)
            val text = if (open != null) "В рабочей зоне с ${WorkStore.hhmm(open.start)}"
            else WorkStore.statusMsg(this).ifBlank { "Учёт времени включён" }
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification(text))
            } catch (e: Exception) {
            }
            delay(wait)
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "worktime"
        const val NOTIF_ID = 4711
    }
}

/** После перезагрузки телефона и обновления приложения возобновляет учёт. */
class WorkBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WorkTracker.ensureService(context)
    }
}
