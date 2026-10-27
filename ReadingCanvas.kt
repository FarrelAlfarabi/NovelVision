package com.novelvision.feature.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

// ===========================================================================
// 1. What the list shows
// ===========================================================================

/**
 * Every row in the list is one of these.
 * KEY RULE: keys are built from stable things (paragraph index, anchor hash),
 * never from the row's position. That is how Compose keeps your place
 * when rows appear above the screen.
 */
sealed interface ReaderItem {
    val key: String
    val paragraphIndex: Int

    data class Text(
        override val paragraphIndex: Int,
        val text: String
    ) : ReaderItem {
        override val key: String get() = "p-$paragraphIndex"
    }

    /** A reserved slot. It exists from the start, even before any image does. */
    data class Illustration(
        override val paragraphIndex: Int,
        val anchorHash: String
    ) : ReaderItem {
        override val key: String get() = "img-$anchorHash"
    }
}

sealed interface IllustrationState {
    data object Pending : IllustrationState
    data object Generating : IllustrationState
    data class Ready(val imageUri: String) : IllustrationState
    data object Failed : IllustrationState
}

// ===========================================================================
// 2. The reader
// ===========================================================================

private const val SLOT_ASPECT_RATIO = 16f / 9f
private const val RANGE_SETTLE_MS = 300L

/**
 * WHY THIS DOES NOT JUMP:
 *  1. The image slot has a FIXED shape (16:9) from the first frame.
 *     When the image arrives, the slot does not change size, so nothing below it moves.
 *  2. Rows have stable keys, so LazyColumn re-anchors on the row you are reading.
 *  3. Progress is saved as PARAGRAPH index, not list index. List indexes shift; paragraphs do not.
 *  4. Image state is passed in as a Map keyed by anchorHash. A state change recomposes only
 *     the visible slots (cheap: unchanged slots are skipped), and text rows are never touched.
 *
 * Keys MUST be unique. Two items with the same key crash LazyColumn. That is why anchorHash
 * has to be unique per book (see ParagraphHasher.anchorKeys).
 *
 * While `items` is empty nothing is composed. The scroll state is created only when the first
 * non-empty list arrives, so a book loaded from Room still opens at the saved place.
 */
@Composable
fun ReadingCanvasView(
    items: List<ReaderItem>,
    illustrationStates: Map<String, IllustrationState>,
    initialParagraphIndex: Int,
    initialScrollOffsetPx: Int,
    onProgress: (paragraphIndex: Int, offsetPx: Int) -> Unit,
    onVisibleRangeChanged: (firstItem: Int, lastItem: Int) -> Unit,
    onRetryIllustration: (anchorHash: String) -> Unit,
    modifier: Modifier = Modifier,
    imageContent: @Composable (uri: String, modifier: Modifier) -> Unit = { uri, mod ->
        CoilIllustration(uri, mod)
    }
) {
    if (items.isEmpty()) return
    ReadingCanvasContent(
        items, illustrationStates, initialParagraphIndex, initialScrollOffsetPx,
        onProgress, onVisibleRangeChanged, onRetryIllustration, modifier, imageContent
    )
}

@OptIn(FlowPreview::class)
@Composable
private fun ReadingCanvasContent(
    items: List<ReaderItem>,
    illustrationStates: Map<String, IllustrationState>,
    initialParagraphIndex: Int,
    initialScrollOffsetPx: Int,
    onProgress: (paragraphIndex: Int, offsetPx: Int) -> Unit,
    onVisibleRangeChanged: (firstItem: Int, lastItem: Int) -> Unit,
    onRetryIllustration: (anchorHash: String) -> Unit,
    modifier: Modifier,
    imageContent: @Composable (uri: String, modifier: Modifier) -> Unit
) {
    // Work out the starting row ONCE. Later changes to `items` must not reset the scroll.
    // Only TEXT rows count: an illustration shares the paragraphIndex of the text before it,
    // so matching it would land one paragraph too early. Past-the-end goes to the last row.
    val startIndex = remember {
        items.indexOfFirst { it is ReaderItem.Text && it.paragraphIndex >= initialParagraphIndex }
            .takeIf { it >= 0 } ?: items.lastIndex
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = startIndex,
        initialFirstVisibleItemScrollOffset = initialScrollOffsetPx
    )

    // Always read the newest values inside long-running effects.
    val currentItems by rememberUpdatedState(items)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnRange by rememberUpdatedState(onVisibleRangeChanged)

    // Save position (debounced so we do not write to disk on every pixel).
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .debounce(500)
            .distinctUntilChanged()
            .collect { (index, offset) ->
                val item = currentItems.getOrNull(index) ?: return@collect
                when (item) {
                    is ReaderItem.Text -> currentOnProgress(item.paragraphIndex, offset)
                    // The image belongs AFTER its paragraph. Resume at the next paragraph.
                    is ReaderItem.Illustration -> currentOnProgress(item.paragraphIndex + 1, 0)
                }
            }
    }

    // Tell the ViewModel what is on screen so it can start work AHEAD of the reader.
    LaunchedEffect(listState) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) null else visible.first().index to visible.last().index
        }
            .filterNotNull()
            .distinctUntilChanged()
            // COST CONTROL: a fling passes dozens of slots in a second. Wait until the
            // reader slows down, or every slot they skip over becomes a paid image.
            .debounce(RANGE_SETTLE_MS)
            .collect { (first, last) -> currentOnRange(first, last) }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            items = items,
            key = { it.key },
            contentType = { item ->
                when (item) {
                    is ReaderItem.Text -> "text"
                    is ReaderItem.Illustration -> "illustration"
                }
            }
        ) { item ->
            when (item) {
                is ReaderItem.Text -> TextBlock(item.text)
                is ReaderItem.Illustration -> IllustrationSlot(
                    state = illustrationStates[item.anchorHash] ?: IllustrationState.Pending,
                    onRetry = { onRetryIllustration(item.anchorHash) },
                    imageContent = imageContent
                )
            }
        }
    }
}

@Composable
private fun TextBlock(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 18.sp,
            lineHeight = 28.sp
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

@Composable
private fun IllustrationSlot(
    state: IllustrationState,
    onRetry: () -> Unit,
    imageContent: @Composable (uri: String, modifier: Modifier) -> Unit
) {
    // Fixed shape. This line is what prevents layout jumps.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .aspectRatio(SLOT_ASPECT_RATIO)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // contentKey: switch on the KIND of state, so a new Ready uri does not re-fade.
        Crossfade(
            targetState = state,
            contentKey = { it::class },
            label = "illustration"
        ) { current ->
            when (current) {
                IllustrationState.Pending -> Text(
                    "Illustration coming up",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                IllustrationState.Generating -> CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp)
                )

                is IllustrationState.Ready -> imageContent(
                    current.imageUri,
                    Modifier.fillMaxSize()
                )

                IllustrationState.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Could not make this image",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Button(onClick = onRetry) { Text("Try again") }
                }
            }
        }
    }
}

/** Real image loader. Reads from local file URIs written by the image worker. */
@Composable
fun CoilIllustration(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(uri) {
        ImageRequest.Builder(context).data(uri).crossfade(true).build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

// ===========================================================================
// 3. Demo: runs with no network and no real images
//    The ViewModel pretends to generate images with random delays and some failures.
//    In the real app this becomes ObserveReaderItemsUseCase + a Room-backed Flow.
// ===========================================================================

private const val LOOK_AHEAD_ITEMS = 12
private const val SCENE_EVERY = 8

class DemoReaderViewModel : ViewModel() {

    val items: List<ReaderItem> = buildList {
        for (i in 0 until 160) {
            add(ReaderItem.Text(i, demoParagraph(i)))
            if ((i + 1) % SCENE_EVERY == 0) {
                add(ReaderItem.Illustration(paragraphIndex = i, anchorHash = "scene-$i"))
            }
        }
    }

    private val _states = MutableStateFlow<Map<String, IllustrationState>>(emptyMap())
    val states: StateFlow<Map<String, IllustrationState>> = _states.asStateFlow()

    // Guards against starting the same job twice while scrolling back and forth.
    private val started = mutableSetOf<String>()

    fun onVisibleRangeChanged(first: Int, last: Int) {
        val end = minOf(last + LOOK_AHEAD_ITEMS, items.lastIndex)
        for (i in first..end) {
            val item = items[i] as? ReaderItem.Illustration ?: continue
            if (started.add(item.anchorHash)) generate(item.anchorHash)
        }
    }

    fun retry(anchorHash: String) {
        started.add(anchorHash)
        generate(anchorHash)
    }

    private fun generate(anchorHash: String) {
        viewModelScope.launch {
            setState(anchorHash, IllustrationState.Generating)
            delay(1200L + Random.nextLong(1500))
            if (Random.nextInt(6) == 0) {
                setState(anchorHash, IllustrationState.Failed)
            } else {
                setState(anchorHash, IllustrationState.Ready("demo://$anchorHash"))
            }
        }
    }

    private fun setState(anchorHash: String, state: IllustrationState) {
        _states.update { it + (anchorHash to state) }
    }

    fun onProgress(paragraphIndex: Int, offsetPx: Int) {
        // Real app: SaveReadingProgressUseCase(bookId, paragraphIndex, offsetPx)
    }

    private fun demoParagraph(i: Int): String =
        "Paragraph $i. " + "The lantern light shook against the wet stone walls, " +
            "and somewhere far below, water kept dripping in a slow, patient rhythm. ".repeat(
                1 + (i % 3)
            )
}

/** Stand-in for Coil so the demo needs no image files. */
@Composable
private fun DemoImage(uri: String, modifier: Modifier) {
    val hue = ((uri.hashCode() and 0x7fffffff) % 360).toFloat()
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(Color.hsv(hue, 0.5f, 0.75f), Color.hsv((hue + 60f) % 360f, 0.6f, 0.45f))
            )
        ),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            uri.removePrefix("demo://"),
            color = Color.White,
            modifier = Modifier.padding(12.dp)
        )
    }
}

class DemoReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: DemoReaderViewModel = viewModel()
                val states by vm.states.collectAsStateWithLifecycle()
                Scaffold { padding ->
                    ReadingCanvasView(
                        items = vm.items,
                        illustrationStates = states,
                        initialParagraphIndex = 0,
                        initialScrollOffsetPx = 0,
                        onProgress = vm::onProgress,
                        onVisibleRangeChanged = vm::onVisibleRangeChanged,
                        onRetryIllustration = vm::retry,
                        modifier = Modifier.padding(padding),
                        imageContent = { uri, mod -> DemoImage(uri, mod) }
                    )
                }
            }
        }
    }
}
