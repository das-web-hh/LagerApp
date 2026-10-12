package ru.storage.app

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Пока окно открыто, экран не гаснет — но не дольше [minutes] минут (потом снова действует обычное время ожидания телефона).
 * Любое изменение [resetKey] (нажатие на счётчик, ввод) начинает отсчёт заново.
 */
@Composable
fun KeepScreenOn(minutes: Int = 20, resetKey: Any? = null) {
    val ctx = LocalContext.current
    val window = ctx.findFragmentActivity()?.window
    DisposableEffect(window) {
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(window, resetKey) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        delay(minutes * 60_000L)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
