package ru.storage.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val providerKeys = listOf("claude", "gemini", "openai")
private val providerLabels = providerKeys.map { AiKeys.providerName(it) }

/** Окно добавления / правки API-ключа. Ключ сохраняется в настройках (Профиль → Настройки → Распознавание документов). */
@Composable
fun AiKeyDialog(
    initial: AiKey?,
    title: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onSave: (AiKey) -> Unit
) {
    var provider by remember { mutableStateOf(initial?.provider ?: "claude") }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var key by remember { mutableStateOf(initial?.key ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var base by remember { mutableStateOf(initial?.base ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Провайдер ИИ", style = MaterialTheme.typography.titleSmall)
                DropdownField(AiKeys.providerName(provider), providerLabels) { l ->
                    provider = providerKeys[providerLabels.indexOf(l).coerceAtLeast(0)]
                }
                OutlinedTextField(
                    key, { key = it }, label = { Text("API-ключ") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    label, { label = it }, label = { Text("Название (необязательно)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    model, { model = it }, singleLine = true,
                    label = { Text("Модель (по умолчанию: ${AiCfg.defaultModel(provider)})") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (provider == "openai") {
                    OutlinedTextField(
                        base, { base = it }, singleLine = true, label = { Text("Адрес API (…/v1)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = key.isNotBlank(),
                onClick = {
                    onSave(
                        AiKey(
                            initial?.id ?: AiKeys.newId(), label.trim(), provider, key.trim(),
                            model.trim(), if (provider == "openai") base.trim() else ""
                        )
                    )
                }
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/**
 * «Повторить» после ошибки распознавания: выбрать другой сохранённый ключ или добавить новый
 * (новый ключ попадает в настройки). Выбранный ключ становится активным, затем запускается повтор.
 */
@Composable
fun RetryKeyDialog(onDismiss: () -> Unit, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val keys = remember(tick) { AiKeys.list(ctx) }
    var chosen by remember { mutableStateOf(AiKeys.activeId(ctx)) }
    var adding by remember { mutableStateOf(keys.isEmpty()) }

    if (adding) {
        AiKeyDialog(
            initial = null,
            title = "Новый API-ключ",
            confirmText = "Добавить и повторить",
            onDismiss = { if (keys.isEmpty()) onDismiss() else adding = false },
            onSave = { k ->
                AiKeys.add(ctx, k, makeActive = true)
                onRetry()
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Повторить через ключ") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                keys.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = k.id },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = chosen == k.id, onClick = { chosen = k.id })
                        Column(Modifier.weight(1f)) {
                            Text(AiKeys.describe(k), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                AiKeys.providerName(k.provider) + " · " + k.model.ifBlank { AiCfg.defaultModel(k.provider) },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("＋ Добавить ключ")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = chosen.isNotEmpty(),
                onClick = {
                    AiKeys.setActive(ctx, chosen)
                    tick++
                    onRetry()
                }
            ) { Text("Повторить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
