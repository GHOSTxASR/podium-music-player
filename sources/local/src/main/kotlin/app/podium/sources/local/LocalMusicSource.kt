package app.podium.sources.local

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.ArtworkFacet
import app.podium.sources.api.ArtworkPayload
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CapabilityAction
import app.podium.sources.api.CapabilityState
import app.podium.sources.api.CapabilityStatus
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.FolderFacet
import app.podium.sources.api.FolderSelection
import app.podium.sources.api.LibraryFacet
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicFolder
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.matching.TrackNormalizer
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Music files on this device, through Android's media library (MediaStore).
 *
 * Without the music permission every capability reports REQUIRES_PERMISSION with a generic
 * "allow access" action; the source never pretends to be an empty library.
 */
class LocalMusicSource(context: Context, private val scope: CoroutineScope) : MusicSource {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val artDir = File(appContext.cacheDir, "local-artwork")

    override val descriptor = SourceDescriptor(
        id = LocalTrackMapper.SOURCE_ID,
        displayName = "On this device",
        providerName = "Android media library",
        basis = Basis.LOCAL_DEVICE,
        cheapResolve = true,
    )

    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val _capabilities = MutableStateFlow(computeCapabilities())
    override val capabilities: StateFlow<SourceCapabilities> = _capabilities.asStateFlow()

    /** Which folders are read (D-32), kept with the source's own settings. */
    private val prefs = appContext.getSharedPreferences("local_source", Context.MODE_PRIVATE)
    private val _selection = MutableStateFlow(loadSelection())
    private val _folders = MutableStateFlow<List<MusicFolder>?>(null)

    /** Null until the first scan completes. */
    private val _tracks = MutableStateFlow<List<Track>?>(null)
    val tracksState: StateFlow<List<Track>?> = _tracks.asStateFlow()
    private var scanJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh(debounceMs = 1_000)
    }

    init {
        resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /** Call after the user grants or revokes the music permission. */
    fun onPermissionChanged() {
        _capabilities.value = computeCapabilities()
        refresh()
    }

    private fun computeCapabilities(): SourceCapabilities {
        val offered = listOf(Capability.SEARCH, Capability.BROWSE, Capability.LIBRARY, Capability.DIRECT_STREAM, Capability.ARTWORK)
        return if (hasPermission()) {
            SourceCapabilities.available(*offered.toTypedArray())
        } else {
            val state = CapabilityState(
                CapabilityStatus.REQUIRES_PERMISSION,
                note = "Allow music access",
                action = CapabilityAction.RequestPermission(permission, "Allow access"),
            )
            SourceCapabilities(offered.associateWith { state })
        }
    }

    fun refresh(debounceMs: Long = 0) {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            if (debounceMs > 0) delay(debounceMs)
            _tracks.value = if (hasPermission()) scan() else emptyList()
        }
    }

    private fun scan(): List<Track> {
        val api30 = Build.VERSION.SDK_INT >= 30
        val projection = buildList {
            addAll(
                listOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ARTIST_ID,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR,
                    MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
            )
            if (api30) {
                addAll(
                    listOf(
                        MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.BITRATE,
                        MediaStore.Audio.Media.CD_TRACK_NUMBER, MediaStore.Audio.Media.DISC_NUMBER,
                    ),
                )
            }
        }.toTypedArray()
        val rows = mutableListOf<MediaStoreRow>()
        try {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    rows += MediaStoreRow(
                        id = c.long(MediaStore.Audio.Media._ID) ?: continue,
                        title = c.string(MediaStore.Audio.Media.TITLE),
                        artist = c.string(MediaStore.Audio.Media.ARTIST),
                        album = c.string(MediaStore.Audio.Media.ALBUM),
                        albumId = c.long(MediaStore.Audio.Media.ALBUM_ID) ?: 0,
                        artistId = c.long(MediaStore.Audio.Media.ARTIST_ID) ?: 0,
                        albumArtist = if (api30) c.string(MediaStore.Audio.Media.ALBUM_ARTIST) else null,
                        durationMs = c.long(MediaStore.Audio.Media.DURATION),
                        trackColumn = c.int(MediaStore.Audio.Media.TRACK),
                        cdTrackNumber = if (api30) c.string(MediaStore.Audio.Media.CD_TRACK_NUMBER) else null,
                        discNumber = if (api30) c.string(MediaStore.Audio.Media.DISC_NUMBER) else null,
                        year = c.int(MediaStore.Audio.Media.YEAR),
                        mimeType = c.string(MediaStore.Audio.Media.MIME_TYPE),
                        bitrate = if (api30) c.int(MediaStore.Audio.Media.BITRATE) else null,
                        sizeBytes = c.long(MediaStore.Audio.Media.SIZE),
                        relativePath = c.string(MediaStore.MediaColumns.RELATIVE_PATH),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read the media library", e)
            return emptyList()
        }
        // Every folder with music is listed (chosen or not); only chosen folders reach the library.
        _folders.value = rows.groupingBy { FolderSelection.normalize(it.relativePath.orEmpty()) }.eachCount()
            .map { (path, count) -> MusicFolder(path, count) }
            .sortedBy { it.path.lowercase() }
        val selection = _selection.value
        return rows.filter { selection.includes(it.relativePath.orEmpty()) }.map(LocalTrackMapper::toTrack)
    }

    private fun loadSelection(): FolderSelection {
        if (!prefs.contains(KEY_INCLUDED) && !prefs.contains(KEY_EXCLUDED)) return FolderSelection.Default
        return FolderSelection(
            included = prefs.getStringSet(KEY_INCLUDED, emptySet()).orEmpty().toSet(),
            excluded = prefs.getStringSet(KEY_EXCLUDED, emptySet()).orEmpty().toSet(),
        )
    }

    override val folders: FolderFacet = object : FolderFacet {
        override fun folders(): Flow<List<MusicFolder>> = _folders.onStart { if (_folders.value == null) refresh() }.filterNotNull()

        override val selection: StateFlow<FolderSelection> = _selection.asStateFlow()

        override fun select(selection: FolderSelection) {
            if (selection == _selection.value) return
            _selection.value = selection
            prefs.edit()
                .putStringSet(KEY_INCLUDED, selection.included)
                .putStringSet(KEY_EXCLUDED, selection.excluded)
                .apply()
            refresh()
        }
    }

    private fun currentTracks(): List<Track> = _tracks.value.orEmpty()

    private fun albumsOf(tracks: List<Track>): List<AlbumSummary> = tracks
        .filter { it.album?.id != null }
        .groupBy { it.album!!.id!! }
        .map { (id, ts) ->
            val first = ts.first()
            AlbumSummary(
                id = id,
                title = first.album!!.title,
                artistDisplay = first.album?.albumArtist ?: first.artistDisplay,
                artwork = first.artwork,
                year = first.releaseDate?.year,
                trackCount = ts.size,
            )
        }
        .sortedBy { it.title.lowercase() }

    private fun artistsOf(tracks: List<Track>): List<ArtistSummary> = tracks
        .groupBy { it.artists.first().id ?: ArtistId.of(descriptor.id, it.artistDisplay) }
        // MediaStore has no artist images; an album cover is not a picture of the artist.
        .map { (id, ts) -> ArtistSummary(id, ts.first().artistDisplay, artwork = null, trackCount = ts.size) }
        .sortedBy { it.name.lowercase() }

    override val library: LibraryFacet = object : LibraryFacet {
        private fun loaded() = _tracks.onStart { if (_tracks.value == null) refresh() }.filterNotNull()
        override fun tracks(): Flow<List<Track>> = loaded()
        override fun albums(): Flow<List<AlbumSummary>> = loaded().map { albumsOf(it) }
        override fun artists(): Flow<List<ArtistSummary>> = loaded().map { artistsOf(it) }
    }

    override val catalog: CatalogFacet = object : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> {
            if (!hasPermission()) return Outcome.Failure(PodiumError.PermissionRequired(permission))
            val words = TrackNormalizer.identityKey(query.text).split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return Outcome.Success(SearchResults.Empty)
            val hits = currentTracks().filter { t ->
                val hay = TrackNormalizer.identityKey("${t.title} ${t.artistDisplay} ${t.album?.title.orEmpty()}").split(' ')
                words.all { w -> hay.any { it.startsWith(w) } }
            }
            return Outcome.Success(SearchResults(tracks = hits.take(query.limit)))
        }

        override suspend fun track(ref: SourceRef): Outcome<Track> =
            currentTracks().firstOrNull { it.source.providerKey == ref.providerKey }?.let { Outcome.Success(it) }
                ?: Outcome.Failure(PodiumError.NotFound(ref.providerKey))

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> {
            val tracks = currentTracks().filter { it.album?.id == id }
            val summary = albumsOf(tracks).firstOrNull() ?: return Outcome.Failure(PodiumError.NotFound("album"))
            return Outcome.Success(AlbumDetail(summary, tracks.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: 0 }))))
        }

        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> {
            val tracks = currentTracks().filter { it.artists.first().id == id }
            val summary = artistsOf(tracks).firstOrNull() ?: return Outcome.Failure(PodiumError.NotFound("artist"))
            return Outcome.Success(ArtistDetail(summary, albumsOf(tracks), tracks))
        }
    }

    override val playback: PlaybackFacet = object : PlaybackFacet {
        override val routes = setOf(PlaybackRoute.DIRECT)

        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            if (!hasPermission()) return FacetResolution.Miss(MissReason.NOT_PERMITTED)
            val id = track.source.providerKey.toLongOrNull() ?: return FacetResolution.Miss(MissReason.NOT_FOUND)
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            val exists = withContext(Dispatchers.IO) {
                runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
            }
            if (!exists) return FacetResolution.Miss(MissReason.NOT_FOUND)
            return FacetResolution.Resolved(
                PlaybackTarget.DirectStream(
                    track.id,
                    PlayableMedia(
                        uri = uri.toString(),
                        mimeType = track.advertisedQualities.firstOrNull()?.container,
                        claimedQuality = track.advertisedQualities.firstOrNull(),
                        durationMs = track.durationMs,
                        sourceId = descriptor.id,
                        cacheKey = "${track.id.value}|original",
                    ),
                ),
            )
        }
    }

    override val artwork: ArtworkFacet = object : ArtworkFacet {
        override suspend fun load(ref: ArtworkRef, sizePx: Int): ArtworkPayload? = withContext(Dispatchers.IO) {
            val (albumId, audioId) = LocalTrackMapper.parseArtworkKey(ref.key) ?: return@withContext null
            val file = File(artDir, "album-$albumId-$sizePx.jpg")
            if (file.exists()) return@withContext ArtworkPayload.LocalFile(file.absolutePath)
            val audioUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioId)
            val bitmap = loadThumbnail(audioUri, sizePx) ?: embeddedPicture(audioUri, sizePx) ?: return@withContext null
            artDir.mkdirs()
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
            ArtworkPayload.LocalFile(file.absolutePath)
        }
    }

    private fun loadThumbnail(uri: Uri, sizePx: Int): Bitmap? =
        runCatching { resolver.loadThumbnail(uri, Size(sizePx, sizePx), null) }.getOrNull()

    private fun embeddedPicture(uri: Uri, sizePx: Int): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val bytes = retriever.embeddedPicture ?: return null
            val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true)
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun Cursor.index(column: String) = getColumnIndex(column).takeIf { it >= 0 }
    private fun Cursor.string(column: String) = index(column)?.let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(column: String) = index(column)?.let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(column: String) = index(column)?.let { if (isNull(it)) null else getInt(it) }

    private companion object {
        const val TAG = "LocalMusicSource"
        const val KEY_INCLUDED = "folders_included"
        const val KEY_EXCLUDED = "folders_excluded"
    }
}
