package com.simon.harmonichackernews.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simon.harmonichackernews.network.CacheServerCheck
import com.simon.harmonichackernews.resources.HarmonicDimens
import com.simon.harmonichackernews.ui.theme.ProductSansFontFamily
import kotlinx.coroutines.launch

data class AiBaseUrlPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
)

@Composable
fun AiSummaryTextDialog(
    title: String,
    hint: String,
    initialValue: String,
    defaultValue: String,
    minLines: Int,
    maxLines: Int,
    textSizeSp: Int,
    trimValue: Boolean,
    allowEmpty: Boolean,
    showReset: Boolean,
    asciiInput: Boolean,
    onSave: suspend (String) -> String?,
    onDismiss: () -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    fun save() {
        if (saving) return
        val savedValue = if (trimValue) value.trim() else value
        if (!allowEmpty && savedValue.isEmpty()) {
            error = "Required"
            return
        }
        saving = true
        scope.launch {
            error = onSave(savedValue)
            saving = false
            if (error == null) onDismiss()
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = { SettingsDialogTitle(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    error = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .then(
                        if (maxLines <= 1) {
                            Modifier.height(
                                HarmonicDimens.compose_settings_dialog_single_line_field_height,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .focusRequester(focusRequester),
                label = { Text(hint) },
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                singleLine = maxLines <= 1,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = ProductSansFontFamily,
                    fontSize = textSizeSp.sp,
                    lineHeight = if (maxLines > 1) (textSizeSp + 3).sp else (textSizeSp + 4).sp,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (asciiInput) KeyboardType.Ascii else KeyboardType.Text,
                    imeAction = if (maxLines <= 1) ImeAction.Done else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
        },
        confirmButton = {
            SettingsDialogTextButton(onClick = { save() }, enabled = !saving) { Text("Save") }
        },
        dismissButton = {
            SettingsDialogTextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
        neutralButton = if (showReset) {
            {
                SettingsDialogTextButton(
                    onClick = {
                        error = null
                        value = defaultValue
                    },
                    enabled = !saving,
                ) {
                    Text("Reset")
                }
            }
        } else {
            null
        },
    )
}

@Composable
fun AiSummaryBaseUrlDialog(
    initialUrl: String,
    presets: List<AiBaseUrlPreset>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    fun save() {
        val savedUrl = url.trim()
        if (savedUrl.isEmpty()) {
            error = "Enter a base URL"
            return
        }
        onSave(savedUrl)
        onDismiss()
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = { SettingsDialogTitle("Base URL") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Presets",
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = ProductSansFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                LazyRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(presets, key = { it.id }) { provider ->
                        val selected = normalizeBaseUrl(provider.baseUrl) == normalizeBaseUrl(url)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                url = provider.baseUrl
                                error = null
                            },
                            label = { Text(provider.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                        .focusRequester(focusRequester),
                    label = { Text("Base URL") },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
            }
        },
        confirmButton = {
            SettingsDialogTextButton(onClick = { save() }) { Text("Save") }
        },
        dismissButton = {
            SettingsDialogTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

/**
 * Base URL entry for the self-hosted translation cache. A new non-empty address is
 * connection-tested before it is saved, so an unreachable server never quietly adds a
 * timeout to every translated thread; a second tap force-saves an address that is only
 * temporarily offline. Clearing the field disables the cache and needs no test.
 */
@Composable
fun TranslationCacheServerDialog(
    initialUrl: String,
    onCheck: suspend (String) -> CacheServerCheck,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initialUrl) { mutableStateOf(initialUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var forceSave by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    fun save() {
        if (checking) return
        val savedValue = value.trim()
        // Empty disables the cache; an unchanged address was tested when it was first saved.
        if (savedValue.isEmpty() || savedValue == initialUrl.trim() || forceSave) {
            onSave(savedValue)
            onDismiss()
            return
        }
        checking = true
        scope.launch {
            when (val outcome = onCheck(savedValue)) {
                is CacheServerCheck.Reachable -> {
                    onSave(savedValue)
                    onDismiss()
                }
                is CacheServerCheck.Unreachable -> {
                    val reason = outcome.reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    error = "Couldn't reach the server$reason. Check that it is running and " +
                        "reachable, or save anyway to enable it as-is."
                    forceSave = true
                }
            }
            checking = false
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = { SettingsDialogTitle("Translation cache server") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Pre-translated threads are fetched from this server. " +
                        "New addresses are connection-tested before they are enabled.",
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = ProductSansFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                        forceSave = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .then(
                            Modifier.height(
                                HarmonicDimens.compose_settings_dialog_single_line_field_height,
                            ),
                        )
                        .focusRequester(focusRequester),
                    label = { Text("Base URL, e.g. http://192.168.1.10:8362") },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
            }
        },
        confirmButton = {
            SettingsDialogTextButton(onClick = { save() }, enabled = !checking) {
                Text(
                    when {
                        checking -> "Testing…"
                        forceSave -> "Save anyway"
                        else -> "Save"
                    },
                )
            }
        },
        dismissButton = {
            SettingsDialogTextButton(onClick = onDismiss, enabled = !checking) {
                Text("Cancel")
            }
        },
    )
}
