package io.github.snd_r.komelia.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.snd_r.komelia.AppNotifications
import io.github.snd_r.komelia.server.MediaServer
import io.github.snd_r.komelia.server.ServerBook
import io.github.snd_r.komelia.server.ServerSeries
import io.github.snd_r.komelia.ui.search.SearchResults
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
import snd.komga.client.library.KomgaLibrary
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeries
import snd.komga.client.series.KomgaSeriesClient
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.series.KomgaSeriesMetadata
import snd.komga.client.series.KomgaSeriesSearch
import snd.komga.client.series.KomgaSeriesStatus
import io.github.snd_r.komelia.server.SeriesStatus as ServerSeriesStatus

@OptIn(FlowPreview::class)
class SearchBarState(
    private val seriesClient: KomgaSeriesClient?,
    private val bookClient: KomgaBookClient?,
    private val appNotifications: AppNotifications,
    private val libraries: StateFlow<List<KomgaLibrary>>,
    private val mediaServer: MediaServer? = null,
    private val isOpdsMode: Boolean = false,
) : ScreenModel {

    private var currentQuery by mutableStateOf("")

    var series by mutableStateOf<List<KomgaSeries>>(emptyList())
    var books by mutableStateOf<List<KomgaBook>>(emptyList())
    var isLoading by mutableStateOf(false)

    init {
        screenModelScope.launch {
            snapshotFlow { currentQuery }
                .debounce {
                    if (it.isBlank()) 0
                    else 500
                }
                .distinctUntilChanged()
                .collect { handleQuery(it) }
        }
    }

    private suspend fun handleQuery(query: String) {
        appNotifications.runCatchingToNotifications {
            isLoading = true

            if (query.isBlank()) {
                series = emptyList()
                books = emptyList()
            } else if (isOpdsMode && mediaServer != null) {
                // Use MediaServer for OPDS mode
                val seriesResult = mediaServer.searchSeries(query, 0, 10)
                series = seriesResult.content.map { serverSeriesToKomgaSeries(it) }

                val booksResult = mediaServer.searchBooks(query, 0, 10)
                books = booksResult.content.map { serverBookToKomgaBook(it) }
            } else if (seriesClient != null && bookClient != null) {
                // Use Komga clients for Komga mode
                series = seriesClient.getSeriesList(
                    KomgaSeriesSearch(fullTextSearch = query),
                    pageRequest = KomgaPageRequest(size = 10)
                ).content

                books = bookClient.getBookList(
                    KomgaBookSearch(fullTextSearch = query),
                    KomgaPageRequest(size = 10)
                ).content
            }

            isLoading = false
        }.onFailure { isLoading = false }
    }

    fun currentQuery(): String = currentQuery

    fun onQueryChange(newQuery: String) {
        currentQuery = newQuery
    }

    fun getLibraryById(id: KomgaLibraryId): KomgaLibrary? {
        return libraries.value.firstOrNull { it.id == id }
    }

    fun searchResults() = SearchResults(series, books)
    
    // Conversion helpers for OPDS mode
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
                    ServerSeriesStatus.ENDED -> KomgaSeriesStatus.ENDED
                    ServerSeriesStatus.ONGOING -> KomgaSeriesStatus.ONGOING
                    ServerSeriesStatus.ABANDONED -> KomgaSeriesStatus.ABANDONED
                    ServerSeriesStatus.HIATUS -> KomgaSeriesStatus.HIATUS
                    ServerSeriesStatus.UNKNOWN -> KomgaSeriesStatus.ENDED
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
}
