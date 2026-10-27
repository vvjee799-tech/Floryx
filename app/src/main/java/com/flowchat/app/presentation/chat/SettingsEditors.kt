package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.presentation.common.FlowSettingsPage
import com.flowchat.app.presentation.common.FlowDialogSystemBars
import com.flowchat.app.presentation.common.flowFieldColors

@Composable
internal fun NameEditorDialog(title: String, initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val focus = remember { FocusRequester() }
    val valid = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { FlowDialogSystemBars(); Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true,
                label = { Text(title) }, colors = flowFieldColors(), shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("name_editor"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onSave(name.trim()) }))
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = valid, modifier = Modifier.testTag("name_save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
internal fun OutputLimitDialog(initialLimit: Int?, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var text by rememberSaveable(initialLimit) { mutableStateOf(initialLimit?.toString().orEmpty()) }
    val valid = text.isBlank() || (text.toIntOrNull()?.let { it > 0 } == true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { FlowDialogSystemBars(); Text(stringResource(R.string.max_tokens)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true,
                    label = { Text(stringResource(R.string.max_tokens)) }, colors = flowFieldColors(),
                    shape = MaterialTheme.shapes.medium, isError = !valid,
                    supportingText = { Text(stringResource(if (valid) R.string.output_limit_hint else R.string.output_limit_invalid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onSave(text.toIntOrNull()) }),
                    modifier = Modifier.fillMaxWidth().testTag("output_limit_editor"))
                TextButton(onClick = { text = "" }) { Text(stringResource(R.string.use_default_value)) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.toIntOrNull()) }, enabled = valid) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
internal fun SystemPromptEditor(prompt: String, onChange: (String) -> Unit, onBack: () -> Unit) {
    FlowSettingsPage(title = stringResource(R.string.system_prompt), onBack = onBack,
        actions = { TextButton(onClick = onBack) { Text(stringResource(R.string.done)) } }) {
        OutlinedTextField(prompt, onChange,
            label = { Text(stringResource(R.string.system_prompt)) },
            placeholder = { Text(stringResource(R.string.system_prompt_placeholder)) },
            colors = flowFieldColors(), shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp).testTag("system_prompt_editor"))
    }
}

@Composable
internal fun UnsavedSettingsDialog(onSave: () -> Unit, onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { FlowDialogSystemBars(); Text(stringResource(R.string.unsaved_settings_title)) },
        text = { Text(stringResource(R.string.unsaved_settings_message)) },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_and_return)) }
                TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.discard_changes)) }
                TextButton(onClick = onKeepEditing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.keep_editing)) }
            }
        }
    )
}
