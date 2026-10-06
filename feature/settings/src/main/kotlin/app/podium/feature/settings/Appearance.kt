package app.podium.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import app.podium.core.designsystem.shell.formatHex
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.DisplayBackground
import app.podium.core.designsystem.theme.LocalPodiumType
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.theme.TextContrast
import app.podium.core.designsystem.type.DisplayFont
import app.podium.core.designsystem.type.LocalTypographyPreset
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.designsystem.type.TypographyPreset
import app.podium.core.interaction.FocusListState
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import kotlin.math.roundToInt

/*
 * Settings ▸ Appearance (D-41, PODIUM_CUSTOMIZATION.md §8): the device body (finish, grain, glitter)
 * and the virtual display (theme, font, background, contrast), each one more Podium menu. Choices
 * are tried on the device itself as the Wheel turns; Center keeps them, Menu puts the old look back.
 */

/** A 0–1 level in Appearance, edited with the Wheel and previewed live ([LevelScreen]). */
enum class AppearanceLevel(val label: String, val step: Float) {
    Grain("Grain", 0.04f),
    GlitterAmount("Glitter amount", 0.05f),
    GlitterDensity("Glitter density", 0.05f),
    GlitterSize("Glitter size", 0.05f),
    GlitterOpacity("Glitter opacity", 0.05f),
    BackgroundOpacity("Background opacity", 0.05f),
    ;

    fun read(a: DeviceAppearance): Float = when (this) {
        Grain -> a.grain
        GlitterAmount -> a.glitter.amount
        GlitterDensity -> a.glitter.density
        GlitterSize -> a.glitter.size
        GlitterOpacity -> a.glitter.opacity
        BackgroundOpacity -> a.screen.imageOpacity
    }

    fun write(a: DeviceAppearance, value: Float): DeviceAppearance {
        val v = value.coerceIn(0f, 1f)
        return when (this) {
            Grain -> a.copy(grain = v)
            GlitterAmount -> a.copy(glitter = a.glitter.copy(amount = v))
            GlitterDensity -> a.copy(glitter = a.glitter.copy(density = v))
            GlitterSize -> a.copy(glitter = a.glitter.copy(size = v))
            GlitterOpacity -> a.copy(glitter = a.glitter.copy(opacity = v))
            BackgroundOpacity -> a.copy(screen = a.screen.copy(imageOpacity = v))
        }
    }

    /** Why this level can't be adjusted right now (title, next step), or null when it can. */
    fun unavailable(a: DeviceAppearance): Pair<String, String>? = when (this) {
        Grain -> if (a.isGlass) "Grain needs a solid finish" to "Choose a finish other than Glass, then come back here." else null
        GlitterAmount, GlitterDensity, GlitterSize, GlitterOpacity -> when {
            a.isGlass -> "Glitter needs a solid finish" to "Choose a finish other than Glass, then come back here."
            !a.glitter.enabled -> "Glitter is off" to "Turn Glitter on in Device body, then come back here."
            else -> null
        }
        BackgroundOpacity -> if (a.screen.backgroundFor(a.display) != DisplayBackground.IMAGE) {
            "No background picture" to "Choose Image under Background first."
        } else null
    }
}

/** Whether the chosen background picture can be shown (the app reads it). */
enum class BackgroundImageStatus { NONE, READY, UNAVAILABLE }

private enum class AppearanceRow { DeviceBody, VirtualDisplay }

/** Settings ▸ Appearance: the body, and the display inside it. */
@Composable
fun AppearanceScreen(repository: DeviceSettingsRepository, onDeviceBody: () -> Unit, onVirtualDisplay: () -> Unit) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val rows = AppearanceRow.entries
    val focus = rememberFocusListState("appearance")
    val canvas = PodiumTheme.colors.canvas
    val activate: (Int) -> Unit = { index ->
        when (rows[index]) {
            AppearanceRow.DeviceBody -> onDeviceBody()
            AppearanceRow.VirtualDisplay -> onVirtualDisplay()
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
                AppearanceRow.DeviceBody -> appearance.baseArgb?.let { MenuPreview.Swatch(Color(it)) } ?: MenuPreview.Instrument
                AppearanceRow.VirtualDisplay -> MenuPreview.Swatch(canvas)
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            AppearanceRow.DeviceBody -> MenuRow("Device body", focused, value = bodySummary(appearance))
            AppearanceRow.VirtualDisplay -> MenuRow("Virtual display", focused, value = "${appearance.display.label}, ${appearance.screen.font.label}")
        }
    }
}

private fun bodySummary(a: DeviceAppearance): String = when {
    a.display.isIndustrial -> if (a.glitter.enabled) "Matte black, glitter" else "Matte black"
    a.glitter.enabled && !a.isGlass -> "${a.preset.label}, glitter"
    else -> a.preset.label
}

private enum class BodyRow { Finish, CustomColor, Grain, Glitter, GlitterAmount, GlitterDensity, GlitterSize, GlitterOpacity, GlitterAnimation }

/** Settings ▸ Appearance ▸ Device body: finish, grain and glitter. */
@Composable
fun DeviceBodyScreen(
    repository: DeviceSettingsRepository,
    onFinish: () -> Unit,
    onCustomColor: () -> Unit,
    onLevel: (AppearanceLevel) -> Unit,
) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val haptics = rememberPodiumHaptics()
    val rows = BodyRow.entries
    val focus = rememberFocusListState("device-body")
    val industrial = appearance.display.isIndustrial
    val solid = !appearance.isGlass
    val glitterOn = solid && appearance.glitter.enabled
    fun level(level: AppearanceLevel) = if (level.unavailable(appearance) != null) haptics.reject() else onLevel(level)
    val activate: (Int) -> Unit = { index ->
        when (rows[index]) {
            BodyRow.Finish -> if (industrial) haptics.reject() else onFinish()
            BodyRow.CustomColor -> if (industrial) haptics.reject() else onCustomColor()
            BodyRow.Grain -> level(AppearanceLevel.Grain)
            BodyRow.Glitter -> if (!solid) haptics.reject() else {
                haptics.confirm()
                repository.setAppearance(appearance.copy(glitter = appearance.glitter.copy(enabled = !appearance.glitter.enabled)))
            }
            BodyRow.GlitterAmount -> level(AppearanceLevel.GlitterAmount)
            BodyRow.GlitterDensity -> level(AppearanceLevel.GlitterDensity)
            BodyRow.GlitterSize -> level(AppearanceLevel.GlitterSize)
            BodyRow.GlitterOpacity -> level(AppearanceLevel.GlitterOpacity)
            BodyRow.GlitterAnimation -> if (!glitterOn) haptics.reject() else {
                haptics.confirm()
                repository.setAppearance(appearance.copy(glitter = appearance.glitter.copy(animated = !appearance.glitter.animated)))
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
                BodyRow.Finish -> appearance.baseArgb?.let { MenuPreview.Swatch(Color(it)) } ?: MenuPreview.Instrument
                BodyRow.CustomColor -> MenuPreview.Swatch(Color(appearance.customArgb))
                else -> MenuPreview.Instrument
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            BodyRow.Finish -> MenuRow(
                "Finish",
                focused,
                value = if (industrial) "Matte black with ${appearance.display.label}" else appearance.preset.label,
                enabled = !industrial,
                showChevron = !industrial,
            )
            BodyRow.CustomColor -> MenuRow(
                "Custom color",
                focused,
                value = formatHex(appearance.customArgb),
                leadingContent = { Swatch(Color(appearance.customArgb)) },
                enabled = !industrial,
                showChevron = !industrial,
            )
            BodyRow.Grain -> MenuRow("Grain", focused, value = if (solid) percent(appearance.grain) else "Solid finishes only", enabled = solid, showChevron = solid)
            BodyRow.Glitter -> MenuRow(
                "Glitter",
                focused,
                value = if (!solid) "Solid finishes only" else if (appearance.glitter.enabled) "On" else "Off",
                enabled = solid,
                showChevron = false,
            )
            BodyRow.GlitterAmount -> MenuRow("Glitter amount", focused, value = percent(appearance.glitter.amount), enabled = glitterOn, showChevron = glitterOn)
            BodyRow.GlitterDensity -> MenuRow("Glitter density", focused, value = percent(appearance.glitter.density), enabled = glitterOn, showChevron = glitterOn)
            BodyRow.GlitterSize -> MenuRow("Glitter size", focused, value = percent(appearance.glitter.size), enabled = glitterOn, showChevron = glitterOn)
            BodyRow.GlitterOpacity -> MenuRow("Glitter opacity", focused, value = percent(appearance.glitter.opacity), enabled = glitterOn, showChevron = glitterOn)
            BodyRow.GlitterAnimation -> MenuRow(
                "Glitter animation",
                focused,
                value = if (appearance.glitter.animated) "Subtle" else "Off",
                enabled = glitterOn,
                showChevron = false,
            )
        }
    }
}

private enum class DisplayRow { Theme, Font, Background, BackgroundColor, BackgroundOpacity, TextContrast }

/** Settings ▸ Appearance ▸ Virtual display: theme, font, background and contrast. */
@Composable
fun VirtualDisplayScreen(
    repository: DeviceSettingsRepository,
    imageStatus: BackgroundImageStatus,
    onTheme: () -> Unit,
    onFont: () -> Unit,
    onBackground: () -> Unit,
    onBackgroundColor: () -> Unit,
    onLevel: (AppearanceLevel) -> Unit,
) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val haptics = rememberPodiumHaptics()
    val rows = DisplayRow.entries
    val focus = rememberFocusListState("virtual-display")
    val screen = appearance.screen
    // Carbon and Bone keep their own display (D-29).
    val ownDisplay = appearance.display == app.podium.core.designsystem.theme.DisplayTheme.CARBON ||
        appearance.display == app.podium.core.designsystem.theme.DisplayTheme.BONE
    val shown = screen.backgroundFor(appearance.display)
    val canvas = PodiumTheme.colors.canvas
    val activate: (Int) -> Unit = { index ->
        when (rows[index]) {
            DisplayRow.Theme -> onTheme()
            DisplayRow.Font -> onFont()
            DisplayRow.Background -> if (ownDisplay) haptics.reject() else onBackground()
            DisplayRow.BackgroundColor -> if (ownDisplay) haptics.reject() else onBackgroundColor()
            DisplayRow.BackgroundOpacity -> if (AppearanceLevel.BackgroundOpacity.unavailable(appearance) != null) haptics.reject() else onLevel(AppearanceLevel.BackgroundOpacity)
            DisplayRow.TextContrast -> {
                haptics.confirm()
                val next = if (screen.contrast == TextContrast.STANDARD) TextContrast.HIGH else TextContrast.STANDARD
                repository.setAppearance(appearance.copy(screen = screen.copy(contrast = next)))
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
                DisplayRow.BackgroundColor -> MenuPreview.Swatch(Color(screen.solidArgb))
                else -> MenuPreview.Swatch(canvas)
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        val notHere = "Not with ${appearance.display.label}"
        when (row) {
            DisplayRow.Theme -> MenuRow("Theme", focused, value = appearance.display.label)
            DisplayRow.Font -> MenuRow("Font", focused, value = screen.font.label)
            DisplayRow.Background -> MenuRow(
                "Background",
                focused,
                value = when {
                    ownDisplay -> notHere
                    shown == DisplayBackground.IMAGE && imageStatus == BackgroundImageStatus.UNAVAILABLE -> "Picture unavailable"
                    else -> shown.label
                },
                enabled = !ownDisplay,
                showChevron = !ownDisplay,
            )
            DisplayRow.BackgroundColor -> MenuRow(
                "Background color",
                focused,
                value = if (ownDisplay) notHere else formatHex(screen.solidArgb),
                leadingContent = if (ownDisplay) null else { { Swatch(Color(screen.solidArgb)) } },
                enabled = !ownDisplay,
                showChevron = !ownDisplay,
            )
            DisplayRow.BackgroundOpacity -> {
                val adjustable = AppearanceLevel.BackgroundOpacity.unavailable(appearance) == null
                MenuRow("Background opacity", focused, value = if (adjustable) percent(screen.imageOpacity) else "With a picture", enabled = adjustable, showChevron = adjustable)
            }
            DisplayRow.TextContrast -> MenuRow("Text contrast", focused, value = screen.contrast.label, showChevron = false)
        }
    }
}

/**
 * Font picker: each face set in itself. Turning the Wheel tries it on the whole display; Center
 * keeps it, Menu puts the old one back.
 */
@Composable
fun FontScreen(repository: DeviceSettingsRepository) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val fonts = DisplayFont.entries
    val focus = remember { FocusListState(initialIndex = fonts.indexOf(repository.appearance.value.screen.font)) }
    PreviewWhileFocused(repository, focus) { current, index ->
        fonts.getOrNull(index)?.let { font -> if (font == current.screen.font) null else current.copy(screen = current.screen.copy(font = font)) }
    }
    val activate: (Int) -> Unit = { index ->
        val current = repository.appearance.value
        repository.setAppearance(current.copy(screen = current.screen.copy(font = fonts[index])))
        repository.setPreview(null)
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = fonts,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        modifier = Modifier.fillMaxSize(),
    ) { font, _, focused ->
        // The row in its own face, so the list is its own specimen.
        val preset = TypographyPreset.of(font)
        CompositionLocalProvider(LocalTypographyPreset provides preset, LocalPodiumType provides preset.type) {
            MenuRow(font.label, focused, selected = font == appearance.screen.font, showChevron = false)
        }
    }
}

private enum class BackgroundRow { None, Solid, Image, ChooseImage }

/**
 * Background picker: None (the theme's own display), Solid (the background colour), or a picture
 * chosen with the system photo picker — no storage permission. A picture that can no longer be
 * read shows the solid colour instead and says so here.
 */
@Composable
fun BackgroundScreen(repository: DeviceSettingsRepository, imageStatus: BackgroundImageStatus, onChooseImage: () -> Unit) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val haptics = rememberPodiumHaptics()
    val screen = appearance.screen
    val hasImage = screen.imageUri != null
    val rows = BackgroundRow.entries.filter { it != BackgroundRow.ChooseImage || hasImage }
    val focus = remember {
        FocusListState(
            initialIndex = when (repository.appearance.value.screen.background) {
                DisplayBackground.NONE -> 0
                DisplayBackground.SOLID -> 1
                DisplayBackground.IMAGE -> 2
            },
        )
    }
    fun choice(row: BackgroundRow): DisplayBackground? = when (row) {
        BackgroundRow.None -> DisplayBackground.NONE
        BackgroundRow.Solid -> DisplayBackground.SOLID
        BackgroundRow.Image -> if (hasImage) DisplayBackground.IMAGE else null
        BackgroundRow.ChooseImage -> null
    }
    PreviewWhileFocused(repository, focus) { current, index ->
        rows.getOrNull(index)?.let(::choice)?.let { bg -> if (bg == current.screen.background) null else current.copy(screen = current.screen.copy(background = bg)) }
    }
    val activate: (Int) -> Unit = { index ->
        val row = rows[index]
        val bg = choice(row)
        when {
            bg != null -> {
                haptics.confirm()
                val current = repository.appearance.value
                repository.setAppearance(current.copy(screen = current.screen.copy(background = bg)))
                repository.setPreview(null)
            }
            else -> onChooseImage()
        }
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = rows,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { row -> if (row == BackgroundRow.Solid) MenuPreview.Swatch(Color(screen.solidArgb)) else MenuPreview.None },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            BackgroundRow.None -> MenuRow("None", focused, value = "The theme's own", selected = screen.background == DisplayBackground.NONE, showChevron = false)
            BackgroundRow.Solid -> MenuRow(
                "Solid",
                focused,
                value = formatHex(screen.solidArgb),
                leadingContent = { Swatch(Color(screen.solidArgb)) },
                selected = screen.background == DisplayBackground.SOLID,
                showChevron = false,
            )
            BackgroundRow.Image -> MenuRow(
                if (hasImage) "Picture" else "Choose a picture",
                focused,
                value = when {
                    !hasImage -> null
                    imageStatus == BackgroundImageStatus.UNAVAILABLE -> "Unavailable, showing the solid color"
                    else -> null
                },
                selected = hasImage && screen.background == DisplayBackground.IMAGE,
                showChevron = !hasImage,
            )
            BackgroundRow.ChooseImage -> MenuRow("Choose another picture", focused)
        }
    }
}

/**
 * Tries the focused choice on the device while a picker is open ([choose] returns the appearance to
 * preview, or null for "as it is"); the preview ends when the picker closes.
 */
@Composable
internal fun PreviewWhileFocused(
    repository: DeviceSettingsRepository,
    focus: FocusListState,
    choose: (current: DeviceAppearance, index: Int) -> DeviceAppearance?,
) {
    if (LocalMiniature.current) return
    LaunchedEffect(focus) {
        snapshotFlow { focus.focusedIndex }.collect { index -> repository.setPreview(choose(repository.appearance.value, index)) }
    }
    DisposableEffect(Unit) { onDispose { repository.setPreview(null) } }
}

/** A level (0–1) adjusted with the Wheel and previewed on the device as you turn; Center keeps it. */
@Composable
fun LevelScreen(repository: DeviceSettingsRepository, level: AppearanceLevel) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val insets = LocalScreenInsets.current
    val haptics = rememberPodiumHaptics()
    val appearance = remember { repository.appearance.value }
    var value by remember { mutableFloatStateOf(level.read(appearance)) }
    if (!LocalMiniature.current) DisposableEffect(Unit) { onDispose { repository.setPreview(null) } }
    val blocked = level.unavailable(appearance)
    if (blocked != null) {
        Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
            MessageState(PodiumSymbol.Settings, blocked.first, blocked.second)
        }
        return
    }
    InputTargetEffect(WheelContext.VOLUME) { input ->
        when (input) {
            is PodiumInput.Rotate -> {
                val next = (value + input.detents * level.step).coerceIn(0f, 1f)
                if (next == value) haptics.boundary()
                value = next
                repository.setPreview(level.write(appearance, value))
                true
            }
            is PodiumInput.Press -> if (input.button == WheelButton.CENTER) {
                repository.setAppearance(level.write(appearance, value))
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
        PodiumText(percent(value), type.largeTitle, colors.labelPrimary)
        Spacer(Modifier.height(Spacing.l))
        ProgressBar({ value }, running = false, emphasized = true)
        Spacer(Modifier.height(Spacing.l))
        PodiumText("Turn the wheel to adjust. Press the center to keep it.", type.footnote, colors.labelSecondary)
    }
}

internal fun percent(value: Float) = "${(value * 100).roundToInt()}%"
