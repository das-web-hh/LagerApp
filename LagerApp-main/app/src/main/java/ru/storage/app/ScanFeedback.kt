package ru.storage.app

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Отклик на скан штрих-кода: короткий «бип» (ошибка — другой звук) и плашка на экране. Вибрация делается отдельно (Haptics). */
object ScanFeedback {
    private var tone: ToneGenerator? = null

    @Synchronized
    fun beep(ok: Boolean = true) {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tone = it }
            t.startTone(if (ok) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, if (ok) 130 else 260)
        } catch (e: Throwable) {
            tone = null
        }
    }
}

/** Плашка вверху экрана: зелёная — отсканировано, красная — не найдено. null — не показывается. */
@Composable
fun ScanBanner(state: Pair<String, Boolean>?) {
    if (state == null) return
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, 140),
        properties = PopupProperties(focusable = false)
    ) {
        Surface(
            color = if (state.second) Color(0xFF2E7D32) else Color(0xFFC62828),
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 8.dp
        ) {
            Text(
                (if (state.second) "✓ " else "✕ ") + state.first,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}
