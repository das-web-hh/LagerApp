package ru.storage.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** Настройки крупной цифры и голоса (хранятся на телефоне: голоса у каждого устройства свои). */
data class VoiceCfg(
    val voiceOn: Boolean = true,      // озвучивать количество
    val onTap: Boolean = true,        // ...при тапах по строке
    val onPicker: Boolean = true,     // ...при выборе барабаном
    val showBig: Boolean = true,      // крупная цифра на экране
    val delayMs: Int = 1000,          // пауза от последней цифры на экране до голоса
    val rate: Float = 1.0f,           // скорость речи
    val pitch: Float = 1.0f,          // тембр (высота голоса)
    val volume: Float = 1.0f,         // громкость речи
    val voiceName: String = "",       // пусто — голос по умолчанию (русский)
    val holdMs: Int = 1500,           // сколько цифра держится на экране до затухания
    val sizeSp: Int = 120             // размер цифры
) {
    fun save(ctx: Context) {
        sp(ctx).edit()
            .putBoolean("voiceOn", voiceOn).putBoolean("onTap", onTap).putBoolean("onPicker", onPicker)
            .putBoolean("showBig", showBig).putInt("delayMs", delayMs).putFloat("rate", rate)
            .putFloat("pitch", pitch).putFloat("volume", volume).putString("voiceName", voiceName)
            .putInt("holdMs", holdMs).putInt("sizeSp", sizeSp)
            .apply()
    }

    companion object {
        private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("voice", Context.MODE_PRIVATE)

        fun load(ctx: Context): VoiceCfg {
            val s = sp(ctx)
            val d = VoiceCfg()
            return VoiceCfg(
                voiceOn = s.getBoolean("voiceOn", d.voiceOn),
                onTap = s.getBoolean("onTap", d.onTap),
                onPicker = s.getBoolean("onPicker", d.onPicker),
                showBig = s.getBoolean("showBig", d.showBig),
                delayMs = s.getInt("delayMs", d.delayMs),
                rate = s.getFloat("rate", d.rate),
                pitch = s.getFloat("pitch", d.pitch),
                volume = s.getFloat("volume", d.volume),
                voiceName = s.getString("voiceName", d.voiceName) ?: "",
                holdMs = s.getInt("holdMs", d.holdMs),
                sizeSp = s.getInt("sizeSp", d.sizeSp)
            )
        }
    }
}

/** Голос: системный синтезатор речи (TextToSpeech). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var queued: String? = null
    private var queuedCfg: VoiceCfg? = null

    /** true, когда синтезатор готов (после этого доступен список голосов). */
    var ready by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                } catch (e: Exception) {
                }
                ready = true
                val q = queued
                val c = queuedCfg
                queued = null
                queuedCfg = null
                if (q != null && c != null) speakNow(q, c)
            }
        }
    }

    fun say(ctx: Context, text: String, cfg: VoiceCfg) {
        init(ctx)
        if (ready) speakNow(text, cfg) else {
            queued = text
            queuedCfg = cfg
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
    }

    /** Русские голоса, установленные на телефоне. */
    fun voices(): List<Voice> = try {
        (tts?.voices ?: emptySet<Voice>()).filter { it.locale.language == "ru" }.sortedBy { it.name }
    } catch (e: Exception) {
        emptyList()
    }

    fun label(v: Voice): String {
        val place = v.locale.displayCountry.ifBlank { v.locale.displayLanguage }
        return v.name + " · " + place + if (v.isNetworkConnectionRequired) " · нужен интернет" else ""
    }

    private fun speakNow(text: String, cfg: VoiceCfg) {
        val t = tts ?: return
        try {
            t.setSpeechRate(cfg.rate.coerceIn(0.3f, 3f))
            t.setPitch(cfg.pitch.coerceIn(0.3f, 3f))
            val v = if (cfg.voiceName.isNotEmpty()) voices().firstOrNull { it.name == cfg.voiceName } else null
            if (v != null) t.setVoice(v) else t.setLanguage(Locale("ru", "RU"))
            val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, cfg.volume.coerceIn(0f, 1f)) }
            t.speak(text, TextToSpeech.QUEUE_FLUSH, b, "count")
        } catch (e: Exception) {
        }
    }
}

class CountEvent(val id: Long, val text: String, val cfg: VoiceCfg, val time: Long)

/**
 * Крупная цифра и голос после подсчёта.
 *  - тап по строке: на экране сразу 1, 2, 3 … (счёт тапов подряд по одной строке);
 *  - выбор барабаном: на экране выбранное количество;
 *  - голос произносит последнее число через паузу (по умолчанию 1 с) после того, как оно появилось на экране;
 *    новый тап в эту паузу сбрасывает ожидание, счёт продолжается.
 */
object CountAnnouncer {
    var event by mutableStateOf<CountEvent?>(null)
        private set
    private var seq = 0L
    private var series = 0
    private var seriesKey: Any? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun tap(ctx: Context, key: Any) {
        if (seriesKey !== key) {
            series = 0
            seriesKey = key
        }
        series++
        announce(ctx, series.toString(), series.toString(), tap = true)
    }

    fun picked(ctx: Context, text: String, speech: String) {
        series = 0
        seriesKey = null
        announce(ctx, text, speech, tap = false)
    }

    private fun announce(ctx: Context, text: String, speech: String, tap: Boolean) {
        val app = ctx.applicationContext
        val cfg = VoiceCfg.load(app)
        if (cfg.showBig) event = CountEvent(++seq, text, cfg, System.currentTimeMillis())
        job?.cancel()
        Speaker.stop()
        job = scope.launch {
            delay(cfg.delayMs.toLong().coerceAtLeast(0L))
            series = 0
            seriesKey = null
            if (cfg.voiceOn && (if (tap) cfg.onTap else cfg.onPicker)) Speaker.say(app, speech, cfg)
        }
    }
}

/** Крупная цифра поверх списка: плавно появляется и плавно гаснет, касания пропускает. */
@Composable
fun CountOverlay() {
    val ev = CountAnnouncer.event
    val alpha = remember { Animatable(0f) }
    var text by remember { mutableStateOf("") }
    var size by remember { mutableIntStateOf(120) }

    LaunchedEffect(ev?.id) {
        val e = ev ?: return@LaunchedEffect
        // старое событие (экран открыли заново) не показываем
        if (System.currentTimeMillis() - e.time > 600) return@LaunchedEffect
        text = e.text
        size = e.cfg.sizeSp
        alpha.animateTo(1f, tween(110))
        delay(e.cfg.holdMs.toLong())
        alpha.animateTo(0f, tween(500))
    }

    if (alpha.value > 0.01f) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .graphicsLayer {
                        this.alpha = alpha.value
                        val s = 0.85f + 0.15f * alpha.value
                        scaleX = s
                        scaleY = s
                    }
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(32.dp))
                    .padding(horizontal = 36.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text,
                    fontSize = (if (text.length > 5) size * 0.55f else size.toFloat()).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** Настройки → «Голос и подсчёт». */
@Composable
fun VoiceSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    var cfg by remember { mutableStateOf(VoiceCfg.load(ctx)) }
    fun upd(n: VoiceCfg) {
        cfg = n
        n.save(ctx)
    }
    LaunchedEffect(Unit) { Speaker.init(ctx) }
    var pickVoice by remember { mutableStateOf(false) }
    val voices = if (Speaker.ready) Speaker.voices() else emptyList()
    val voiceTitle = voices.firstOrNull { it.name == cfg.voiceName }?.let { Speaker.label(it) } ?: "По умолчанию (русский)"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Голос и подсчёт", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        Text("Голос", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Озвучивать количество", "Назвать последнее число голосом", cfg.voiceOn) { upd(cfg.copy(voiceOn = it)) }
        SwitchRow("Голос при тапах", "Тапнули 10 раз — скажет «10»", cfg.onTap) { upd(cfg.copy(onTap = it)) }
        SwitchRow("Голос при выборе барабаном", "Выбрали 15 — скажет «15»", cfg.onPicker) { upd(cfg.copy(onPicker = it)) }
        SliderRow(
            "Пауза перед голосом", String.format(Locale.ROOT, "%.1f с", cfg.delayMs / 1000f),
            cfg.delayMs / 1000f, 0f..3f
        ) { upd(cfg.copy(delayMs = ((it * 10).roundToInt() * 100))) }
        Text(
            "Считается от момента, когда последняя цифра появилась на экране. Новый тап в эту паузу продолжает счёт.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clickable { pickVoice = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Выбор голоса", style = MaterialTheme.typography.titleSmall)
                Text(voiceTitle, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
        SliderRow("Скорость речи", String.format(Locale.ROOT, "×%.2f", cfg.rate), cfg.rate, 0.5f..2f) {
            upd(cfg.copy(rate = (it * 20).roundToInt() / 20f))
        }
        SliderRow("Тембр (высота голоса)", String.format(Locale.ROOT, "×%.2f", cfg.pitch), cfg.pitch, 0.5f..2f) {
            upd(cfg.copy(pitch = (it * 20).roundToInt() / 20f))
        }
        SliderRow("Громкость голоса", "${(cfg.volume * 100).roundToInt()}%", cfg.volume, 0f..1f) {
            upd(cfg.copy(volume = (it * 20).roundToInt() / 20f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Speaker.say(ctx, "15", cfg) }, modifier = Modifier.weight(1f)) { Text("Проверить голос") }
            OutlinedButton(
                onClick = {
                    try {
                        ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Голоса системы", maxLines = 1) }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Цифра на экране", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Крупная цифра", "Плавно появляется и гаснет при каждом тапе и выборе", cfg.showBig) { upd(cfg.copy(showBig = it)) }
        SliderRow("Размер цифры", "${cfg.sizeSp} sp", cfg.sizeSp.toFloat(), 60f..220f) { upd(cfg.copy(sizeSp = it.roundToInt())) }
        SliderRow(
            "Сколько держится на экране", String.format(Locale.ROOT, "%.1f с", cfg.holdMs / 1000f),
            cfg.holdMs / 1000f, 0.3f..5f
        ) { upd(cfg.copy(holdMs = ((it * 10).roundToInt() * 100))) }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { upd(VoiceCfg()) }, modifier = Modifier.fillMaxWidth()) { Text("Сбросить по умолчанию") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Настройки голоса хранятся на этом телефоне. Если русского голоса нет в списке — установите его в «Голоса системы».",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (pickVoice) {
        AlertDialog(
            onDismissRequest = { pickVoice = false },
            title = { Text("Голос") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier.fillMaxWidth().clickable { upd(cfg.copy(voiceName = "")); pickVoice = false },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = cfg.voiceName.isEmpty(), onClick = { upd(cfg.copy(voiceName = "")); pickVoice = false })
                        Text("По умолчанию (русский)")
                    }
                    voices.forEach { v ->
                        Row(
                            Modifier.fillMaxWidth().clickable { upd(cfg.copy(voiceName = v.name)); pickVoice = false },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = cfg.voiceName == v.name, onClick = { upd(cfg.copy(voiceName = v.name)); pickVoice = false })
                            Text(Speaker.label(v), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (voices.isEmpty()) {
                        Text(
                            if (Speaker.ready) "Русских голосов не найдено. Установите голос в «Голоса системы»."
                            else "Синтезатор речи загружается…",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickVoice = false }) { Text("Закрыть") } }
        )
    }
}
