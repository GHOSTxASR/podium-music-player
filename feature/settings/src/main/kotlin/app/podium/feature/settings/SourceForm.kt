package app.podium.feature.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalRowPadding
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.sources.api.SetupField
import app.podium.sources.api.SetupForm
import app.podium.sources.api.SetupProblem
import kotlinx.coroutines.launch

private sealed interface FormRow {
    data class Field(val field: SetupField) : FormRow
    data object Submit : FormRow
    data class Note(val text: String) : FormRow
}

/**
 * A source's setup or sign-in form (D-37), rendered from its [SetupForm] without knowing what the
 * source is: one field per row, then the action. Secrets are masked, never kept in saved state and
 * dropped when the screen goes. An unencrypted address on the listener's own network needs a second,
 * explicit "Connect anyway" (D-09).
 */
@Composable
fun SourceFormScreen(settings: OnlineSourceSettings, key: String, onDone: () -> Unit) {
    val form = remember(key) { settings.form(key) }
    if (form == null) {
        val insets = LocalScreenInsets.current
        Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
            MessageState(PodiumSymbol.Error, "This isn't available", "Go back and pick a source again.")
        }
        return
    }
    val values = remember(key) { mutableStateMapOf<String, String>() }
    var problem by remember(key) { mutableStateOf<SetupProblem?>(null) }
    var busy by remember(key) { mutableStateOf(false) }
    var cleartextConfirmed by remember(key) { mutableStateOf(false) }
    val haptics = rememberPodiumHaptics()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requesters = remember(form) { form.fields.associate { it.key to FocusRequester() } }
    DisposableEffect(Unit) {
        onDispose {
            values.clear() // nothing typed outlives the form
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    val needsConsent = problem == SetupProblem.NEEDS_CLEARTEXT_CONSENT
    val rows = buildList {
        form.fields.forEach { add(FormRow.Field(it)) }
        problem?.let { add(FormRow.Note(messageFor(it))) }
        add(FormRow.Submit)
    }
    val submit: () -> Unit = {
        if (!busy) {
            focusManager.clearFocus()
            keyboard?.hide()
            if (needsConsent) cleartextConfirmed = true
            busy = true
            scope.launch {
                val sent = values.toMap() + if (cleartextConfirmed) mapOf(SetupForm.ALLOW_CLEARTEXT to "true") else emptyMap()
                val result = settings.submit(form, sent)
                busy = false
                problem = result
                if (result == null) {
                    haptics.confirm()
                    onDone()
                } else {
                    haptics.reject()
                }
            }
        }
    }
    val focus = rememberFocusListState("source-form:$key")
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            is FormRow.Field -> requesters[row.field.key]?.requestFocus()
            FormRow.Submit -> submit()
            else -> Unit
        }
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = rows,
        state = focus,
        key = {
            when (it) {
                is FormRow.Field -> "field:${it.field.key}"
                FormRow.Submit -> "submit"
                is FormRow.Note -> "note"
            }
        },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        focusable = { it !is FormRow.Note },
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            is FormRow.Field -> {
                val index = form.fields.indexOf(row.field)
                FormField(
                    field = row.field,
                    value = values[row.field.key].orEmpty(),
                    onChange = { values[row.field.key] = it; if (problem != null && !needsConsent) problem = null },
                    focus = requesters.getValue(row.field.key),
                    last = index == form.fields.lastIndex,
                    onNext = { form.fields.getOrNull(index + 1)?.let { requesters[it.key]?.requestFocus() } ?: submit() },
                )
            }
            FormRow.Submit -> MenuRow(
                when {
                    busy -> "Connecting…"
                    needsConsent -> "Connect anyway"
                    else -> form.submitLabel
                },
                focused,
                enabled = !busy,
                showChevron = false,
            )
            is FormRow.Note -> PodiumText(row.text, PodiumTheme.type.caption, PodiumTheme.colors.labelSecondary, modifier = Modifier.padding(horizontal = LocalRowPadding.current, vertical = Spacing.s))
        }
    }
}

/** Every problem explained, with what to do next (copy rules: sentence case, no provider names). */
internal fun messageFor(problem: SetupProblem): String = when (problem) {
    SetupProblem.MISSING_FIELD -> "Fill in every field, then try again."
    SetupProblem.BAD_ADDRESS -> "That address doesn't look right. Check it and try again."
    SetupProblem.INSECURE_ADDRESS -> "That address isn't encrypted. Use https, or a server on your own network."
    SetupProblem.NEEDS_CLEARTEXT_CONSENT -> "This server's connection isn't encrypted. Connect only if it's on your own network."
    SetupProblem.UNREACHABLE -> "Couldn't connect to this music server. Check the address and that the server is running."
    SetupProblem.WRONG_CREDENTIALS -> "The server didn't accept that user name and password."
    SetupProblem.NOT_SUPPORTED -> "This server doesn't offer a music API Podium can use."
    SetupProblem.UNKNOWN -> "Something went wrong on the server. Try again in a moment."
}

/** One field: a hairline box on the paper, the label as its placeholder, secrets masked. */
@Composable
private fun FormField(
    field: SetupField,
    value: String,
    onChange: (String) -> Unit,
    focus: FocusRequester,
    last: Boolean,
    onNext: () -> Unit,
) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val secret = field.kind == SetupField.Kind.SECRET
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalRowPadding.current, vertical = Spacing.s)
            .border(1.dp, colors.labelTertiary, RoundedCornerShape(if (colors.isIndustrial) 2.dp else 10.dp))
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        if (value.isEmpty()) PodiumText(field.label, type.row, colors.labelTertiary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = type.row.copy(color = colors.labelPrimary),
            cursorBrush = SolidColor(colors.labelPrimary),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = when (field.kind) {
                    SetupField.Kind.URL -> KeyboardType.Uri
                    SetupField.Kind.SECRET -> KeyboardType.Password
                    SetupField.Kind.TEXT -> KeyboardType.Ascii
                },
                imeAction = if (last) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onNext() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = field.label },
        )
    }
}
