package io.github.snd_r.komelia.server

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant
import snd.komga.client.book.KomgaBook
import snd.komga.client.book.KomgaBookClient
import snd.komga.client.book.KomgaBookId
import snd.komga.client.book.KomgaBookMetadata
import snd.komga.client.book.KomgaBookReadProgressUpdateRequest
import snd.komga.client.book.KomgaMediaStatus
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.library.KomgaLibrary
import snd.komga.client.library.KomgaLibraryClient
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeries
import snd.komga.client.series.KomgaSeriesClient
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.series.KomgaSeriesMetadata
import snd.komga.client.series.KomgaSeriesStatus
import snd.komga.client.user.KomgaUser
import snd.komga.client.user.KomgaUserClient

private val logger = KotlinLogging.logger {}

/**
 * MediaServer implementation that wraps the existing Komga client
 * 
 * This adapter maps Komga-specific types to the generic MediaServer interface,
 * allowing the application to use either Komga or OPDS backends through
 * the same abstraction layer.
 */
class KomgaMediaServer(
    private val baseUrl: String,
    private val userClient: KomgaUserClient,
    private val libraryClient: KomgaLibraryClient,
    private val seriesClient: KomgaSeriesClient,
    private val bookClient: KomgaBookClient
) : MediaServer, WriteableMediaServer {
    
    override val supportsWriteOperations: Boolean = true
    override val supportsRealTimeEvents: Boolean = true
    
    private var currentUser: KomgaUser? = null
    
    override suspend fun getServerInfo(): ServerInfo {
        return ServerInfo(
            type = ServerType.KOMGA,
            name = "Komga",
            version = null, // Could get from actuator if needed
            baseUrl = baseUrl
        )
    }
    
    override suspend fun getCurrentUser(): ServerUser {
        val user = currentUser ?: userClient.getMe().also { currentUser = it }
        return ServerUser(
            id = ServerId(user.id.value),
            email = user.email,
            isAdmin = user.roles.contains("ADMIN"),
            roles = user.roles
        )
    }
    
    override suspend fun getLibraries(): List<ServerLibrary> {
        return libraryClient.getLibraries().map { it.toServerLibrary() }
    }
    
    override suspend fun getLibrary(libraryId: ServerId): ServerLibrary {
        return libraryClient.getLibrary(KomgaLibraryId(libraryId.value)).toServerLibrary()
    }
    
    override suspend fun getSeriesInLibrary(
        libraryId: ServerId,
        page: Int,
        pageSize: Int,
        sort: ServerSort?,
        searchTerm: String?
    ): ServerPageResult<ServerSeries> {
        val pageRequest = KomgaPageRequest(
            pageIndex = page,
            size = pageSize,
            sort = sort?.toKomgaSort()
        )
        
        val query = snd.komga.client.series.KomgaSeriesQuery(
            libraryIds = listOf(KomgaLibraryId(libraryId.value)),
            searchTerm = searchTerm
        )
        
        @Suppress("DEPRECATION")
        val result = seriesClient.getAllSeries(
            query = query,
            pageRequest = pageRequest
        )
        
        return ServerPageResult(
            content = result.content.map { it.toServerSeries() },
            totalPages = result.totalPages,
            totalElements = result.totalElements,
            currentPage = result.number,
            pageSize = result.size,
            first = result.first,
            last = result.last,
            empty = result.empty
        )
    }
    
    override suspend fun getSeries(seriesId: ServerId): ServerSeries {
        return seriesClient.getOneSeries(KomgaSeriesId(seriesId.value)).toServerSeries()
    }
    
    override suspend fun getBooksInSeries(
        seriesId: ServerId,
        page: Int,
        pageSize: Int,
        sort: ServerSort?
    ): ServerPageResult<ServerBook> {
        val pageRequest = KomgaPageRequest(
            pageIndex = page,
            size = pageSize,
            sort = sort?.toKomgaSort()
        )
        
        val result = bookClient.getBookList(
            snd.komga.client.search.allOfBooks {
                seriesId { isEqualTo(KomgaSeriesId(seriesId.value)) }
            },
            pageRequest = pageRequest
        )
        
        return ServerPageResult(
            content = result.content.map { it.toServerBook() },
            totalPages = result.totalPages,
            totalElements = result.totalElements,
            currentPage = result.number,
            pageSize = result.size,
            first = result.first,
            last = result.last,
            empty = result.empty
        )
    }
    
    override suspend fun getBook(bookId: ServerId): ServerBook {
        return bookClient.getBook(KomgaBookId(bookId.value)).toServerBook()
    }
    
    override suspend fun getBookPages(bookId: ServerId): List<ServerBookPage> {
        return bookClient.getBookPages(KomgaBookId(bookId.value)).map { page ->
            ServerBookPage(
                number = page.number,
                fileName = page.fileName,
                mediaType = page.mediaType,
                width = page.width,
                height = page.height,
                fileSize = page.size
            )
        }
    }
    
    override fun getBookThumbnailUrl(bookId: ServerId): String {
        return "$baseUrl/api/v1/books/${bookId.value}/thumbnail"
    }
    
    override fun getSeriesThumbnailUrl(seriesId: ServerId): String {
        return "$baseUrl/api/v1/series/${seriesId.value}/thumbnail"
    }
    
    override fun getBookPageUrl(bookId: ServerId, pageNumber: Int): String {
        return "$baseUrl/api/v1/books/${bookId.value}/pages/$pageNumber"
    }
    
    override fun getBookDownloadUrl(bookId: ServerId): String {
        return "$baseUrl/api/v1/books/${bookId.value}/file"
    }
    
    override suspend fun searchSeries(
        searchTerm: String,
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerSeries> {
        val pageRequest = KomgaPageRequest(pageIndex = page, size = pageSize)
        val query = snd.komga.client.series.KomgaSeriesQuery(searchTerm = searchTerm)
        @Suppress("DEPRECATION")
        val result = seriesClient.getAllSeries(
            query = query,
            pageRequest = pageRequest
        )
        
        return ServerPageResult(
            content = result.content.map { it.toServerSeries() },
            totalPages = result.totalPages,
            totalElements = result.totalElements,
            currentPage = result.number,
            pageSize = result.size,
            first = result.first,
            last = result.last,
            empty = result.empty
        )
    }
    
    override suspend fun searchBooks(
        searchTerm: String,
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerBook> {
        val pageRequest = KomgaPageRequest(pageIndex = page, size = pageSize)
        val result = bookClient.getBookList(
            snd.komga.client.search.allOfBooks { },
            fullTextSearch = searchTerm,
            pageRequest = pageRequest
        )
        
        return ServerPageResult(
            content = result.content.map { it.toServerBook() },
            totalPages = result.totalPages,
            totalElements = result.totalElements,
            currentPage = result.number,
            pageSize = result.size,
            first = result.first,
            last = result.last,
            empty = result.empty
        )
    }
    
    override suspend fun getRecentlyAddedSeries(
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerSeries> {
        val pageRequest = KomgaPageRequest(pageIndex = page, size = pageSize)
        val result = seriesClient.getNewSeries(pageRequest = pageRequest)
        
        return ServerPageResult(
            content = result.content.map { it.toServerSeries() },
            totalPages = result.totalPages,
            totalElements = result.totalElements,
            currentPage = result.number,
            pageSize = result.size,
            first = result.first,
            last = result.last,
            empty = result.empty
        )
    }
    
    override suspend fun getRecentlyAddedBooks(
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerBook> {
        val pageRequest = KomgaPageRequest(pageIndex = page, size = pageSize)
        val result = bookClient.getLatestBooks(pageRequest = pageRequest)
        
        return ServerPageResult(
            content = result.content.map { it.toServerBook() },
            totalPages = result.totalPages,
            totalElements = result.totalElements,
            currentPage = result.number,
            pageSize = result.size,
            first = result.first,
            last = result.last,
            empty = result.empty
        )
    }
    
    override suspend fun getInProgressBooks(
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerBook> {
        val pageRequest = KomgaPageRequest(pageIndex = page, size = pageSize)
        val result = bookClient.getBooksOnDeck(pageRequest = pageRequest)
        
        return ServerPageResult(
            content = result.content.map { it.toServerBook() },
            totalPages = result.totalPages,
            totalElements = result.totalElements,
            currentPage = result.number,
            pageSize = result.size,
            first = result.first,
            last = result.last,
            empty = result.empty
        )
    }
    
    override suspend fun updateReadProgress(
        bookId: ServerId,
        page: Int,
        isCompleted: Boolean
    ): Result<Unit> {
        return try {
            bookClient.markReadProgress(
                KomgaBookId(bookId.value),
                KomgaBookReadProgressUpdateRequest(
                    page = page,
                    completed = isCompleted
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Failed to update read progress for book ${bookId.value}" }
            Result.failure(e)
        }
    }
    
    override suspend fun markBookAsRead(bookId: ServerId): Result<Unit> {
        return try {
            bookClient.markReadProgress(
                KomgaBookId(bookId.value),
                KomgaBookReadProgressUpdateRequest(completed = true)
            )
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark book as read: ${bookId.value}" }
            Result.failure(e)
        }
    }
    
    override suspend fun markBookAsUnread(bookId: ServerId): Result<Unit> {
        return try {
            bookClient.deleteReadProgress(KomgaBookId(bookId.value))
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark book as unread: ${bookId.value}" }
            Result.failure(e)
        }
    }
    
    // WriteableMediaServer methods
    
    override suspend fun updateSeriesMetadata(
        seriesId: ServerId,
        metadata: ServerSeriesMetadata
    ): ServerSeries {
        // This would need the actual Komga metadata update implementation
        // For now, just return the current series
        return getSeries(seriesId)
    }
    
    override suspend fun updateBookMetadata(
        bookId: ServerId,
        metadata: ServerBookMetadata
    ): ServerBook {
        // This would need the actual Komga metadata update implementation
        // For now, just return the current book
        return getBook(bookId)
    }
    
    override suspend fun scanLibrary(libraryId: ServerId) {
        libraryClient.scan(KomgaLibraryId(libraryId.value))
    }
    
    override suspend fun analyzeLibrary(libraryId: ServerId) {
        libraryClient.analyze(KomgaLibraryId(libraryId.value))
    }
    
    override suspend fun refreshLibraryMetadata(libraryId: ServerId) {
        libraryClient.refreshMetadata(KomgaLibraryId(libraryId.value))
    }
    
    // Extension functions to convert Komga types to Server types
    
    private fun KomgaLibrary.toServerLibrary(): ServerLibrary {
        return ServerLibrary(
            id = ServerId(id.value),
            name = name,
            unavailable = unavailable
        )
    }
    
    private fun KomgaSeries.toServerSeries(): ServerSeries {
        return ServerSeries(
            id = ServerId(id.value),
            libraryId = ServerId(libraryId.value),
            name = metadata.title,
            sortName = metadata.titleSort,
            status = metadata.status.toServerStatus(),
            booksCount = booksCount,
            booksReadCount = booksReadCount,
            booksUnreadCount = booksUnreadCount,
            booksInProgressCount = booksInProgressCount,
            description = metadata.summary.takeIf { it.isNotEmpty() },
            created = created,
            lastModified = lastModified,
            thumbnailUrl = null, // URL is constructed separately
            metadata = metadata.toServerMetadata()
        )
    }
    
    private fun KomgaSeriesMetadata.toServerMetadata(): ServerSeriesMetadata {
        return ServerSeriesMetadata(
            title = title,
            sortTitle = titleSort,
            summary = summary.takeIf { it.isNotEmpty() },
            status = status.toServerStatus(),
            publisher = publisher.takeIf { it.isNotEmpty() },
            genres = genres,
            tags = tags,
            language = language.takeIf { it.isNotEmpty() },
            ageRating = ageRating?.toString(),
            authors = emptyList(), // Komga has different author structure
            totalBookCount = totalBookCount
        )
    }
    
    private fun KomgaSeriesStatus.toServerStatus(): SeriesStatus {
        return when (this) {
            KomgaSeriesStatus.ENDED -> SeriesStatus.ENDED
            KomgaSeriesStatus.ONGOING -> SeriesStatus.ONGOING
            KomgaSeriesStatus.ABANDONED -> SeriesStatus.ABANDONED
            KomgaSeriesStatus.HIATUS -> SeriesStatus.HIATUS
        }
    }
    
    private fun KomgaBook.toServerBook(): ServerBook {
        return ServerBook(
            id = ServerId(id.value),
            seriesId = ServerId(seriesId.value),
            libraryId = ServerId(libraryId.value),
            name = metadata.title,
            sortNumber = metadata.numberSort,
            pageCount = media.pagesCount,
            fileSize = size,
            mediaType = media.mediaType,
            readProgress = readProgress?.let { progress ->
                ServerReadProgress(
                    page = progress.page,
                    isCompleted = progress.completed,
                    readDate = progress.readDate,
                    lastReadDate = progress.lastModified
                )
            },
            thumbnailUrl = null, // URL is constructed separately
            created = created,
            lastModified = lastModified,
            metadata = metadata.toServerMetadata()
        )
    }
    
    private fun KomgaBookMetadata.toServerMetadata(): ServerBookMetadata {
        return ServerBookMetadata(
            title = title,
            sortTitle = titleSort,
            summary = summary.takeIf { it.isNotEmpty() },
            number = number.takeIf { it.isNotEmpty() },
            releaseDate = releaseDate?.toString(),
            publisher = publisher.takeIf { it.isNotEmpty() },
            genres = emptyList(), // Komga books don't have genres directly
            tags = tags,
            language = null,
            authors = authors.map { ServerAuthor(it.name, it.role) },
            links = links.map { ServerLink(it.label, it.url) }
        )
    }
    
    private fun ServerSort.toKomgaSort(): snd.komga.client.common.KomgaSort {
        return snd.komga.client.common.KomgaSort(
            orders = listOf(
                snd.komga.client.common.KomgaSort.Order(
                    property = property,
                    direction = when (direction) {
                        SortDirection.ASC -> snd.komga.client.common.KomgaSort.Direction.ASC
                        SortDirection.DESC -> snd.komga.client.common.KomgaSort.Direction.DESC
                    }
                )
            )
        )
    }
}
