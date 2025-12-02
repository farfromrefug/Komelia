package io.github.snd_r.komelia.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.snd_r.komelia.AppNotifications
import io.github.snd_r.komelia.server.MediaServer
import io.github.snd_r.komelia.server.ServerBook
import io.github.snd_r.komelia.server.ServerSeries
import io.github.snd_r.komelia.server.ServerId
import io.github.snd_r.komelia.ui.LoadState
import io.github.snd_r.komelia.ui.LoadState.Uninitialized
import io.github.snd_r.komelia.ui.common.cards.defaultCardWidth
import io.github.snd_r.komelia.ui.common.menus.BookMenuActions
import io.github.snd_r.komelia.ui.common.menus.SeriesMenuActions
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import snd.komga.client.book.KomgaBook
import snd.komga.client.book.KomgaBookClient
import snd.komga.client.book.KomgaBookId
import snd.komga.client.book.KomgaBookMetadata
import snd.komga.client.book.KomgaBookReadProgress
import snd.komga.client.book.KomgaBookSearch
import snd.komga.client.book.KomgaMedia
import snd.komga.client.book.KomgaMediaStatus
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeries
import snd.komga.client.series.KomgaSeriesClient
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.series.KomgaSeriesMetadata
import snd.komga.client.series.KomgaSeriesSearch
import snd.komga.client.series.KomgaSeriesStatus
import snd.komga.client.sse.KomgaEvent
import snd.komga.client.sse.KomgaEvent.BookEvent
import snd.komga.client.sse.KomgaEvent.ReadProgressEvent
import snd.komga.client.sse.KomgaEvent.ReadProgressSeriesEvent
import snd.komga.client.sse.KomgaEvent.SeriesEvent

class HomeViewModel(
    private val seriesClient: KomgaSeriesClient?,
    private val bookClient: KomgaBookClient?,
    private val appNotifications: AppNotifications,
    private val komgaEvents: SharedFlow<KomgaEvent>?,
    private val filterRepository: HomeScreenFilterRepository,
    cardWidthFlow: Flow<Dp>,
    private val mediaServer: MediaServer? = null,
    private val isOpdsMode: Boolean = false,
) : StateScreenModel<LoadState<Unit>>(Uninitialized) {
    val cardWidth = cardWidthFlow.stateIn(screenModelScope, Eagerly, defaultCardWidth.dp)

    private val reloadEventsEnabled = MutableStateFlow(true)
    private val reloadJobsFlow = MutableSharedFlow<Unit>(1, 0, DROP_OLDEST)

    val filters = MutableStateFlow(emptyList<HomeFilterData>())
    val activeFilterNumber = MutableStateFlow(0)

    suspend fun initialize() {
        if (state.value !is Uninitialized) return

        load()
        
        // Only start Komga event listener if not in OPDS mode
        if (!isOpdsMode && komgaEvents != null) {
            startKomgaEventListener()
        }

        reloadJobsFlow.onEach {
            reloadEventsEnabled.first { it }
            load()
            delay(5000)
        }.launchIn(screenModelScope)
    }

    fun reload() {
        screenModelScope.launch { load() }
    }

    private suspend fun load() {
        appNotifications.runCatchingToNotifications {
            mutableState.value = LoadState.Loading

            filters.value = filterRepository.getFilters().first()
                .mapNotNull { fetchFilterData(it) }

            mutableState.value = LoadState.Success(Unit)

        }.onFailure { mutableState.value = LoadState.Error(it) }
    }

    private suspend fun fetchFilterData(filter: HomeScreenFilter): HomeFilterData? {
        return if (isOpdsMode && mediaServer != null) {
            fetchOpdsFilterData(filter)
        } else {
            fetchKomgaFilterData(filter)
        }
    }
    
    private suspend fun fetchKomgaFilterData(filter: HomeScreenFilter): HomeFilterData? {
        val bookClient = this.bookClient ?: return null
        val seriesClient = this.seriesClient ?: return null
        
        return when (filter) {
            is BooksHomeScreenFilter.CustomFilter -> {
                val books = bookClient.getBookList(
                    search = KomgaBookSearch(filter.filter, filter.textSearch),
                    pageRequest = filter.pageRequest
                ).content

                BookFilterData(books = books, filter = filter)
            }

            is BooksHomeScreenFilter.OnDeck -> {
                val books = bookClient.getBooksOnDeck(pageRequest = KomgaPageRequest(size = filter.pageSize)).content
                BookFilterData(books, filter)
            }

            is SeriesHomeScreenFilter.CustomFilter -> {
                val series = seriesClient.getSeriesList(
                    search = KomgaSeriesSearch(filter.filter, filter.textSearch),
                    pageRequest = filter.pageRequest
                ).content

                SeriesFilterData(series = series, filter = filter)
            }

            is SeriesHomeScreenFilter.RecentlyAdded -> {
                val series = seriesClient.getNewSeries(
                    oneshot = false,
                    pageRequest = KomgaPageRequest(size = filter.pageSize)
                ).content
                SeriesFilterData(
                    series = series,
                    filter = filter
                )
            }

            is SeriesHomeScreenFilter.RecentlyUpdated -> {
                val series = seriesClient.getUpdatedSeries(
                    oneshot = false,
                    pageRequest = KomgaPageRequest(size = filter.pageSize)
                ).content
                SeriesFilterData(
                    series = series,
                    filter = filter
                )
            }
        }
    }
    
    private suspend fun fetchOpdsFilterData(filter: HomeScreenFilter): HomeFilterData? {
        val server = mediaServer ?: return null
        
        return when (filter) {
            is BooksHomeScreenFilter.CustomFilter -> {
                // OPDS doesn't support custom filters, return empty or search results
                val searchTerm = filter.textSearch
                if (searchTerm != null) {
                    val result = server.searchBooks(searchTerm, 0, filter.pageSize)
                    BookFilterData(books = result.content.map { serverBookToKomgaBook(it) }, filter = filter)
                } else {
                    BookFilterData(books = emptyList(), filter = filter)
                }
            }

            is BooksHomeScreenFilter.OnDeck -> {
                // OPDS typically doesn't support "on deck" - return in-progress books or empty
                val result = server.getInProgressBooks(0, filter.pageSize)
                BookFilterData(result.content.map { serverBookToKomgaBook(it) }, filter)
            }

            is SeriesHomeScreenFilter.CustomFilter -> {
                // OPDS doesn't support custom filters, return empty or search results
                val searchTerm = filter.textSearch
                if (searchTerm != null) {
                    val result = server.searchSeries(searchTerm, 0, filter.pageSize)
                    SeriesFilterData(series = result.content.map { serverSeriesToKomgaSeries(it) }, filter = filter)
                } else {
                    SeriesFilterData(series = emptyList(), filter = filter)
                }
            }

            is SeriesHomeScreenFilter.RecentlyAdded -> {
                val result = server.getRecentlyAddedSeries(0, filter.pageSize)
                SeriesFilterData(
                    series = result.content.map { serverSeriesToKomgaSeries(it) },
                    filter = filter
                )
            }

            is SeriesHomeScreenFilter.RecentlyUpdated -> {
                // OPDS doesn't distinguish recently added vs updated, use same endpoint
                val result = server.getRecentlyAddedSeries(0, filter.pageSize)
                SeriesFilterData(
                    series = result.content.map { serverSeriesToKomgaSeries(it) },
                    filter = filter
                )
            }
        }
    }
    
    // Conversion helpers to map Server types to Komga types for UI compatibility
    private fun serverBookToKomgaBook(book: ServerBook): KomgaBook {
        return KomgaBook(
            id = KomgaBookId(book.id.value),
            seriesId = KomgaSeriesId(book.seriesId.value),
            seriesTitle = "",
            libraryId = KomgaLibraryId(book.libraryId.value),
            name = book.name,
            number = book.sortNumber?.toInt() ?: 0,
            url = book.thumbnailUrl ?: "",
            sizeBytes = book.fileSize,
            size = "${book.fileSize / 1024}KB",
            media = KomgaMedia(
                status = KomgaMediaStatus.READY,
                mediaType = book.mediaType ?: "",
                pagesCount = book.pageCount,
                comment = "",
                epubDivinaCompatible = false
            ),
            metadata = KomgaBookMetadata(
                title = book.metadata.title,
                titleLock = false,
                summary = book.metadata.summary ?: "",
                summaryLock = false,
                number = book.metadata.number ?: "",
                numberLock = false,
                numberSort = book.sortNumber ?: 0.0,
                numberSortLock = false,
                releaseDate = null,
                releaseDateLock = false,
                authors = book.metadata.authors.map { 
                    snd.komga.client.book.KomgaAuthor(it.name, it.role) 
                },
                authorsLock = false,
                tags = book.metadata.tags.toSet(),
                tagsLock = false,
                isbn = "",
                isbnLock = false,
                links = book.metadata.links.map { 
                    snd.komga.client.book.KomgaWebLink(it.label, it.url) 
                },
                linksLock = false,
                created = book.created,
                lastModified = book.lastModified
            ),
            readProgress = book.readProgress?.let {
                KomgaBookReadProgress(
                    page = it.page,
                    completed = it.isCompleted,
                    readDate = it.readDate,
                    created = it.readDate,
                    lastModified = it.lastReadDate,
                    deviceId = "",
                    deviceName = ""
                )
            },
            deleted = false,
            fileHash = "",
            fileLastModified = book.lastModified,
            oneshot = false
        )
    }
    
    private fun serverSeriesToKomgaSeries(series: ServerSeries): KomgaSeries {
        return KomgaSeries(
            id = KomgaSeriesId(series.id.value),
            libraryId = KomgaLibraryId(series.libraryId.value),
            name = series.name,
            url = series.thumbnailUrl ?: "",
            booksCount = series.booksCount,
            booksReadCount = series.booksReadCount,
            booksUnreadCount = series.booksUnreadCount,
            booksInProgressCount = series.booksInProgressCount,
            created = series.created,
            lastModified = series.lastModified,
            fileLastModified = series.lastModified,
            deleted = false,
            oneshot = false,
            metadata = KomgaSeriesMetadata(
                title = series.metadata.title,
                titleLock = false,
                titleSort = series.metadata.sortTitle ?: series.metadata.title,
                titleSortLock = false,
                summary = series.metadata.summary ?: "",
                summaryLock = false,
                status = when (series.status) {
                    io.github.snd_r.komelia.server.SeriesStatus.ENDED -> KomgaSeriesStatus.ENDED
                    io.github.snd_r.komelia.server.SeriesStatus.ONGOING -> KomgaSeriesStatus.ONGOING
                    io.github.snd_r.komelia.server.SeriesStatus.ABANDONED -> KomgaSeriesStatus.ABANDONED
                    io.github.snd_r.komelia.server.SeriesStatus.HIATUS -> KomgaSeriesStatus.HIATUS
                    io.github.snd_r.komelia.server.SeriesStatus.UNKNOWN -> KomgaSeriesStatus.ENDED
                },
                statusLock = false,
                readingDirection = null,
                readingDirectionLock = false,
                publisher = series.metadata.publisher ?: "",
                publisherLock = false,
                ageRating = null,
                ageRatingLock = false,
                language = series.metadata.language ?: "",
                languageLock = false,
                genres = series.metadata.genres.toSet(),
                genresLock = false,
                tags = series.metadata.tags.toSet(),
                tagsLock = false,
                totalBookCount = series.metadata.totalBookCount,
                totalBookCountLock = false,
                sharingLabels = emptySet(),
                sharingLabelsLock = false,
                links = emptyList(),
                linksLock = false,
                alternateTitles = emptyList(),
                alternateTitlesLock = false,
                created = series.created,
                lastModified = series.lastModified
            )
        )
    }

    fun seriesMenuActions(): SeriesMenuActions? {
        val client = seriesClient ?: return null
        return SeriesMenuActions(client, appNotifications, screenModelScope)
    }
    
    fun bookMenuActions(): BookMenuActions? {
        val client = bookClient ?: return null
        return BookMenuActions(client, appNotifications, screenModelScope)
    }

    fun stopKomgaEventsHandler() {
        reloadEventsEnabled.value = false
    }

    fun startKomgaEventsHandler() {
        reloadEventsEnabled.value = true
    }

    private fun startKomgaEventListener() {
        val events = komgaEvents ?: return
        events.onEach { event ->
            when (event) {
                is BookEvent -> {
                    reloadJobsFlow.tryEmit(Unit)
                }

                is SeriesEvent -> {
                    reloadJobsFlow.tryEmit(Unit)
                }

                is ReadProgressEvent -> {
                    val matches = false
                    if (matches) reloadJobsFlow.tryEmit(Unit)

                }

                is ReadProgressSeriesEvent -> {
                    val matches = false
                    if (matches) reloadJobsFlow.tryEmit(Unit)
                }

                else -> {}
            }
        }.launchIn(screenModelScope)
    }

    fun onFilterChange(number: Int) {
        this.activeFilterNumber.value = number
    }

}
