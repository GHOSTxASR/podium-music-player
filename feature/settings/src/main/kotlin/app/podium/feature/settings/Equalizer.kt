package app.podium.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.ProgressBar
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.player.api.EqualizerPreset
import app.podium.player.api.EqualizerSettings
import app.podium.player.api.SoundEffect
import kotlin.math.roundToInt

private sealed interface EqRow {
    data object Power : EqRow
    data object Preset : EqRow
    data object Effect : EqRow
    data class Band(val index: Int) : EqRow
}

/**
 * Settings ▸ Equaliser (D-76): on or off, a preset, the effect (D-89 — it plays with the bands on or
 * off), and the ten bands, each opening to be set with the Wheel. Every change is heard at once.
 */
@Composable
fun EqualizerScreen(repository: DeviceSettingsRepository, onBand: (Int) -> Unit) {
    val effects by repository.effects.collectAsStateWithLifecycle()
    val eq = effects.equalizer
    val haptics = rememberPodiumHaptics()
    val overlay = LocalOverlayHost.current
    val rows = listOf(EqRow.Power, EqRow.Preset, EqRow.Effect) + EqualizerSettings.BANDS_HZ.indices.map { EqRow.Band(it) }
    val focus = rememberFocusListState("equalizer")
    val activate: (Int) -> Unit = { i ->
        when (val row = rows[i]) {
            EqRow.Power -> {
                haptics.confirm()
                repository.setEqualizer(eq.copy(enabled = !eq.enabled))
            }
            EqRow.Preset -> if (!eq.enabled) haptics.reject() else overlay.show(
                MenuSpec(
                    "Preset: ${eq.preset.label}",
                    EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM }.map { preset ->
                        MenuAction(preset.label) { repository.setEqualizer(eq.withPreset(preset)) }
                    },
                ),
            )
            EqRow.Effect -> overlay.show(
                MenuSpec(
                    "Effect: ${effects.sound.label}",
                    SoundEffect.entries.map { effect ->
                        MenuAction(effect.label) { repository.setSoundEffect(effect) }
                    },
                ),
            )
            is EqRow.Band -> if (!eq.enabled) haptics.reject() else onBand(row.index)
        }
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = rows,
        state = focus,
        key = { it.toString() },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            EqRow.Power -> MenuRow("Equaliser", focused, value = if (eq.enabled) "On" else "Off", showChevron = false)
            EqRow.Preset -> MenuRow("Preset", focused, value = eq.preset.label, enabled = eq.enabled, showChevron = false)
            EqRow.Effect -> MenuRow("Effect", focused, value = effects.sound.label, showChevron = false)
            is EqRow.Band -> MenuRow(bandLabel(row.index), focused, value = gainLabel(eq.gainsDb[row.index]), enabled = eq.enabled)
        }
    }
}

/** One band, set with the Wheel a decibel a turn, heard as it changes; Center or Menu goes back. */
@Composable
fun EqualizerBandScreen(repository: DeviceSettingsRepository, band: Int, onDone: () -> Unit) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val insets = LocalScreenInsets.current
    val haptics = rememberPodiumHaptics()
    val effects by repository.effects.collectAsStateWithLifecycle()
    val gain = effects.equalizer.gainsDb.getOrElse(band) { 0f }
    InputTargetEffect(WheelContext.VOLUME) { input ->
        when (input) {
            is PodiumInput.Rotate -> {
                val current = repository.effects.value.equalizer
                val next = (current.gainsDb[band] + input.detents * EqualizerSettings.STEP_DB)
                    .coerceIn(-EqualizerSettings.MAX_GAIN_DB, EqualizerSettings.MAX_GAIN_DB)
                if (next == current.gainsDb[band]) haptics.boundary() else haptics.step()
                repository.setEqualizer(current.withGain(band, next))
                true
            }
            is PodiumInput.Press -> if (input.button == WheelButton.CENTER) {
                haptics.confirm()
                onDone()
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
        PodiumText(gainLabel(gain), type.largeTitle, colors.labelPrimary)
        Spacer(Modifier.height(Spacing.xs))
        PodiumText(bandLabel(band), type.body, colors.labelSecondary)
        Spacer(Modifier.height(Spacing.l))
        ProgressBar({ (gain + EqualizerSettings.MAX_GAIN_DB) / (2 * EqualizerSettings.MAX_GAIN_DB) }, running = false, emphasized = true)
        Spacer(Modifier.height(Spacing.l))
        PodiumText(
            "Turn the wheel to boost or cut. Press the center when it sounds right.",
            type.footnote,
            colors.labelSecondary,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

/** "31 Hz", "1 kHz", "16 kHz". */
fun bandLabel(band: Int): String {
    val hz = EqualizerSettings.BANDS_HZ[band]
    return if (hz < 1_000) "$hz Hz" else "${hz / 1_000} kHz"
}

/** "+3 dB", "0 dB", "−4 dB" (a true minus sign). */
internal fun gainLabel(db: Float): String {
    val whole = db.roundToInt()
    return when {
        whole > 0 -> "+$whole dB"
        whole < 0 -> "−${-whole} dB"
        else -> "0 dB"
    }
}
