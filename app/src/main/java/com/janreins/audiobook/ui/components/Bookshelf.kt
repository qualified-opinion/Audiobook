package com.janreins.audiobook.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.janreins.audiobook.data.CoverPlaceholders
import com.janreins.audiobook.data.Shelf
import com.janreins.audiobook.data.ShelfLayout
import com.janreins.audiobook.data.ShelfSection
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.ui.theme.SpineBand
import com.janreins.audiobook.ui.theme.SpineTitle
import com.janreins.audiobook.ui.theme.WoodAccentDark
import com.janreins.audiobook.ui.theme.WoodAccentLight
import com.janreins.audiobook.ui.theme.WoodGlowDark
import com.janreins.audiobook.ui.theme.WoodLabelDark
import com.janreins.audiobook.ui.theme.WoodLabelLight
import com.janreins.audiobook.ui.theme.WoodPlankBottomDark
import com.janreins.audiobook.ui.theme.WoodPlankBottomLight
import com.janreins.audiobook.ui.theme.WoodPlankTopDark
import com.janreins.audiobook.ui.theme.WoodPlankTopLight
import com.janreins.audiobook.ui.theme.WoodWallDark
import com.janreins.audiobook.ui.theme.WoodWallLight
import kotlin.math.roundToInt

@Immutable
data class ShelfColors(
    val wall: Color,
    val glow: Color,
    val plankTop: Color,
    val plankBottom: Color,
    val label: Color,
    val accent: Color
)

/** Day or night wood, following the app theme the person chose (not only the phone's setting). */
@Composable
fun shelfColors(): ShelfColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        ShelfColors(WoodWallDark, WoodGlowDark, WoodPlankTopDark, WoodPlankBottomDark, WoodLabelDark, WoodAccentDark)
    } else {
        ShelfColors(WoodWallLight, Color.Transparent, WoodPlankTopLight, WoodPlankBottomLight, WoodLabelLight, WoodAccentLight)
    }

/** What a spine needs to know about its book. [progress] is 0..1 and already includes the live position. */
data class SpineState(val progress: Float, val started: Boolean, val finished: Boolean)

private val ShelfSidePadding = 16.dp
private val SpineInset = 6.dp
private const val SPINE_GAP_DP = 3

private val DisplayCoverSize = 100.dp

/**
 * The library as a wooden bookcase: one shelf each for Continue listening, Up next and Finished.
 * The books you're listening to stand face-out on the top shelf (it scrolls sideways when they don't fit);
 * every other book stands spine-out. Only the shelf rows on screen are drawn, so big libraries stay smooth.
 */
@Composable
fun Bookshelf(
    sections: List<ShelfSection>,
    stateOf: (Audiobook) -> SpineState,
    currentBookId: String?,
    isPlaying: Boolean,
    onBookClick: (Audiobook) -> Unit,
    onMarkFinished: (Audiobook) -> Unit,
    onMarkUnplayed: (Audiobook) -> Unit,
    modifier: Modifier = Modifier,
    colors: ShelfColors = shelfColors()
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // A warm lamp glow at the top of the bookcase (night only; transparent by day).
                if (colors.glow.alpha > 0f && size.width > 0f) {
                    drawRect(Brush.radialGradient(listOf(colors.glow, Color.Transparent), Offset(size.width / 2f, 0f), size.width))
                }
            }
            .testTag("bookshelf")
    ) {
        val rowWidth = (maxWidth - ShelfSidePadding * 2 - SpineInset * 2).value.toInt()
        val shelves = remember(sections, rowWidth) {
            sections.map { section ->
                if (section.shelf.isDisplayShelf()) {
                    section to listOf(section.books)
                } else {
                    val widths = section.books.map { ShelfLayout.spineWidthDp(it.durationMs) }
                    section to ShelfLayout.packRows(widths, rowWidth, SPINE_GAP_DP).map { section.books.slice(it) }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ShelfSidePadding, end = ShelfSidePadding, top = 4.dp, bottom = 24.dp)
        ) {
            shelves.forEach { (section, rows) ->
                item(key = "shelf_${section.shelf}", contentType = "header") {
                    ShelfHeader(section, colors)
                }
                rows.forEachIndexed { index, books ->
                    val display = section.shelf.isDisplayShelf()
                    item(key = "shelf_${section.shelf}_$index", contentType = if (display) "display" else "row") {
                        if (display) {
                            DisplayShelf(books, stateOf, currentBookId, isPlaying, colors, onBookClick, onMarkFinished, onMarkUnplayed)
                        } else {
                            ShelfRow(books, stateOf, currentBookId, isPlaying, colors, onBookClick, onMarkFinished, onMarkUnplayed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfHeader(section: ShelfSection, colors: ShelfColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 18.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = section.shelf.label(),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.label,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${section.books.size}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.label.copy(alpha = 0.7f),
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = if (section.books.size == 1) "1 audiobook" else "${section.books.size} audiobooks"
            }
        )
    }
}

@Composable
private fun ShelfRow(
    books: List<Audiobook>,
    stateOf: (Audiobook) -> SpineState,
    currentBookId: String?,
    isPlaying: Boolean,
    colors: ShelfColors,
    onBookClick: (Audiobook) -> Unit,
    onMarkFinished: (Audiobook) -> Unit,
    onMarkUnplayed: (Audiobook) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Room above the spines for bookmark ribbons and the pulled-out playing book.
                .padding(start = SpineInset, end = SpineInset, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(SPINE_GAP_DP.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            books.forEach { book ->
                key(book.id) {
                    BookSpine(
                        book = book,
                        state = stateOf(book),
                        isCurrent = book.id == currentBookId,
                        isPlaying = isPlaying,
                        accent = colors.accent,
                        onClick = { onBookClick(book) },
                        onMarkFinished = { onMarkFinished(book) },
                        onMarkUnplayed = { onMarkUnplayed(book) }
                    )
                }
            }
        }
        Plank(colors)
    }
}

/** The top shelf: books you're listening to, covers facing out like a bookshop display. */
@Composable
private fun DisplayShelf(
    books: List<Audiobook>,
    stateOf: (Audiobook) -> SpineState,
    currentBookId: String?,
    isPlaying: Boolean,
    colors: ShelfColors,
    onBookClick: (Audiobook) -> Unit,
    onMarkFinished: (Audiobook) -> Unit,
    onMarkUnplayed: (Audiobook) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("display_shelf"),
            contentPadding = PaddingValues(start = SpineInset, end = SpineInset, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            items(books, key = { it.id }) { book ->
                FaceOutBook(
                    book = book,
                    state = stateOf(book),
                    isCurrent = book.id == currentBookId,
                    isPlaying = isPlaying,
                    accent = colors.accent,
                    onClick = { onBookClick(book) },
                    onMarkFinished = { onMarkFinished(book) },
                    onMarkUnplayed = { onMarkUnplayed(book) }
                )
            }
        }
        Plank(colors)
    }
}

@Composable
fun FaceOutBook(
    book: Audiobook,
    state: SpineState,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onMarkFinished: () -> Unit,
    onMarkUnplayed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(DisplayCoverSize)
                .shadow(6.dp, RoundedCornerShape(3.dp))
                .shelfBookActions(book, state, isCurrent, isPlaying, onClick, onMarkFinished, onMarkUnplayed) { showMenu = true }
                .testTag("book_face_${book.id.hashCode()}")
        ) {
            BookCover(book = book, modifier = Modifier.fillMaxSize(), cornerRadius = 3.dp, showPlayingOverlay = isCurrent)
            if (state.progress > 0f && !state.finished) {
                // How far through: a bar along the bottom of the cover.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(accent)
                    )
                }
            }
        }
        ShelfBookMenu(showMenu, state, onDismiss = { showMenu = false }, onMarkFinished, onMarkUnplayed)
    }
}

@Composable
private fun Plank(colors: ShelfColors) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .background(Brush.verticalGradient(listOf(colors.plankTop, colors.plankBottom)), RoundedCornerShape(2.dp))
            .drawBehind {
                // Light front edge and a faint wood grain.
                drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                val step = 11.dp.toPx()
                var x = step / 2f
                while (x < size.width) {
                    drawLine(Color.Black.copy(alpha = 0.07f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    x += step
                }
            }
    )
    // The plank's shadow on the wall below it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent)))
    )
}

private val RibbonShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(size.width / 2f, size.height * 0.75f)
    lineTo(0f, size.height)
    close()
}

@Composable
fun BookSpine(
    book: Audiobook,
    state: SpineState,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onMarkFinished: () -> Unit,
    onMarkUnplayed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val base = Color(CoverPlaceholders.PALETTE[CoverPlaceholders.colorIndex(book.id, CoverPlaceholders.PALETTE.size)])

    // The book that's loaded in the player sticks out a little above the others.
    Box(modifier = modifier.offset(y = if (isCurrent) (-8).dp else 0.dp)) {
        Box(
            modifier = Modifier
                .size(ShelfLayout.spineWidthDp(book.durationMs).dp, ShelfLayout.spineHeightDp(book.id).dp)
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
                // Lighter on the left, darker on the right, so the spine looks round.
                .background(
                    Brush.horizontalGradient(
                        0f to lerp(base, Color.White, 0.14f),
                        0.35f to base,
                        1f to lerp(base, Color.Black, 0.28f)
                    )
                )
                .shelfBookActions(book, state, isCurrent, isPlaying, onClick, onMarkFinished, onMarkUnplayed) { showMenu = true }
                .testTag("book_spine_${book.id.hashCode()}")
        ) {
            SpineBandLine(Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
            SpineBandLine(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
            Text(
                text = book.title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 0.3.sp
                ),
                color = SpineTitle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 18.dp, bottom = if (isCurrent) 34.dp else 18.dp)
                    .readTopToBottom()
            )
            if (state.progress > 0f && !state.finished) {
                // How far through: a line up the left edge of the spine.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .width(3.dp)
                        .fillMaxHeight(state.progress.coerceIn(0f, 1f))
                        .background(accent)
                )
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Default.Equalizer,
                    contentDescription = null,
                    tint = SpineTitle,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 15.dp)
                        .size(14.dp)
                )
            }
        }
        if (state.started && !state.finished) {
            // A bookmark ribbon over the top of a book you've started.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-6).dp)
                    .size(6.dp, 15.dp)
                    .background(accent, RibbonShape)
            )
        }
        ShelfBookMenu(showMenu, state, onDismiss = { showMenu = false }, onMarkFinished, onMarkUnplayed)
    }
}

/** Read out as, for example, "Quiet Harbour, 71% listened" or "The Salt Road, 42% listened, playing". */
internal fun shelfBookDescription(book: Audiobook, state: SpineState, isCurrent: Boolean, isPlaying: Boolean): String =
    buildString {
        append(book.title)
        append(
            when {
                state.finished -> ", finished"
                state.started -> ", ${(state.progress * 100).roundToInt()}% listened"
                else -> ", not started"
            }
        )
        if (isCurrent) append(if (isPlaying) ", playing" else ", paused")
    }

/** Tap plays; press and hold opens the menu. TalkBack gets the same two menu actions. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.shelfBookActions(
    book: Audiobook,
    state: SpineState,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMarkFinished: () -> Unit,
    onMarkUnplayed: () -> Unit,
    onLongClick: () -> Unit
): Modifier = this
    .combinedClickable(onClickLabel = "Play", onLongClickLabel = "Options", onClick = onClick, onLongClick = onLongClick)
    .clearAndSetSemantics {
        contentDescription = shelfBookDescription(book, state, isCurrent, isPlaying)
        customActions = buildList {
            if (!state.finished) add(CustomAccessibilityAction("Mark as finished") { onMarkFinished(); true })
            if (state.finished || state.started) add(CustomAccessibilityAction("Mark as unplayed") { onMarkUnplayed(); true })
        }
    }

@Composable
private fun ShelfBookMenu(
    expanded: Boolean,
    state: SpineState,
    onDismiss: () -> Unit,
    onMarkFinished: () -> Unit,
    onMarkUnplayed: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Only offer what would change something.
        if (!state.finished) {
            DropdownMenuItem(text = { Text("Mark as finished") }, onClick = { onDismiss(); onMarkFinished() })
        }
        if (state.finished || state.started) {
            DropdownMenuItem(text = { Text("Mark as unplayed") }, onClick = { onDismiss(); onMarkUnplayed() })
        }
    }
}

private fun Shelf.isDisplayShelf(): Boolean = this == Shelf.CONTINUE_LISTENING

@Composable
private fun SpineBandLine(modifier: Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(2.dp).background(SpineBand))
}

/** Turns a one-line text on its side so it reads top to bottom, like the title on a book's spine. */
private fun Modifier.readTopToBottom(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = -(placeable.width / 2 - placeable.height / 2),
            y = -(placeable.height / 2 - placeable.width / 2)
        ) { rotationZ = 90f }
    }
}

fun Shelf.label(): String = when (this) {
    Shelf.CONTINUE_LISTENING -> "Continue listening"
    Shelf.UP_NEXT -> "Up next"
    Shelf.FINISHED -> "Finished"
}
