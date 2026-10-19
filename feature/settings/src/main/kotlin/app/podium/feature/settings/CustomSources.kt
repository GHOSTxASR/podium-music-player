package app.podium.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.KeyboardAction
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalMiniature
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.PodiumTextField
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Custom sources (D-91) as Settings shows them: sources the listener added for their streams. Songs
 * still come from the online service; when one of these has the exact same recording, it plays from
 * there, in the order listed, and otherwise as usual. Everything here is generic: a name, where it is
 * (the host alone), on or off, the settings the source declares, and how it's doing.
 */
data class CustomSourceState(
    val id: String,
    val name: String,
    /** Where it is, as the listener may see it: the host alone (the rest can carry a token). */
    val host: String,
    val enabled: Boolean,
    val status: CustomSourceStatus,
    val options: List<CustomSourceOption>,
)

sealed interface CustomSourceStatus {
    data object Ready : CustomSourceStatus
    data object Checking : CustomSourceStatus

    /** What's wrong, in a sentence, with what happens meanwhile or what to do. */
    data class Problem(val message: String) : CustomSourceStatus
}

/** One setting a source declares (its quality, say) at the listener's value. */
data class CustomSourceOption(
    val key: String,
    val label: String,
    val kind: Kind,
    val value: String,
    /** What the listener sees for [value]. */
    val valueLabel: String,
    val choices: List<Choice> = emptyList(),
) {
    enum class Kind { CHOICE, TOGGLE, TEXT }

    data class Choice(val label: String, val value: String)
}

sealed interface AddSourceResult {
    data class Added(val id: String, val name: String) : AddSourceResult

    /** An unencrypted server on the listener's own network: added once they agree (D-09). */
    data object NeedsLocalConsent : AddSourceResult

    data class Problem(val message: String) : AddSourceResult
}

interface CustomSourcesSettings {
    /** In the listener's order: the first that has a song plays it. */
    val sources: StateFlow<List<CustomSourceState>>

    /** Adds the source at [address] (as typed or pasted) after asking it who it is. */
    suspend fun add(address: String, allowLocalUnencrypted: Boolean = false): AddSourceResult
    fun setEnabled(id: String, enabled: Boolean)

    /** Moves the source [offset] places (negative: earlier, so it's asked sooner). */
    fun move(id: String, offset: Int)
    fun setOption(id: String, key: String, value: String)

    /** Asks the source again whether it's there. */
    fun check(id: String)
    fun remove(id: String)
}

/** What the Settings row says. */
fun customSourcesSummary(sources: List<CustomSourceState>): String = when {
    sources.isEmpty() -> "None"
    sources.none { it.enabled } -> "Off"
    sources.size == 1 -> sources.single().name
    else -> "${sources.count { it.enabled }} of ${sources.size} on"
}

/** What a source's row says about it. */
fun customSourceSummary(source: CustomSourceState): String = when {
    !source.enabled -> "Off"
    else -> when (source.status) {
        CustomSourceStatus.Ready -> "On"
        CustomSourceStatus.Checking -> "Checking"
        is CustomSourceStatus.Problem -> "Not answering"
    }
}

private const val ADD_ROW = "\u0000add"
private const val ABOUT_ROW = "\u0000about"

/**
 * Settings ▸ Custom sources: the listener's sources in order, then "Add a source". Center opens a
 * source's settings.
 */
@Composable
fun CustomSourcesScreen(settings: CustomSourcesSettings, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val sources by settings.sources.collectAsStateWithLifecycle()
    val haptics = rememberPodiumHaptics()
    val rows = sources.map { it.id } + ADD_ROW + ABOUT_ROW
    val focus = rememberFocusListState("custom-sources")
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            null, ABOUT_ROW -> haptics.reject()
            ADD_ROW -> onAdd()
            else -> onOpen(row)
        }
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = rows,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            ADD_ROW -> MenuRow("Add a source", focused)
            ABOUT_ROW -> MenuRow(
                if (sources.isEmpty()) {
                    "For your own streams, like lossless audio. A song plays from one when it has the exact same recording."
                } else {
                    "A song plays from the first that has the exact same recording, and otherwise as usual."
                },
                focused,
                enabled = false,
                showChevron = false,
            )
            else -> {
                val source = sources.firstOrNull { it.id == row } ?: return@FocusList
                MenuRow(source.name, focused, value = customSourceSummary(source))
            }
        }
    }
}

private enum class SourceRow { Enabled, Option, MoveUp, MoveDown, Check, Status, Address, Remove }

private data class SourceLine(val row: SourceRow, val option: CustomSourceOption? = null) {
    val key: String get() = if (option != null) "Option:${option.key}" else row.name
}

/**
 * Settings ▸ Custom sources ▸ a source: on or off, the settings it declares, its place in the
 * order, a check, where it is and how it's doing, and Remove (confirmed).
 */
@Composable
fun CustomSourceScreen(settings: CustomSourcesSettings, id: String, onEditText: (String) -> Unit, onRemoved: () -> Unit) {
    val sources by settings.sources.collectAsStateWithLifecycle()
    val haptics = rememberPodiumHaptics()
    val overlay = LocalOverlayHost.current
    val index = sources.indexOfFirst { it.id == id }
    val source = sources.getOrNull(index)
    // Removed: back to the list (never from a miniature of this screen, which acts on nothing).
    val miniature = LocalMiniature.current
    LaunchedEffect(source == null) { if (source == null && !miniature) onRemoved() }
    if (source == null) return
    val lines = buildList {
        add(SourceLine(SourceRow.Enabled))
        source.options.forEach { add(SourceLine(SourceRow.Option, it)) }
        if (sources.size > 1) {
            add(SourceLine(SourceRow.MoveUp))
            add(SourceLine(SourceRow.MoveDown))
        }
        add(SourceLine(SourceRow.Check))
        if (source.status is CustomSourceStatus.Problem) add(SourceLine(SourceRow.Status))
        add(SourceLine(SourceRow.Address))
        add(SourceLine(SourceRow.Remove))
    }
    val activate: (Int) -> Unit = { i ->
        val line = lines.getOrNull(i)
        when (line?.row) {
            SourceRow.Enabled -> {
                haptics.confirm()
                settings.setEnabled(id, !source.enabled)
            }
            SourceRow.Option -> {
                val option = line.option!!
                when (option.kind) {
                    CustomSourceOption.Kind.TOGGLE -> {
                        haptics.confirm()
                        settings.setOption(id, option.key, if (option.value == "true") "false" else "true")
                    }
                    CustomSourceOption.Kind.CHOICE -> overlay.show(
                        MenuSpec(
                            option.label,
                            option.choices.map { c -> MenuAction(c.label) { settings.setOption(id, option.key, c.value) } },
                        ),
                    )
                    CustomSourceOption.Kind.TEXT -> onEditText(option.key)
                }
            }
            SourceRow.MoveUp -> if (index == 0) haptics.reject() else {
                haptics.confirm()
                settings.move(id, -1)
            }
            SourceRow.MoveDown -> if (index == sources.lastIndex) haptics.reject() else {
                haptics.confirm()
                settings.move(id, 1)
            }
            SourceRow.Check -> if (source.status == CustomSourceStatus.Checking) haptics.reject() else {
                haptics.confirm()
                settings.check(id)
            }
            SourceRow.Remove -> overlay.show(
                MenuSpec(
                    "Remove ${source.name}?",
                    listOf(
                        MenuAction("Remove") { settings.remove(id) },
                        MenuAction("Keep it") {},
                    ),
                ),
            )
            SourceRow.Status, SourceRow.Address, null -> haptics.reject()
        }
    }
    val focus = rememberFocusListState("custom-source:$id")
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = lines,
        state = focus,
        key = { it.key },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { line, _, focused ->
        when (line.row) {
            SourceRow.Enabled -> MenuRow("Use this source", focused, value = if (source.enabled) "On" else "Off", showChevron = false)
            SourceRow.Option -> {
                val option = line.option!!
                MenuRow(option.label, focused, value = option.valueLabel, showChevron = option.kind != CustomSourceOption.Kind.TOGGLE)
            }
            SourceRow.MoveUp -> MenuRow("Move up", focused, value = "Asked ${ordinal(index + 1)}", enabled = index > 0, showChevron = false)
            SourceRow.MoveDown -> MenuRow("Move down", focused, enabled = index < sources.lastIndex, showChevron = false)
            SourceRow.Check -> MenuRow(
                "Check now",
                focused,
                value = when (source.status) {
                    CustomSourceStatus.Checking -> "Checking"
                    CustomSourceStatus.Ready -> "Answering"
                    is CustomSourceStatus.Problem -> null
                },
                showChevron = false,
            )
            SourceRow.Status -> MenuRow((source.status as? CustomSourceStatus.Problem)?.message.orEmpty(), focused, enabled = false, showChevron = false)
            SourceRow.Address -> MenuRow("Address", focused, value = source.host, enabled = false, showChevron = false)
            SourceRow.Remove -> MenuRow("Remove", focused, showChevron = false)
        }
    }
}

/** "1st", "2nd", "3rd", "4th" … for the place a source is asked in. */
internal fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

/**
 * Settings ▸ Custom sources ▸ Add a source: type or paste the source's https address, as the source
 * gives it (with or without /manifest.json). Center, Done or the button adds it; Podium asks the
 * source who it is first.
 */
@Composable
fun AddCustomSourceScreen(settings: CustomSourcesSettings, onAdded: (String) -> Unit) {
    val overlay = LocalOverlayHost.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun submit(address: String, allowLocal: Boolean = false) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            val result = settings.add(address, allowLocal)
            busy = false
            when (result) {
                is AddSourceResult.Added -> onAdded(result.id)
                AddSourceResult.NeedsLocalConsent -> overlay.show(
                    MenuSpec(
                        "This server is on your network and doesn't use encryption. Connect anyway?",
                        listOf(
                            MenuAction("Connect") { submit(address, allowLocal = true) },
                            MenuAction("Cancel") {},
                        ),
                    ),
                )
                is AddSourceResult.Problem -> problem = result.message
            }
        }
    }
    SourceTextEntry(
        initial = "",
        description = "Source address",
        placeholder = "https://",
        hint = when {
            busy -> "Asking the source who it is…"
            problem != null -> problem!!
            else -> "Type or paste the source's address, like https://addon.example.com."
        },
        hintIsProblem = problem != null && !busy,
        actionLabel = if (busy) "Adding…" else "Add source",
        offerPaste = true,
        enabled = !busy,
        url = true,
        onSubmit = { submit(it) },
    )
}

/** Settings ▸ Custom sources ▸ a source ▸ a text setting it declares. */
@Composable
fun CustomSourceTextOptionScreen(settings: CustomSourcesSettings, id: String, key: String, onDone: () -> Unit) {
    val sources by settings.sources.collectAsStateWithLifecycle()
    val option = sources.firstOrNull { it.id == id }?.options?.firstOrNull { it.key == key }
    val miniature = LocalMiniature.current
    LaunchedEffect(option == null) { if (option == null && !miniature) onDone() }
    if (option == null) return
    SourceTextEntry(
        initial = option.value,
        description = option.label,
        placeholder = "",
        hint = "Sent to the source with every request.",
        hintIsProblem = false,
        actionLabel = "Save",
        offerPaste = true,
        enabled = true,
        url = false,
        onSubmit = { value ->
            settings.setOption(id, key, value.trim())
            onDone()
        },
    )
}

/**
 * One line of text, typed with Podium's keyboard or the phone's (D-45), a hint below it, and the
 * buttons. Center does the main action.
 */
@Composable
private fun SourceTextEntry(
    initial: String,
    description: String,
    placeholder: String,
    hint: String,
    hintIsProblem: Boolean,
    actionLabel: String,
    offerPaste: Boolean,
    enabled: Boolean,
    url: Boolean,
    onSubmit: (String) -> Unit,
) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val insets = LocalScreenInsets.current
    val haptics = rememberPodiumHaptics()
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var text by remember { mutableStateOf(initial) }
    val submit = {
        if (!enabled || (url && text.isBlank())) {
            haptics.reject()
        } else {
            haptics.confirm()
            focusManager.clearFocus()
            onSubmit(text)
        }
    }
    val paste: () -> Unit = {
        scope.launch {
            val pasted = runCatching { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (pasted.isNullOrBlank()) haptics.reject() else {
                text = pasted.trim().take(MAX_TEXT)
                haptics.confirm()
            }
        }
    }
    val miniature = LocalMiniature.current
    if (!miniature) LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    InputTargetEffect(WheelContext.VOLUME) { input ->
        when {
            input is PodiumInput.Press && input.button == WheelButton.CENTER -> {
                submit()
                true
            }
            else -> false
        }
    }
    Column(Modifier.fillMaxSize().padding(top = insets.top + Spacing.s, bottom = insets.bottom).padding(horizontal = Spacing.xl)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(colors.canvasRaised, RoundedCornerShape(14.dp))
                .border(1.dp, if (hintIsProblem) colors.critical else colors.separator, RoundedCornerShape(14.dp))
                .padding(horizontal = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty() && placeholder.isNotEmpty()) PodiumText(placeholder, type.body, colors.labelTertiary, maxLines = 1)
                PodiumTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_TEXT) },
                    textStyle = type.body.copy(color = colors.labelPrimary),
                    description = description,
                    focusRequester = focusRequester,
                    action = KeyboardAction.DONE,
                    onAction = submit,
                    maxLength = MAX_TEXT,
                    accept = { !it.isISOControl() },
                    phoneOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = if (url) KeyboardType.Uri else KeyboardType.Text,
                    ),
                    cursorColor = colors.highlightText,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(Spacing.m))
        PodiumText(hint, type.footnote, if (hintIsProblem) colors.critical else colors.labelSecondary, maxLines = 3)
        Spacer(Modifier.height(Spacing.l))
        Row(Modifier.fillMaxWidth()) {
            if (offerPaste) {
                EntryButton("Paste", primary = false, modifier = Modifier.weight(1f), onClick = paste)
                Spacer(Modifier.width(Spacing.m))
            }
            EntryButton(actionLabel, primary = true, modifier = Modifier.weight(if (offerPaste) 2f else 1f), onClick = submit)
        }
    }
}

@Composable
private fun EntryButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    Box(
        modifier
            .height(48.dp)
            .background(if (primary) colors.highlight else colors.canvasRaised, RoundedCornerShape(14.dp))
            .then(if (primary) Modifier else Modifier.border(1.dp, colors.separator, RoundedCornerShape(14.dp)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PodiumText(label, type.rowFocused, if (primary) colors.onHighlight else colors.labelPrimary)
    }
}

private const val MAX_TEXT = 2_000
