package ru.storage.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Настройка «Экран»: не выключать экран в окнах подсчёта (DHL Postwagen, палеты, количество товара).
 * enabled — функция включена; minutes — сколько минут держать экран включённым (потом он гаснет как обычно).
 * Хранится на телефоне и в настройках аккаунта.
 */
object ScreenCfg {
    const val DEFAULT_MINUTES = 10
    const val MAX_MINUTES = 120

    var enabled by mutableStateOf(true)
    var minutes by mutableIntStateOf(DEFAULT_MINUTES)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("screen_cfg", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val s = sp(ctx)
        enabled = s.getBoolean("on", true)
        minutes = s.getInt("min", DEFAULT_MINUTES).coerceIn(1, MAX_MINUTES)
    }

    private fun store(ctx: Context) {
        sp(ctx).edit().putBoolean("on", enabled).putInt("min", minutes).apply()
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        store(ctx)
    }

    fun setMinutes(ctx: Context, m: Int) {
        minutes = m.coerceIn(1, MAX_MINUTES)
        store(ctx)
    }

    /** Применить значения из аккаунта (Firebase). */
    fun applyCloud(ctx: Context, on: Boolean?, min: Number?) {
        if (on != null) enabled = on
        if (min != null) minutes = min.toInt().coerceIn(1, MAX_MINUTES)
        store(ctx)
    }
}

/**
 * Пока окно открыто, экран не гаснет — но не дольше времени из настроек («Экран», по умолчанию 10 минут;
 * потом снова действует обычное время ожидания телефона). Если функция выключена в настройках — ничего не делает.
 * Любое изменение [resetKey] (нажатие на счётчик, ввод) начинает отсчёт заново.
 */
@Composable
fun KeepScreenOn(resetKey: Any? = null) {
    val ctx = LocalContext.current
    val window = ctx.findFragmentActivity()?.window
    val on = ScreenCfg.enabled
    val minutes = ScreenCfg.minutes
    DisposableEffect(window) {
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(window, resetKey, on, minutes) {
        if (!on) {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return@LaunchedEffect
        }
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        delay(minutes * 60_000L)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
