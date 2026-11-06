package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Анимация после входа, 3 секунды, поверх экрана:
 * 0–0,55 c синий круг раскрывается из центра и закрывает экран входа (в этот момент вызывается onCovered —
 * под синим уже строится главный экран); с 0,35 c белый логотип «прилетает» с пружинкой;
 * с 1,0 c надпись «печатается», затем появляется приветствие; внизу заполняется полоска;
 * с 2,45 до 3,0 c в синем открывается круглое «окно» и растёт на весь экран — виден главный экран, onFinished.
 */
@Composable
fun WelcomeSplash(onCovered: () -> Unit, onFinished: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val logo = remember { Animatable(0f) }
    val typed = remember { Animatable(0f) }
    val sub = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val title = "Warehouse"
    val coveredCb by rememberUpdatedState(onCovered)
    val finishedCb by rememberUpdatedState(onFinished)

    BackHandler {} // во время анимации «Назад» ничего не делает

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { progress.animateTo(1f, tween(2600, easing = LinearEasing)) }
            launch {
                reveal.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
                coveredCb()
            }
            launch {
                delay(350)
                logo.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 170f))
            }
            launch {
                delay(1000)
                typed.animateTo(1f, tween(850, easing = LinearEasing))
                delay(80)
                sub.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
            }
            launch {
                delay(2450)
                exit.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
            }
        }
        finishedCb()
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // экран закрыт: нажатия не доходят до того, что под анимацией
            awaitPointerEventScope {
                while (true) awaitPointerEvent().changes.forEach { it.consume() }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val maxR = hypot(size.width, size.height)
            drawCircle(BrandBlue, radius = maxR * reveal.value, center = center)
            if (exit.value > 0f) {
                drawCircle(Color.Transparent, radius = maxR * exit.value, center = center, blendMode = BlendMode.Clear)
            }
        }

        Column(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = (1f - exit.value * 1.7f).coerceIn(0f, 1f)
                val s = 1f + exit.value * 0.25f
                scaleX = s
                scaleY = s
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painterResource(R.drawable.app_logo), contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(160.dp).graphicsLayer {
                    val l = logo.value
                    alpha = l.coerceIn(0f, 1f)
                    val s = 0.35f + 0.65f * l
                    scaleX = s
                    scaleY = s
                    translationY = (1f - l.coerceIn(0f, 1f)) * 60.dp.toPx()
                }
            )
            Spacer(Modifier.height(20.dp))
            // «печать»: напечатанные буквы белые, остальные невидимы, но место занимают — надпись не дёргается
            val n = (title.length * typed.value).toInt().coerceIn(0, title.length)
            Text(
                buildAnnotatedString {
                    append(title.substring(0, n))
                    withStyle(SpanStyle(color = Color.Transparent)) { append(title.substring(n)) }
                },
                fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Добро пожаловать",
                fontSize = 17.sp, color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.graphicsLayer {
                    alpha = sub.value
                    translationY = (1f - sub.value) * 12.dp.toPx()
                }
            )
        }

        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
                .graphicsLayer { alpha = (reveal.value * (1f - exit.value * 1.7f)).coerceIn(0f, 1f) }
                .width(170.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.25f))
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(Color.White))
        }
    }
}
