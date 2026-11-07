package ru.storage.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Поиск артикула на stroeh.de (второй уровень артикула). Запрос уходит только по нажатию кнопки, через ваш скрипт Google:
 * поиск → список товаров → выбор → со страницы товара берётся «Artikelnr.» и показывается на подтверждение.
 */
@Composable
fun StrohSearchDialog(initialQuery: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var hits by remember { mutableStateOf<List<DriveApi.StrohHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    // выбранный товар: (название на сайте, артикул) — ждёт подтверждения
    var found by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun search() {
        if (query.trim().length < 2 || busy) return
        busy = true; msg = ""; found = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { DriveApi.strohSearch(ctx, query.trim()) }
                hits = r
                searched = true
                if (r.isEmpty()) msg = "Ничего не найдено. Попробуйте короче: бренд и название без веса."
            } catch (e: Exception) {
                msg = e.message ?: "Ошибка поиска"
            } finally {
                busy = false
            }
        }
    }

    fun pick(h: DriveApi.StrohHit) {
        if (busy) return
        busy = true; msg = ""
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { DriveApi.strohArticle(ctx, h.url) }
                if (r.second.isBlank()) msg = "На странице товара артикул не найден" else found = (r.first.ifBlank { h.name }) to r.second
            } catch (e: Exception) {
                msg = e.message ?: "Ошибка"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Артикул на stroeh.de") },
        text = {
            val f = found
            if (f != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.first, fontWeight = FontWeight.SemiBold)
                    Text("Артикул: ${f.second}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Проверьте, что вес и название совпадают. Этот номер будет запасным: свой артикул (ввод, скан, накладная) всегда главнее.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Column {
                    OutlinedTextField(
                        query, { query = it }, label = { Text("Название товара") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { search() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Подождите…" else "Искать")
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    if (msg.isNotBlank()) Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    if (searched && hits.isNotEmpty()) {
                        Text("Нажмите на нужный товар:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(hits, key = { it.url }) { h ->
                                Text(h.name, modifier = Modifier.fillMaxWidth().clickable { pick(h) }.padding(vertical = 10.dp))
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val f = found
            if (f != null) TextButton(onClick = { onPick(f.second) }) { Text("Подставить") }
        },
        dismissButton = {
            if (found != null) TextButton(onClick = { found = null }) { Text("Назад") }
            else TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
