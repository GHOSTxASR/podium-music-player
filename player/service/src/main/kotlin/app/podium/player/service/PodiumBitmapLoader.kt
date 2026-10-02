@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import app.podium.core.model.ArtworkRef
import app.podium.sources.api.ArtworkPayload
import app.podium.sources.api.ArtworkResolver
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future

/**
 * Notification/lock-screen artwork. `podium-art://` URIs are resolved through the owning source's
 * ArtworkFacet (no provider URLs in the session); anything else goes to Media3's default loader.
 */
class PodiumBitmapLoader(context: Context, private val artwork: ArtworkResolver) : BitmapLoader {
    private val delegate = DataSourceBitmapLoader.Builder(context).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = delegate.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != ArtworkRef.SCHEME) return delegate.loadBitmap(uri)
        return scope.future {
            when (val payload = artwork.load(uri.toString(), SIZE_PX)) {
                is ArtworkPayload.LocalFile -> BitmapFactory.decodeFile(payload.path)
                is ArtworkPayload.Bytes -> BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.size)
                null -> null
            } ?: throw IllegalStateException("No artwork for $uri")
        }
    }

    private companion object {
        const val SIZE_PX = 512
    }
}
