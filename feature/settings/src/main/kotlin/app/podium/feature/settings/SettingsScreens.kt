package app.podium.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalMiniature
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.ProgressBar
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.HueWalk
import app.podium.core.designsystem.shell.formatHex
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.shell.parseHex
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.BoneColors
import app.podium.core.designsystem.theme.CarbonColors
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.FocusListState
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import kotlin.math.roundToInt

private enum class SettingsRow { Theme, Finish, CustomColor, Grain, MusicFolders, OnlineSources, Autoplay, Recommendations, AvoidRepeats, OnlineLyrics, LyricsCredit, Haptics, Clicks, StartupSound }

/**
 * Settings (D-26, D-32, D-35): the device's look, which folders hold its music, which online sources it
 * uses, and how it answers touch.
 */
@Composable
fun SettingsScreen(
    repository: DeviceSettingsRepository,
    folders: MusicFolderSettings,
    onlineService: OnlineServiceSettings,
    onTheme: () -> Unit,
    onFinish: () -> Unit,
    onCustomColor: () -> Unit,
    onGrain: () -> Unit,
    onMusicFolders: () -> Unit,
    onOnlineService: () -> Unit,
) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val startupSound by repository.startupSound.collectAsStateWithLifecycle()
    val hapticsOn by repository.haptics.collectAsStateWithLifecycle()
    val clicksOn by repository.clicks.collectAsStateWithLifecycle()
    val autoplayOn by repository.autoplay.collectAsStateWithLifecycle()
    val recommendationsOn by repository.onlineRecommendations.collectAsStateWithLifecycle()
    val avoidRepeatsOn by repository.avoidRepeats.collectAsStateWithLifecycle()
    val onlineLyricsOn by repository.onlineLyrics.collectAsStateWithLifecycle()
    val folderList by folders.folders.collectAsStateWithLifecycle()
    val selection by folders.selection.collectAsStateWithLifecycle()
    val service by onlineService.state.collectAsStateWithLifecycle()
    val haptics = rememberPodiumHaptics()
    // Only a build with an online service has anything to choose there.
    val rows = SettingsRow.entries.filter { it != SettingsRow.OnlineSources || service != null }
    val focus = rememberFocusListState("settings")
    val activate: (Int) -> Unit = { index ->
        val industrial = appearance.display.isIndustrial
        when (rows[index]) {
            SettingsRow.Theme -> onTheme()
            SettingsRow.Finish -> if (industrial) haptics.reject() else onFinish()
            SettingsRow.CustomColor -> if (industrial) haptics.reject() else onCustomColor()
            SettingsRow.Grain -> if (appearance.isGlass) haptics.reject() else onGrain()
            SettingsRow.MusicFolders -> onMusicFolders()
            SettingsRow.OnlineSources -> onOnlineService()
            SettingsRow.Autoplay -> {
                haptics.confirm()
                repository.setAutoplay(!autoplayOn)
            }
            SettingsRow.Recommendations -> if (!autoplayOn) haptics.reject() else {
                haptics.confirm()
                repository.setOnlineRecommendations(!recommendationsOn)
            }
            SettingsRow.AvoidRepeats -> if (!autoplayOn) haptics.reject() else {
                haptics.confirm()
                repository.setAvoidRepeats(!avoidRepeatsOn)
            }
            SettingsRow.OnlineLyrics -> {
                haptics.confirm()
                repository.setOnlineLyrics(!onlineLyricsOn)
            }
            SettingsRow.LyricsCredit -> haptics.reject()
            SettingsRow.Haptics -> {
                repository.setHaptics(!hapticsOn)
                haptics.confirm()
            }
            SettingsRow.Clicks -> {
                haptics.confirm()
                repository.setClicks(!clicksOn)
            }
            SettingsRow.StartupSound -> {
                haptics.confirm()
                repository.setStartupSound(!startupSound)
            }
        }
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = rows,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { row ->
            when (row) {
                SettingsRow.Finish -> appearance.baseArgb?.let { MenuPreview.Swatch(Color(it)) } ?: MenuPreview.Instrument
                SettingsRow.CustomColor -> MenuPreview.Swatch(Color(appearance.customArgb))
                else -> MenuPreview.Instrument
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        val industrial = appearance.display.isIndustrial
        when (row) {
            SettingsRow.Theme -> MenuRow("Theme", focused, value = appearance.display.label)
            SettingsRow.Finish -> MenuRow(
                "Finish",
                focused,
                value = if (industrial) "Matte black with ${appearance.display.label}" else appearance.preset.label,
                enabled = !industrial,
                showChevron = !industrial,
            )
            SettingsRow.CustomColor -> MenuRow(
                "Custom color",
                focused,
                value = formatHex(appearance.customArgb),
                leadingContent = { Swatch(Color(appearance.customArgb)) },
                enabled = !industrial,
                showChevron = !industrial,
            )
            SettingsRow.Grain -> MenuRow(
                "Grain",
                focused,
                value = if (appearance.isGlass) "Solid finishes only" else percent(appearance.grain),
                enabled = !appearance.isGlass,
                showChevron = !appearance.isGlass,
            )
            SettingsRow.MusicFolders -> MenuRow(
                "Music folders",
                focused,
                value = when {
                    folderList == null -> null
                    selection == app.podium.sources.api.FolderSelection.Everything -> "All"
                    else -> "${songsIncluded(folderList.orEmpty(), selection)} songs"
                },
            )
            SettingsRow.OnlineSources -> MenuRow(service?.name ?: "Online music", focused, value = onlineServiceSummary(service))
            SettingsRow.Autoplay -> MenuRow("Autoplay", focused, value = if (autoplayOn) "On" else "Off", showChevron = false)
            SettingsRow.Recommendations -> MenuRow("Online recommendations", focused, value = if (recommendationsOn) "On" else "Off", enabled = autoplayOn, showChevron = false)
            SettingsRow.AvoidRepeats -> MenuRow("Avoid repeats", focused, value = if (avoidRepeatsOn) "On" else "Off", enabled = autoplayOn, showChevron = false)
            SettingsRow.OnlineLyrics -> MenuRow("Online lyrics", focused, value = if (onlineLyricsOn) "On" else "Off", showChevron = false)
            // The lyrics service's credit (LYRICS_ARCHITECTURE.md §9): its community wrote the words.
            SettingsRow.LyricsCredit -> MenuRow("Lyrics from LRCLIB, written by its community", focused, enabled = false, showChevron = false)
            SettingsRow.Haptics -> MenuRow("Haptics", focused, value = if (hapticsOn) "On" else "Off", showChevron = false)
            SettingsRow.Clicks -> MenuRow("Click sound", focused, value = if (clicksOn) "On" else "Off", showChevron = false)
            SettingsRow.StartupSound -> MenuRow("Startup sound", focused, value = if (startupSound) "On" else "Off", showChevron = false)
        }
    }
}

/**
 * Finish picker. Turning the wheel tries each finish on the device itself; the center keeps it,
 * Menu puts the old one back (and goes back). "Custom color" opens the colour editor.
 */
@Composable
fun FinishScreen(repository: DeviceSettingsRepository, onCustomColor: () -> Unit) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val presets = FinishPreset.entries
    val focus = remember { FocusListState(initialIndex = presets.indexOf(repository.appearance.value.preset)) }
    val miniature = LocalMiniature.current
    if (!miniature) LaunchedEffect(focus) {
        snapshotFlow { focus.focusedIndex }.collect { index ->
            val preset = presets.getOrNull(index) ?: return@collect
            val current = repository.appearance.value
            repository.setPreview(if (preset == current.preset) null else current.copy(preset = preset))
        }
    }
    if (!LocalMiniature.current) DisposableEffect(Unit) { onDispose { repository.setPreview(null) } }
    val activate: (Int) -> Unit = { index ->
        val preset = presets[index]
        if (preset == FinishPreset.CUSTOM) {
            onCustomColor()
        } else {
            // Choosing keeps you here with the check on your choice; Menu goes back.
            repository.setAppearance(repository.appearance.value.copy(preset = preset))
            repository.setPreview(null)
        }
    }
    ListInputEffect(focus, onActivate = activate)
    val dark = PodiumTheme.colors.isDark
    FocusList(
        items = presets,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { preset -> DeviceAppearance(preset, customArgb = appearance.customArgb).baseArgb?.let { MenuPreview.Swatch(Color(it)) } ?: MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { preset, _, focused ->
        val swatch = when (preset) {
            FinishPreset.GLASS -> null
            FinishPreset.CUSTOM -> Color(appearance.customArgb)
            else -> DeviceAppearance(preset).palette(dark).bodyTop
        }
        MenuRow(
            preset.label,
            focused,
            leadingContent = { Swatch(swatch) },
            selected = preset == appearance.preset,
            showChevron = preset == FinishPreset.CUSTOM,
        )
    }
}

/**
 * Theme picker (D-29): Glass, or the matte instrument looks Carbon and Bone. Turning the wheel
 * tries each one on the whole device; Center keeps it, Menu puts the old one back.
 */
@Composable
fun ThemeScreen(repository: DeviceSettingsRepository) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val themes = DisplayTheme.entries
    val focus = remember { FocusListState(initialIndex = themes.indexOf(repository.appearance.value.display)) }
    val miniature = LocalMiniature.current
    if (!miniature) LaunchedEffect(focus) {
        snapshotFlow { focus.focusedIndex }.collect { index ->
            val theme = themes.getOrNull(index) ?: return@collect
            val current = repository.appearance.value
            repository.setPreview(if (theme == current.display) null else current.copy(display = theme))
        }
    }
    if (!LocalMiniature.current) DisposableEffect(Unit) { onDispose { repository.setPreview(null) } }
    val activate: (Int) -> Unit = { index ->
        repository.setAppearance(repository.appearance.value.copy(display = themes[index]))
        repository.setPreview(null)
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = themes,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { theme ->
            when (theme) {
                DisplayTheme.GLASS -> MenuPreview.Instrument
                DisplayTheme.CARBON -> MenuPreview.Swatch(CarbonColors.canvas)
                DisplayTheme.BONE -> MenuPreview.Swatch(BoneColors.canvas)
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { theme, _, focused ->
        MenuRow(
            theme.label,
            focused,
            leadingContent = {
                Swatch(
                    when (theme) {
                        DisplayTheme.GLASS -> null
                        DisplayTheme.CARBON -> CarbonColors.canvas
                        DisplayTheme.BONE -> BoneColors.canvas
                    },
                )
            },
            selected = theme == appearance.display,
            showChevron = false,
        )
    }
}

/** Grain strength, adjusted with the wheel and previewed on the device as you turn. */
@Composable
fun GrainScreen(repository: DeviceSettingsRepository) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val insets = LocalScreenInsets.current
    val haptics = rememberPodiumHaptics()
    val appearance = remember { repository.appearance.value }
    var grain by remember { mutableFloatStateOf(appearance.grain) }
    if (!LocalMiniature.current) DisposableEffect(Unit) { onDispose { repository.setPreview(null) } }
    if (appearance.isGlass) {
        Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
            MessageState(PodiumSymbol.Settings, "Grain needs a solid finish", "Choose a finish other than Glass, then come back here.")
        }
        return
    }
    InputTargetEffect(WheelContext.VOLUME) { input ->
        when (input) {
            is PodiumInput.Rotate -> {
                val next = (grain + input.detents * GRAIN_STEP).coerceIn(0f, 1f)
                if (next == grain) haptics.boundary()
                grain = next
                repository.setPreview(appearance.copy(grain = grain))
                true
            }
            is PodiumInput.Press -> if (input.button == WheelButton.CENTER) {
                repository.setAppearance(appearance.copy(grain = grain))
                repository.setPreview(null)
                haptics.confirm()
                true
            } else false
            else -> false
        }
    }
    Column(
        Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom).padding(horizontal = Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PodiumText(percent(grain), type.largeTitle, colors.labelPrimary)
        Spacer(Modifier.height(Spacing.l))
        ProgressBar({ grain }, running = false, emphasized = true)
        Spacer(Modifier.height(Spacing.l))
        PodiumText("Turn the wheel to adjust. Press the center to keep it.", type.footnote, colors.labelSecondary)
    }
}

/**
 * Custom colour editor: type a hex code, or turn the wheel to walk the hue. The device previews
 * the colour as it changes; the center (or the button) applies it as the Custom finish.
 */
@Composable
fun CustomColorScreen(repository: DeviceSettingsRepository) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val insets = LocalScreenInsets.current
    val haptics = rememberPodiumHaptics()
    val focusManager = LocalFocusManager.current
    val appearance = remember { repository.appearance.value }
    var argb by remember { mutableIntStateOf(appearance.customArgb) }
    var walk by remember { mutableStateOf(HueWalk.of(appearance.customArgb)) }
    var text by remember { mutableStateOf(formatHex(argb).drop(1)) }
    var invalid by remember { mutableStateOf(false) }

    val miniature = LocalMiniature.current
    if (!miniature) {
        LaunchedEffect(argb) { repository.setPreview(appearance.copy(preset = FinishPreset.CUSTOM, customArgb = argb)) }
        if (!LocalMiniature.current) DisposableEffect(Unit) { onDispose { repository.setPreview(null) } }
    }

    val apply = {
        repository.setAppearance(appearance.copy(preset = FinishPreset.CUSTOM, customArgb = argb))
        repository.setPreview(null)
        haptics.confirm()
        focusManager.clearFocus()
    }
    InputTargetEffect(WheelContext.VOLUME) { input ->
        when (input) {
            is PodiumInput.Rotate -> {
                walk = walk.turned(input.detents * HUE_STEP_DEGREES)
                argb = walk.argb
                text = formatHex(argb).drop(1)
                invalid = false
                true
            }
            is PodiumInput.Press -> if (input.button == WheelButton.CENTER) {
                apply()
                true
            } else false
            else -> false
        }
    }

    Column(
        Modifier.fillMaxSize().padding(top = insets.top + Spacing.s, bottom = insets.bottom).padding(horizontal = Spacing.xl),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .background(Color(argb), RoundedCornerShape(16.dp))
                .border(1.dp, colors.separator, RoundedCornerShape(16.dp))
                .semantics { contentDescription = "Color preview ${formatHex(argb)}" },
        )
        Spacer(Modifier.height(Spacing.l))
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(colors.canvasRaised, RoundedCornerShape(14.dp))
                .border(1.dp, if (invalid) colors.critical else colors.separator, RoundedCornerShape(14.dp))
                .padding(horizontal = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PodiumText("#", type.title, colors.labelSecondary)
            Spacer(Modifier.width(Spacing.xs))
            BasicTextField(
                value = text,
                onValueChange = { raw ->
                    val cleaned = raw.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                    text = cleaned
                    val parsed = parseHex(cleaned)
                    invalid = cleaned.length == 6 && parsed == null
                    if (parsed != null) {
                        argb = parsed
                        walk = HueWalk.of(parsed)
                    }
                },
                singleLine = true,
                textStyle = type.title.copy(color = colors.labelPrimary),
                cursorBrush = SolidColor(colors.highlightText),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Hex color code" },
            )
        }
        Spacer(Modifier.height(Spacing.s))
        PodiumText(
            if (text.length < 6) "Use six hex digits, like 2F6F5E. Or turn the wheel to change the hue." else "Turn the wheel to change the hue.",
            type.footnote,
            colors.labelSecondary,
        )
        Spacer(Modifier.height(Spacing.l))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(colors.highlight, RoundedCornerShape(14.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (parseHex(text) != null) apply() else haptics.reject()
                },
            contentAlignment = Alignment.Center,
        ) {
            PodiumText("Use this color", type.rowFocused, colors.onHighlight)
        }
    }
}

@Composable
private fun Swatch(color: Color?) {
    val colors = PodiumTheme.colors
    val shape = CircleShape
    Box(
        Modifier
            .size(22.dp)
            .then(
                if (color != null) {
                    Modifier.background(color, shape)
                } else {
                    // Glass: a clear disc with a rim.
                    Modifier.background(colors.labelPrimary.copy(alpha = 0.08f), shape)
                },
            )
            .border(1.dp, colors.labelPrimary.copy(alpha = 0.25f), shape),
    )
}

private fun percent(value: Float) = "${(value * 100).roundToInt()}%"

private const val GRAIN_STEP = 0.04f
private const val HUE_STEP_DEGREES = 6.0
