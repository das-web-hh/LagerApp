package ru.storage.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Настройки вибрации (хранятся на телефоне). */
data class VibCfg(
    val on: Boolean = true,        // вибрация вообще
    val onTap: Boolean = true,     // при тапе по строке
    val onPicker: Boolean = true,  // при прокрутке барабана количества (каждое число)
    val onList: Boolean = true,    // при прокрутке списка (каждая строка)
    val strength: Int = 60,        // сила, % (1…100)
    val durationMs: Int = 20       // длительность одного импульса
) {
    fun save(ctx: Context) {
        sp(ctx).edit()
            .putBoolean("on", on).putBoolean("onTap", onTap).putBoolean("onPicker", onPicker)
            .putBoolean("onList", onList).putInt("strength", strength).putInt("durationMs", durationMs)
            .apply()
    }

    companion object {
        private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("vibration", Context.MODE_PRIVATE)

        fun load(ctx: Context): VibCfg {
            val s = sp(ctx)
            val d = VibCfg()
            return VibCfg(
                on = s.getBoolean("on", d.on),
                onTap = s.getBoolean("onTap", d.onTap),
                onPicker = s.getBoolean("onPicker", d.onPicker),
                onList = s.getBoolean("onList", d.onList),
                strength = s.getInt("strength", d.strength),
                durationMs = s.getInt("durationMs", d.durationMs)
            )
        }
    }
}

object Haptics {
    const val TAP = 0
    const val PICKER = 1
    const val LIST = 2

    @Volatile private var cached: VibCfg? = null
    private var vib: Vibrator? = null

    /** Вызывать после изменения настроек. */
    fun reload(ctx: Context) {
        cached = VibCfg.load(ctx)
    }

    private fun vibrator(ctx: Context): Vibrator? {
        vib?.let { return it }
        val app = ctx.applicationContext
        val v = try {
            if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
        vib = v
        return v
    }

    /** Один импульс по настройкам. kind — когда вызвано (тап, барабан, список). */
    fun pulse(ctx: Context, kind: Int) {
        val cfg = cached ?: VibCfg.load(ctx).also { cached = it }
        if (!cfg.on) return
        val allowed = when (kind) {
            TAP -> cfg.onTap
            PICKER -> cfg.onPicker
            else -> cfg.onList
        }
        if (allowed) fire(ctx, cfg)
    }

    /** Проверка из настроек: играет импульс независимо от переключателей по событиям. */
    fun test(ctx: Context, cfg: VibCfg) = fire(ctx, cfg.copy(on = true))

    private fun fire(ctx: Context, cfg: VibCfg) {
        val v = vibrator(ctx) ?: return
        try {
            if (!v.hasVibrator()) return
            val ms = cfg.durationMs.coerceIn(1, 500).toLong()
            val amp = (cfg.strength.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)
            val effect = if (v.hasAmplitudeControl()) VibrationEffect.createOneShot(ms, amp)
            else VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            v.vibrate(effect)
        } catch (e: Exception) {
        }
    }
}

@Composable
private fun VibSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Настройки → «Вибрация». */
@Composable
fun VibrationSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    var cfg by remember { mutableStateOf(VibCfg.load(ctx)) }
    fun upd(n: VibCfg) {
        cfg = n
        n.save(ctx)
        Haptics.reload(ctx)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Вибрация", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        VibSwitch("Вибрация", "Общий выключатель", cfg.on) { upd(cfg.copy(on = it)) }
        VibSwitch("При тапе", "Каждый тап по строке", cfg.onTap) { upd(cfg.copy(onTap = it)) }
        VibSwitch("При прокрутке барабана", "Каждое число при выборе количества (было 1 — стало 2)", cfg.onPicker) {
            upd(cfg.copy(onPicker = it))
        }
        VibSwitch("При прокрутке списка", "Каждая строка, проходящая через середину колеса", cfg.onList) {
            upd(cfg.copy(onList = it))
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Text("Сила", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${cfg.strength}%", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = cfg.strength.toFloat(), valueRange = 5f..100f,
            onValueChange = { upd(cfg.copy(strength = (it / 5).roundToInt() * 5)) }
        )
        Row {
            Text("Длительность", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${cfg.durationMs} мс", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = cfg.durationMs.toFloat(), valueRange = 5f..200f,
            onValueChange = { upd(cfg.copy(durationMs = (it / 5).roundToInt().coerceAtLeast(1) * 5)) }
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { Haptics.test(ctx, cfg) }, modifier = Modifier.fillMaxWidth()) { Text("Проверить вибрацию") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { upd(VibCfg()) }, modifier = Modifier.fillMaxWidth()) { Text("Сбросить по умолчанию") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Сила работает, если мотор телефона поддерживает регулировку; иначе действует только длительность. " +
                "Настройки хранятся на этом телефоне. Если вибрации нет совсем — проверьте, что она не отключена в системных настройках звука.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
