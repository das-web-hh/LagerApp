package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MIN_SEARCH_CHARS = 3

/** Иконка сканера штрих-кодов: уголки рамки и полосы кода. */
@Composable
fun BarcodeScannerIcon(
    size: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.09f
        val m = stroke / 2f
        val c = w * 0.3f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = stroke, cap = StrokeCap.Round)
        // уголки
        line(m, c, m, m); line(m, m, c, m)
        line(w - c, m, w - m, m); line(w - m, m, w - m, c)
        line(m, h - c, m, h - m); line(m, h - m, c, h - m)
        line(w - c, h - m, w - m, h - m); line(w - m, h - m, w - m, h - c)
        // полосы штрих-кода
        val bars = listOf(0.26f to 0.05f, 0.35f to 0.03f, 0.42f to 0.07f, 0.54f to 0.03f, 0.61f to 0.05f, 0.70f to 0.04f)
        bars.forEach { (x, bw) ->
            drawRect(color, Offset(w * x, h * 0.28f), Size(w * bw, h * 0.44f))
        }
    }
}

/** Значок в строке списка: есть ли у товара штрих-код. Нет кода — тот же значок перечёркнут красной линией. */
@Composable
fun BarcodeStatusIcon(
    has: Boolean,
    size: Dp = 20.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    slash: Color = MaterialTheme.colorScheme.error
) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val c = if (has) color else color.copy(alpha = 0.55f)
        val bars = listOf(0.08f to 0.07f, 0.20f to 0.04f, 0.29f to 0.10f, 0.45f to 0.04f, 0.54f to 0.08f, 0.68f to 0.04f, 0.77f to 0.07f, 0.89f to 0.04f)
        bars.forEach { (x, bw) -> drawRect(c, Offset(w * x, h * 0.18f), Size(w * bw, h * 0.64f)) }
        if (!has) {
            drawLine(slash, Offset(w * 0.06f, h * 0.94f), Offset(w * 0.94f, h * 0.06f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        }
    }
}
