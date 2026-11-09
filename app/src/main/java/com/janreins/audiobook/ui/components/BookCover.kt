package com.janreins.audiobook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.janreins.audiobook.data.CoverModel
import com.janreins.audiobook.data.CoverPlaceholders
import com.janreins.audiobook.data.CoverStore
import com.janreins.audiobook.data.CoverThumbnail
import okio.Buffer
import com.janreins.audiobook.data.coverModel
import com.janreins.audiobook.data.model.Audiobook
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.IOException

class CoverKeyer : Keyer<CoverModel> {
    override fun key(data: CoverModel, options: Options): String = CoverStore.cacheKey(data)
}

class CoverFetcher(private val model: CoverModel, private val store: CoverStore) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        return when (val thumbnail = store.thumbnail(model) ?: throw IOException("No cover art")) {
            is CoverThumbnail.Cached -> SourceFetchResult(
                source = ImageSource(file = thumbnail.file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK
            )
            is CoverThumbnail.InMemory -> SourceFetchResult(
                source = ImageSource(source = Buffer().write(thumbnail.jpeg), fileSystem = FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.MEMORY
            )
        }
    }

    class Factory(private val store: CoverStore) : Fetcher.Factory<CoverModel> {
        override fun create(data: CoverModel, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverFetcher(data, store)
    }
}

@Composable
fun BookCover(
    book: Audiobook,
    modifier: Modifier,
    cornerRadius: Dp,
    showPlayingOverlay: Boolean = false
) {
    val model = remember(book) { book.coverModel() }
    val color = Color(CoverPlaceholders.PALETTE[CoverPlaceholders.colorIndex(book.id, CoverPlaceholders.PALETTE.size)])
    BoxWithConstraints(
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)).background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = CoverPlaceholders.initials(book.title),
            style = MaterialTheme.typography.titleLarge,
            fontSize = (minOf(maxWidth, maxHeight).value * 0.34f).sp,
            color = Color.White,
            // Placeholder initials are decoration; the book title is read out next to the cover.
            modifier = Modifier.clearAndSetSemantics {}
        )
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (showPlayingOverlay) {
            Icon(
                imageVector = Icons.Default.Equalizer,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            )
        }
    }
}
