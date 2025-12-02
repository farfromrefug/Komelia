package io.github.snd_r.komelia.ui.series

import androidx.compose.ui.unit.Dp
import io.github.snd_r.komelia.AppNotifications
import io.github.snd_r.komelia.server.KomgaTypeConverters
import io.github.snd_r.komelia.server.MediaServer
import io.github.snd_r.komelia.server.ServerId
import io.github.snd_r.komelia.settings.CommonSettingsRepository
import io.github.snd_r.komelia.ui.LoadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import snd.komga.client.book.KomgaBook
import snd.komga.client.series.KomgaSeries

/**
 * OPDS-specific books state that uses MediaServer instead of Komga clients.
 * This is a simplified version without advanced filtering since OPDS doesn't support it.
 */
class OpdsSeriesBooksState(
    private val series: StateFlow<KomgaSeries?>,
    private val settingsRepository: CommonSettingsRepository,
    private val notifications: AppNotifications,
    private val mediaServer: MediaServer,
    private val screenModelScope: CoroutineScope,
    val cardWidth: StateFlow<Dp>,
) {
    data class BooksData(
        val books: List<KomgaBook> = emptyList(),
        val pageSize: Int = 20,
        val totalPages: Int = 1,
        val currentPage: Int = 1,
        val layout: BooksLayout = BooksLayout.GRID,
        val selectionMode: Boolean = false,
        val selectedBooks: List<KomgaBook> = emptyList(),
    )

    private val mutableState = MutableStateFlow<LoadState<BooksData>>(LoadState.Uninitialized)
    val state = mutableState.asStateFlow()

    suspend fun initialize() {
        if (state.value != LoadState.Uninitialized) return
        loadBookData(1)
    }

    suspend fun reload() {
        when (val currentState = state.value) {
            is LoadState.Success<BooksData> -> loadBookData(currentState.value.currentPage)
            else -> loadBookData(1)
        }
    }

    private suspend fun loadBookData(page: Int) {
        notifications.runCatchingToNotifications {
            val currentState = state.value
            val pageLoadSize = when (currentState) {
                is LoadState.Success<BooksData> -> {
                    currentState.value.pageSize
                }

                else -> {
                    mutableState.value = LoadState.Loading
                    settingsRepository.getBookPageLoadSize().first()
                }
            }

            val seriesValue = series.filterNotNull().first()
            val seriesId = ServerId(seriesValue.id.value)
            
            val pageResponse = mediaServer.getBooksInSeries(
                seriesId = seriesId,
                page = page - 1,
                pageSize = pageLoadSize
            )

            val books = pageResponse.content.map { KomgaTypeConverters.serverBookToKomgaBook(it) }
            
            val newState = when (currentState) {
                is LoadState.Success<BooksData> -> currentState.value.copy(
                    books = books,
                    pageSize = pageLoadSize,
                    totalPages = pageResponse.totalPages,
                    currentPage = pageResponse.pageNumber + 1,
                )

                else -> BooksData(
                    books = books,
                    pageSize = pageLoadSize,
                    totalPages = pageResponse.totalPages,
                    currentPage = pageResponse.pageNumber + 1,
                    layout = settingsRepository.getBookListLayout().first(),
                    selectionMode = false,
                    selectedBooks = emptyList()
                )
            }
            mutableState.value = LoadState.Success(newState)
        }.onFailure { mutableState.value = LoadState.Error(it) }
    }

    fun onBookPageSizeChange(pageSize: Int) {
        updateCurrentState { it.copy(pageSize = pageSize) }
        screenModelScope.launch {
            settingsRepository.putBookPageLoadSize(pageSize)
            loadBookData(1)
        }
    }

    fun onPageChange(page: Int) {
        screenModelScope.launch {
            setSelectionMode(false)
            loadBookData(page)
        }
    }

    fun onBookLayoutChange(layout: BooksLayout) {
        updateCurrentState { it.copy(layout = layout) }
        screenModelScope.launch { settingsRepository.putBookListLayout(layout) }
    }

    fun setSelectionMode(editMode: Boolean) {
        updateCurrentState {
            it.copy(
                selectionMode = editMode,
                selectedBooks = if (!editMode) emptyList() else it.selectedBooks
            )
        }
    }

    fun onBookSelect(book: KomgaBook) {
        val currState = state.value
        if (currState !is LoadState.Success<BooksData>) return
        val currentlySelected = currState.value.selectedBooks

        if (currentlySelected.any { it.id == book.id }) {
            val selection = currentlySelected.filter { it.id != book.id }
            updateCurrentState { state ->
                state.copy(
                    selectedBooks = currentlySelected.filter { it.id != book.id },
                    selectionMode = selection.isNotEmpty()
                )
            }
        } else updateCurrentState { state ->
            state.copy(
                selectedBooks = state.selectedBooks + book,
                selectionMode = true
            )
        }
    }

    // No-op for OPDS
    fun stopKomgaEventHandler() {}
    fun startKomgaEventHandler() {}

    private fun updateCurrentState(transform: (settings: BooksData) -> BooksData) {
        mutableState.update {
            when (it) {
                is LoadState.Success<BooksData> -> LoadState.Success(transform(it.value))
                else -> it
            }
        }
    }
}
