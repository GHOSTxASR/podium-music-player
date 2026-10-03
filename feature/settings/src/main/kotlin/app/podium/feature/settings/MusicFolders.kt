package app.podium.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.sources.api.FolderSelection
import app.podium.sources.api.MusicFolder
import kotlinx.coroutines.flow.StateFlow

/**
 * The folders the phone's library is read from (D-32), for whichever source reads its library from
 * storage. Rendered generically: the screen never knows which source it is.
 */
interface MusicFolderSettings {
    /** Every folder that holds music; null while unknown (no such source, no access, or scanning). */
    val folders: StateFlow<List<MusicFolder>?>
    val selection: StateFlow<FolderSelection>
    fun select(selection: FolderSelection)
}

/** A folder as the browser shows it: its own name, the songs inside it (all levels), and whether it goes deeper. */
data class FolderNode(val path: String, val name: String, val songCount: Int, val hasSubfolders: Boolean)

/** The folders directly inside [path] ("" is the root), built from the flat list of folders that hold music. */
fun foldersInside(path: String, folders: List<MusicFolder>): List<FolderNode> {
    val parent = FolderSelection.normalize(path)
    return folders.filter { it.path.startsWith(parent) && it.path != parent }
        .groupBy { parent + it.path.removePrefix(parent).substringBefore('/') + "/" }
        .map { (child, inside) ->
            FolderNode(
                path = child,
                name = child.removeSuffix("/").substringAfterLast('/'),
                songCount = inside.sumOf { it.songCount },
                hasSubfolders = inside.any { it.path != child },
            )
        }
        .sortedBy { it.name.lowercase() }
}

/** Songs that would be read, given the folders and a choice. */
fun songsIncluded(folders: List<MusicFolder>, selection: FolderSelection): Int =
    folders.filter { selection.includes(it.path) }.sumOf { it.songCount }

private sealed interface FolderRow {
    data object All : FolderRow
    data class Here(val path: String, val name: String) : FolderRow
    data class Child(val node: FolderNode) : FolderRow
}

/**
 * One level of the folder tree. At the top, "All folders" reads everything; inside a folder,
 * "All of …" reads that folder with everything in it. A folder with subfolders opens on Center
 * (long-press Center chooses it whole); a folder without opens nothing and Center chooses it.
 */
@Composable
fun MusicFoldersScreen(settings: MusicFolderSettings, path: String, onOpen: (String) -> Unit) {
    val folders by settings.folders.collectAsStateWithLifecycle()
    val selection by settings.selection.collectAsStateWithLifecycle()
    val haptics = rememberPodiumHaptics()
    val here = FolderSelection.normalize(path)
    val list = folders
    val rows = remember(list, here) {
        if (list == null) emptyList()
        else buildList {
            add(if (here.isEmpty()) FolderRow.All else FolderRow.Here(here, here.removeSuffix("/").substringAfterLast('/')))
            foldersInside(here, list).forEach { add(FolderRow.Child(it)) }
        }
    }
    val focus = rememberFocusListState("folders:$here")
    val toggle: (String) -> Unit = { p ->
        haptics.confirm()
        settings.select(selection.toggled(p))
    }
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            FolderRow.All -> {
                haptics.confirm()
                settings.select(FolderSelection.Everything)
            }
            is FolderRow.Here -> toggle(row.path)
            is FolderRow.Child -> if (row.node.hasSubfolders) onOpen(row.node.path) else toggle(row.node.path)
            null -> Unit
        }
    }
    val longPress: (Int) -> Unit = { i -> (rows.getOrNull(i) as? FolderRow.Child)?.let { toggle(it.node.path) } }
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)

    if (list == null) {
        val insets = LocalScreenInsets.current
        Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
            MessageState(PodiumSymbol.Library, "No folders to choose yet", "Allow access to music on this phone from Music, then come back.")
        }
        return
    }
    FocusList(
        items = rows,
        state = focus,
        key = {
            when (it) {
                FolderRow.All -> "all"
                is FolderRow.Here -> "here"
                is FolderRow.Child -> it.node.path
            }
        },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            FolderRow.All -> MenuRow(
                "All folders",
                focused,
                value = if (selection == FolderSelection.Everything) "On" else songCount(songsIncluded(list, selection)),
                showChevron = false,
                selected = selection == FolderSelection.Everything,
            )
            is FolderRow.Here -> MenuRow(
                "All of ${row.name}",
                focused,
                showChevron = false,
                leadingContent = { ChoiceBox(selection.includes(row.path), selection.isMixedBelow(row.path)) },
            )
            is FolderRow.Child -> MenuRow(
                row.node.name,
                focused,
                value = songCount(row.node.songCount),
                showChevron = row.node.hasSubfolders,
                leadingContent = { ChoiceBox(selection.includes(row.node.path), selection.isMixedBelow(row.node.path)) },
            )
        }
    }
}

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

/** A small square: filled when read, empty when skipped, half when some of it is read. */
@Composable
private fun ChoiceBox(included: Boolean, mixed: Boolean) {
    val colors = PodiumTheme.colors
    val ink = colors.labelPrimary
    val dim = colors.labelTertiary
    Canvas(Modifier.size(16.dp)) {
        val r = CornerRadius(if (colors.isIndustrial) 1.dp.toPx() else 4.dp.toPx())
        drawRoundRect(if (included || mixed) ink else dim, cornerRadius = r, style = Stroke(1.5.dp.toPx()))
        val inset = 4.dp.toPx()
        val inner = Size(size.width - inset * 2, size.height - inset * 2)
        when {
            included && !mixed -> drawRoundRect(ink, Offset(inset, inset), inner, CornerRadius(r.x / 2))
            mixed -> drawRoundRect(ink, Offset(inset, inset), Size(inner.width / 2, inner.height), CornerRadius(r.x / 2))
            else -> Unit
        }
    }
}
