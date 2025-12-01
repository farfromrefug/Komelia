package io.github.snd_r.komelia.server

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Generic Server Abstraction Layer
 * 
 * These interfaces and models provide an abstraction layer that allows the application
 * to work with different media server backends (Komga, Booklore via OPDS, etc.)
 * 
 * The abstraction focuses on the core concepts common to most media servers:
 * - Libraries (collections of content)
 * - Series (groups of related books)
 * - Books (individual publications)
 * - Users and authentication
 */

/**
 * Unique identifier for server entities
 */
@JvmInline
@Serializable
value class ServerId(val value: String)

/**
 * Represents the type of server backend
 */
enum class ServerType {
    KOMGA,
    OPDS
}

/**
 * Information about the connected server
 */
data class ServerInfo(
    val type: ServerType,
    val name: String,
    val version: String?,
    val baseUrl: String
)

/**
 * Authenticated user information
 */
data class ServerUser(
    val id: ServerId,
    val email: String,
    val isAdmin: Boolean = false,
    val roles: List<String> = emptyList()
)

/**
 * A library/shelf on the server
 */
data class ServerLibrary(
    val id: ServerId,
    val name: String,
    val unavailable: Boolean = false
)

/**
 * A series (collection of books)
 */
data class ServerSeries(
    val id: ServerId,
    val libraryId: ServerId,
    val name: String,
    val sortName: String?,
    val status: SeriesStatus,
    val booksCount: Int,
    val booksReadCount: Int,
    val booksUnreadCount: Int,
    val booksInProgressCount: Int,
    val description: String?,
    val created: Instant?,
    val lastModified: Instant?,
    val thumbnailUrl: String?,
    val metadata: ServerSeriesMetadata
)

/**
 * Series metadata
 */
data class ServerSeriesMetadata(
    val title: String,
    val sortTitle: String?,
    val summary: String?,
    val status: SeriesStatus,
    val publisher: String?,
    val genres: List<String>,
    val tags: List<String>,
    val language: String?,
    val ageRating: String?,
    val authors: List<ServerAuthor>,
    val totalBookCount: Int?
)

/**
 * Series status
 */
enum class SeriesStatus {
    ENDED,
    ONGOING,
    ABANDONED,
    HIATUS,
    UNKNOWN
}

/**
 * A book (individual publication)
 */
data class ServerBook(
    val id: ServerId,
    val seriesId: ServerId,
    val libraryId: ServerId,
    val name: String,
    val sortNumber: Double?,
    val pageCount: Int,
    val fileSize: Long,
    val mediaType: String,
    val readProgress: ServerReadProgress?,
    val thumbnailUrl: String?,
    val created: Instant?,
    val lastModified: Instant?,
    val metadata: ServerBookMetadata
)

/**
 * Book metadata
 */
data class ServerBookMetadata(
    val title: String,
    val sortTitle: String?,
    val summary: String?,
    val number: String?,
    val releaseDate: String?,
    val publisher: String?,
    val genres: List<String>,
    val tags: List<String>,
    val language: String?,
    val authors: List<ServerAuthor>,
    val links: List<ServerLink>
)

/**
 * Author/contributor information
 */
data class ServerAuthor(
    val name: String,
    val role: String
)

/**
 * External link
 */
data class ServerLink(
    val label: String,
    val url: String
)

/**
 * Reading progress for a book
 */
data class ServerReadProgress(
    val page: Int,
    val isCompleted: Boolean,
    val readDate: Instant?,
    val lastReadDate: Instant?
)

/**
 * A page in a book
 */
data class ServerBookPage(
    val number: Int,
    val fileName: String,
    val mediaType: String,
    val width: Int?,
    val height: Int?,
    val fileSize: Long?
)

/**
 * Paginated result wrapper
 */
data class ServerPageResult<T>(
    val content: List<T>,
    val totalPages: Int,
    val totalElements: Long,
    val currentPage: Int,
    val pageSize: Int,
    val first: Boolean,
    val last: Boolean,
    val empty: Boolean
) {
    companion object {
        fun <T> empty(): ServerPageResult<T> = ServerPageResult(
            content = emptyList(),
            totalPages = 0,
            totalElements = 0,
            currentPage = 0,
            pageSize = 0,
            first = true,
            last = true,
            empty = true
        )
        
        fun <T> of(items: List<T>): ServerPageResult<T> = ServerPageResult(
            content = items,
            totalPages = 1,
            totalElements = items.size.toLong(),
            currentPage = 0,
            pageSize = items.size,
            first = true,
            last = true,
            empty = items.isEmpty()
        )
    }
}

/**
 * Sort options for queries
 */
data class ServerSort(
    val property: String,
    val direction: SortDirection
)

enum class SortDirection {
    ASC,
    DESC
}
