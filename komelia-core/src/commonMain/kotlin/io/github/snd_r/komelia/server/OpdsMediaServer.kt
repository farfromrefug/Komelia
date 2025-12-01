package io.github.snd_r.komelia.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.snd_r.komelia.opds.client.OpdsClient
import io.github.snd_r.komelia.opds.model.OpdsFeed
import io.github.snd_r.komelia.opds.model.OpdsLinkRel
import io.github.snd_r.komelia.opds.model.OpdsMetadata
import io.github.snd_r.komelia.opds.model.OpdsPublication
import io.github.snd_r.komelia.opds.model.getAcquisitionLink
import io.github.snd_r.komelia.opds.model.getAuthorsString
import io.github.snd_r.komelia.opds.model.getCoverUrl
import io.github.snd_r.komelia.opds.model.getNextLink
import io.github.snd_r.komelia.opds.model.getSearchLink
import io.github.snd_r.komelia.opds.model.getThumbnailUrl
import kotlinx.datetime.Instant

private val logger = KotlinLogging.logger {}

/**
 * MediaServer implementation that uses OPDS 2.0 as the backend
 * 
 * This adapter maps OPDS concepts to the MediaServer interface:
 * - OPDS Navigation feeds → Libraries
 * - OPDS Groups → Series
 * - OPDS Publications → Books
 * 
 * Note: OPDS is primarily a read-only protocol, so write operations
 * are not supported and will return failure results.
 */
class OpdsMediaServer(
    private val opdsClient: OpdsClient,
    private val serverName: String = "OPDS Server"
) : MediaServer {
    
    private var rootFeed: OpdsFeed? = null
    private val libraryFeeds = mutableMapOf<ServerId, OpdsFeed>()
    
    override val supportsWriteOperations: Boolean = false
    override val supportsRealTimeEvents: Boolean = false
    
    override suspend fun getServerInfo(): ServerInfo {
        val feed = getRootFeedCached()
        return ServerInfo(
            type = ServerType.OPDS,
            name = feed.metadata.title,
            version = null,
            baseUrl = opdsClient.baseUrl
        )
    }
    
    override suspend fun getCurrentUser(): ServerUser {
        // OPDS doesn't have a standard user concept
        // Return a dummy user for compatibility
        return ServerUser(
            id = ServerId("opds-user"),
            email = "opds@local",
            isAdmin = false,
            roles = emptyList()
        )
    }
    
    override suspend fun getLibraries(): List<ServerLibrary> {
        val feed = getRootFeedCached()
        
        // Navigation links in the root feed represent "libraries" in OPDS
        val navigation = feed.navigation ?: emptyList()
        
        return navigation.mapIndexed { index, link ->
            ServerLibrary(
                id = ServerId(link.href),
                name = link.title ?: "Library ${index + 1}",
                unavailable = false
            )
        }.ifEmpty {
            // If no navigation, create a single "All Books" library
            listOf(
                ServerLibrary(
                    id = ServerId(opdsClient.baseUrl),
                    name = feed.metadata.title,
                    unavailable = false
                )
            )
        }
    }
    
    override suspend fun getLibrary(libraryId: ServerId): ServerLibrary {
        return getLibraries().find { it.id == libraryId }
            ?: throw IllegalArgumentException("Library not found: ${libraryId.value}")
    }
    
    override suspend fun getSeriesInLibrary(
        libraryId: ServerId,
        page: Int,
        pageSize: Int,
        sort: ServerSort?,
        searchTerm: String?
    ): ServerPageResult<ServerSeries> {
        val feed = getLibraryFeed(libraryId)
        
        // In OPDS, "groups" can represent series
        val groups = feed.groups ?: emptyList()
        val series = groups.map { group ->
            groupToSeries(group, libraryId)
        }
        
        // If no groups, create series from publications directly
        // (treating each publication as a standalone "series")
        val seriesFromPublications = if (series.isEmpty() && feed.publications != null) {
            feed.publications.mapIndexed { index, pub ->
                publicationToSeries(pub, libraryId, index)
            }
        } else {
            emptyList()
        }
        
        val allSeries = series + seriesFromPublications
        return createPage(allSeries, page, pageSize)
    }
    
    override suspend fun getSeries(seriesId: ServerId): ServerSeries {
        // For OPDS, we might need to navigate to the series feed
        val feed = opdsClient.getFeed(seriesId.value)
        val firstPub = feed.publications?.firstOrNull()
        
        return ServerSeries(
            id = seriesId,
            libraryId = ServerId(opdsClient.baseUrl),
            name = feed.metadata.title,
            sortName = feed.metadata.title,
            status = SeriesStatus.UNKNOWN,
            booksCount = feed.publications?.size ?: 0,
            booksReadCount = 0,
            booksUnreadCount = feed.publications?.size ?: 0,
            booksInProgressCount = 0,
            description = firstPub?.metadata?.description,
            created = null,
            lastModified = feed.metadata.modified?.let { parseInstant(it) },
            thumbnailUrl = firstPub?.getThumbnailUrl(),
            metadata = feedToSeriesMetadata(feed)
        )
    }
    
    override suspend fun getBooksInSeries(
        seriesId: ServerId,
        page: Int,
        pageSize: Int,
        sort: ServerSort?
    ): ServerPageResult<ServerBook> {
        val feed = opdsClient.getFeed(seriesId.value)
        val publications = feed.publications ?: emptyList()
        
        val books = publications.mapIndexed { index, pub ->
            publicationToBook(pub, seriesId, ServerId(opdsClient.baseUrl), index)
        }
        
        return createPage(books, page, pageSize)
    }
    
    override suspend fun getBook(bookId: ServerId): ServerBook {
        val pub = opdsClient.getPublication(bookId.value)
        return publicationToBook(pub, ServerId("unknown"), ServerId(opdsClient.baseUrl), 0)
    }
    
    override suspend fun getBookPages(bookId: ServerId): List<ServerBookPage> {
        // OPDS doesn't provide page-level information for image-based books
        // This would need to be handled differently (e.g., download and extract)
        return emptyList()
    }
    
    override fun getBookThumbnailUrl(bookId: ServerId): String {
        // The bookId in our case is the publication's self link
        // We'd need to cache the thumbnail URLs or re-fetch
        return "${opdsClient.baseUrl}/thumbnail/${bookId.value}"
    }
    
    override fun getSeriesThumbnailUrl(seriesId: ServerId): String {
        return "${opdsClient.baseUrl}/series-thumbnail/${seriesId.value}"
    }
    
    override fun getBookPageUrl(bookId: ServerId, pageNumber: Int): String {
        // OPDS doesn't support page-by-page access
        // Return the download URL instead
        return getBookDownloadUrl(bookId)
    }
    
    override fun getBookDownloadUrl(bookId: ServerId): String {
        return bookId.value
    }
    
    override suspend fun searchSeries(
        searchTerm: String,
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerSeries> {
        val rootFeed = getRootFeedCached()
        val searchLink = rootFeed.getSearchLink()
        
        if (searchLink == null) {
            logger.warn { "Server does not support search" }
            return ServerPageResult.empty()
        }
        
        val results = opdsClient.search(searchLink.href, searchTerm)
        val series = results.publications?.mapIndexed { index, pub ->
            publicationToSeries(pub, ServerId(opdsClient.baseUrl), index)
        } ?: emptyList()
        
        return createPage(series, page, pageSize)
    }
    
    override suspend fun searchBooks(
        searchTerm: String,
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerBook> {
        val rootFeed = getRootFeedCached()
        val searchLink = rootFeed.getSearchLink()
        
        if (searchLink == null) {
            logger.warn { "Server does not support search" }
            return ServerPageResult.empty()
        }
        
        val results = opdsClient.search(searchLink.href, searchTerm)
        val books = results.publications?.mapIndexed { index, pub ->
            publicationToBook(pub, ServerId("search"), ServerId(opdsClient.baseUrl), index)
        } ?: emptyList()
        
        return createPage(books, page, pageSize)
    }
    
    override suspend fun getRecentlyAddedSeries(
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerSeries> {
        // OPDS may have a "recent" navigation link
        val rootFeed = getRootFeedCached()
        val recentLink = rootFeed.navigation?.find { 
            it.title?.contains("recent", ignoreCase = true) == true ||
            it.href.contains("recent", ignoreCase = true)
        }
        
        return if (recentLink != null) {
            val feed = opdsClient.getFeed(recentLink.href)
            val series = feed.publications?.mapIndexed { index, pub ->
                publicationToSeries(pub, ServerId(opdsClient.baseUrl), index)
            } ?: emptyList()
            createPage(series, page, pageSize)
        } else {
            // Fall back to root feed publications
            val series = rootFeed.publications?.mapIndexed { index, pub ->
                publicationToSeries(pub, ServerId(opdsClient.baseUrl), index)
            } ?: emptyList()
            createPage(series, page, pageSize)
        }
    }
    
    override suspend fun getRecentlyAddedBooks(
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerBook> {
        val rootFeed = getRootFeedCached()
        val recentLink = rootFeed.navigation?.find { 
            it.title?.contains("recent", ignoreCase = true) == true ||
            it.href.contains("recent", ignoreCase = true)
        }
        
        return if (recentLink != null) {
            val feed = opdsClient.getFeed(recentLink.href)
            val books = feed.publications?.mapIndexed { index, pub ->
                publicationToBook(pub, ServerId("recent"), ServerId(opdsClient.baseUrl), index)
            } ?: emptyList()
            createPage(books, page, pageSize)
        } else {
            val books = rootFeed.publications?.mapIndexed { index, pub ->
                publicationToBook(pub, ServerId("all"), ServerId(opdsClient.baseUrl), index)
            } ?: emptyList()
            createPage(books, page, pageSize)
        }
    }
    
    override suspend fun getInProgressBooks(
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerBook> {
        // Standard OPDS doesn't track reading progress
        return ServerPageResult.empty()
    }
    
    override suspend fun updateReadProgress(
        bookId: ServerId,
        page: Int,
        isCompleted: Boolean
    ): Result<Unit> {
        // OPDS doesn't support write operations
        return Result.failure(UnsupportedOperationException("OPDS does not support read progress tracking"))
    }
    
    override suspend fun markBookAsRead(bookId: ServerId): Result<Unit> {
        return Result.failure(UnsupportedOperationException("OPDS does not support read progress tracking"))
    }
    
    override suspend fun markBookAsUnread(bookId: ServerId): Result<Unit> {
        return Result.failure(UnsupportedOperationException("OPDS does not support read progress tracking"))
    }
    
    // Helper methods
    
    private suspend fun getRootFeedCached(): OpdsFeed {
        return rootFeed ?: opdsClient.getRootFeed().also { rootFeed = it }
    }
    
    private suspend fun getLibraryFeed(libraryId: ServerId): OpdsFeed {
        return libraryFeeds.getOrPut(libraryId) {
            opdsClient.getFeed(libraryId.value)
        }
    }
    
    private fun groupToSeries(group: io.github.snd_r.komelia.opds.model.OpdsGroup, libraryId: ServerId): ServerSeries {
        val selfLink = group.links?.find { it.rel == OpdsLinkRel.SELF }?.href 
            ?: group.links?.firstOrNull()?.href 
            ?: ""
            
        return ServerSeries(
            id = ServerId(selfLink),
            libraryId = libraryId,
            name = group.metadata.title,
            sortName = group.metadata.title,
            status = SeriesStatus.UNKNOWN,
            booksCount = group.publications?.size ?: 0,
            booksReadCount = 0,
            booksUnreadCount = group.publications?.size ?: 0,
            booksInProgressCount = 0,
            description = null,
            created = null,
            lastModified = group.metadata.modified?.let { parseInstant(it) },
            thumbnailUrl = group.publications?.firstOrNull()?.getThumbnailUrl(),
            metadata = ServerSeriesMetadata(
                title = group.metadata.title,
                sortTitle = group.metadata.title,
                summary = group.metadata.subtitle,
                status = SeriesStatus.UNKNOWN,
                publisher = null,
                genres = emptyList(),
                tags = emptyList(),
                language = null,
                ageRating = null,
                authors = emptyList(),
                totalBookCount = group.publications?.size
            )
        )
    }
    
    private fun publicationToSeries(pub: OpdsPublication, libraryId: ServerId, index: Int): ServerSeries {
        val selfLink = pub.links.find { it.rel == OpdsLinkRel.SELF }?.href 
            ?: pub.getAcquisitionLink()?.href 
            ?: "pub-$index"
            
        return ServerSeries(
            id = ServerId(selfLink),
            libraryId = libraryId,
            name = pub.metadata.title,
            sortName = pub.metadata.sortAs ?: pub.metadata.title,
            status = SeriesStatus.UNKNOWN,
            booksCount = 1,
            booksReadCount = 0,
            booksUnreadCount = 1,
            booksInProgressCount = 0,
            description = pub.metadata.description,
            created = pub.metadata.published?.let { parseInstant(it) },
            lastModified = pub.metadata.modified?.let { parseInstant(it) },
            thumbnailUrl = pub.getThumbnailUrl(),
            metadata = metadataToSeriesMetadata(pub.metadata)
        )
    }
    
    private fun publicationToBook(
        pub: OpdsPublication, 
        seriesId: ServerId, 
        libraryId: ServerId,
        index: Int
    ): ServerBook {
        val acquisitionLink = pub.getAcquisitionLink()
        val bookId = acquisitionLink?.href ?: pub.links.firstOrNull()?.href ?: "book-$index"
        
        return ServerBook(
            id = ServerId(bookId),
            seriesId = seriesId,
            libraryId = libraryId,
            name = pub.metadata.title,
            sortNumber = pub.metadata.belongsTo?.series?.firstOrNull()?.position,
            pageCount = pub.metadata.numberOfPages ?: 0,
            fileSize = 0,
            mediaType = acquisitionLink?.type ?: "application/octet-stream",
            readProgress = null,
            thumbnailUrl = pub.getThumbnailUrl(),
            created = pub.metadata.published?.let { parseInstant(it) },
            lastModified = pub.metadata.modified?.let { parseInstant(it) },
            metadata = metadataToBookMetadata(pub.metadata, pub.links)
        )
    }
    
    private fun feedToSeriesMetadata(feed: OpdsFeed): ServerSeriesMetadata {
        val firstPub = feed.publications?.firstOrNull()
        return ServerSeriesMetadata(
            title = feed.metadata.title,
            sortTitle = feed.metadata.title,
            summary = firstPub?.metadata?.description,
            status = SeriesStatus.UNKNOWN,
            publisher = firstPub?.metadata?.publisher?.firstOrNull()?.name,
            genres = firstPub?.metadata?.subject?.map { it.name } ?: emptyList(),
            tags = emptyList(),
            language = firstPub?.metadata?.language?.firstOrNull(),
            ageRating = null,
            authors = firstPub?.metadata?.author?.map { 
                ServerAuthor(it.name, "Author") 
            } ?: emptyList(),
            totalBookCount = feed.publications?.size
        )
    }
    
    private fun metadataToSeriesMetadata(metadata: OpdsMetadata): ServerSeriesMetadata {
        return ServerSeriesMetadata(
            title = metadata.title,
            sortTitle = metadata.sortAs ?: metadata.title,
            summary = metadata.description,
            status = SeriesStatus.UNKNOWN,
            publisher = metadata.publisher?.firstOrNull()?.name,
            genres = metadata.subject?.map { it.name } ?: emptyList(),
            tags = emptyList(),
            language = metadata.language?.firstOrNull(),
            ageRating = null,
            authors = extractAuthors(metadata),
            totalBookCount = null
        )
    }
    
    private fun metadataToBookMetadata(
        metadata: OpdsMetadata, 
        links: List<io.github.snd_r.komelia.opds.model.OpdsLink>
    ): ServerBookMetadata {
        return ServerBookMetadata(
            title = metadata.title,
            sortTitle = metadata.sortAs ?: metadata.title,
            summary = metadata.description,
            number = metadata.belongsTo?.series?.firstOrNull()?.position?.toString(),
            releaseDate = metadata.published,
            publisher = metadata.publisher?.firstOrNull()?.name,
            genres = metadata.subject?.map { it.name } ?: emptyList(),
            tags = emptyList(),
            language = metadata.language?.firstOrNull(),
            authors = extractAuthors(metadata),
            links = links.mapNotNull { link ->
                if (link.rel != null && link.href.isNotEmpty()) {
                    ServerLink(link.rel, link.href)
                } else null
            }
        )
    }
    
    private fun extractAuthors(metadata: OpdsMetadata): List<ServerAuthor> {
        val authors = mutableListOf<ServerAuthor>()
        
        metadata.author?.forEach { authors.add(ServerAuthor(it.name, "Author")) }
        metadata.artist?.forEach { authors.add(ServerAuthor(it.name, "Artist")) }
        metadata.illustrator?.forEach { authors.add(ServerAuthor(it.name, "Illustrator")) }
        metadata.penciler?.forEach { authors.add(ServerAuthor(it.name, "Penciler")) }
        metadata.inker?.forEach { authors.add(ServerAuthor(it.name, "Inker")) }
        metadata.colorist?.forEach { authors.add(ServerAuthor(it.name, "Colorist")) }
        metadata.letterer?.forEach { authors.add(ServerAuthor(it.name, "Letterer")) }
        metadata.editor?.forEach { authors.add(ServerAuthor(it.name, "Editor")) }
        metadata.translator?.forEach { authors.add(ServerAuthor(it.name, "Translator")) }
        metadata.contributor?.forEach { authors.add(ServerAuthor(it.name, "Contributor")) }
        
        return authors
    }
    
    private fun <T> createPage(items: List<T>, page: Int, pageSize: Int): ServerPageResult<T> {
        val totalPages = if (items.isEmpty()) 0 else (items.size + pageSize - 1) / pageSize
        val startIndex = page * pageSize
        val endIndex = minOf(startIndex + pageSize, items.size)
        
        val pageContent = if (startIndex < items.size) {
            items.subList(startIndex, endIndex)
        } else {
            emptyList()
        }
        
        return ServerPageResult(
            content = pageContent,
            totalPages = totalPages,
            totalElements = items.size.toLong(),
            currentPage = page,
            pageSize = pageSize,
            first = page == 0,
            last = page >= totalPages - 1,
            empty = pageContent.isEmpty()
        )
    }
    
    private fun parseInstant(dateString: String): Instant? {
        return try {
            Instant.parse(dateString)
        } catch (e: Exception) {
            logger.debug { "Failed to parse date: $dateString" }
            null
        }
    }
}
