package io.github.snd_r.komelia.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.snd_r.komelia.opds.client.OpdsClient
import io.github.snd_r.komelia.opds.model.OpdsFeed
import io.github.snd_r.komelia.opds.model.OpdsLinkRel
import io.github.snd_r.komelia.opds.model.OpdsMetadata
import io.github.snd_r.komelia.opds.model.OpdsPublication
import io.github.snd_r.komelia.opds.model.getAcquisitionLink
import io.github.snd_r.komelia.opds.model.getThumbnailUrl
import kotlinx.datetime.Instant

private val logger = KotlinLogging.logger {}

/**
 * MediaServer implementation specifically for Booklore's OPDS API
 * 
 * Booklore uses OPDS v1-style API endpoints:
 * - /api/v1/opds - Root catalog
 * - /api/v1/opds/catalog - All books with pagination (page, size, q, libraryId)
 * - /api/v1/opds/recent - Recently added books
 * - /api/v1/opds/libraries - Libraries navigation
 * - /api/v1/opds/series - Series navigation
 * - /api/v1/opds/shelves - Shelves navigation
 * - /api/v1/opds/surprise - Random books
 * - /api/v1/opds/catalog?q={searchTerms} - Search
 */
class OPDSV1MediaServer(
    private val opdsClient: OpdsClient,
    private val serverName: String = "Booklore"
) : MediaServer {
    
    companion object {
        // Booklore-specific OPDS endpoint paths
        private const val OPDS_ROOT = "/api/v1/opds"
        private const val OPDS_CATALOG = "/api/v1/opds/catalog"
        private const val OPDS_RECENT = "/api/v1/opds/recent"
        private const val OPDS_LIBRARIES = "/api/v1/opds/libraries"
        private const val OPDS_SERIES = "/api/v1/opds/series"
        private const val OPDS_SHELVES = "/api/v1/opds/shelves"
        private const val OPDS_SURPRISE = "/api/v1/opds/surprise"
        
        // Default page size
        private const val DEFAULT_PAGE_SIZE = 50
        private const val MAX_PAGE_SIZE = 100
    }
    
    private var rootFeed: OpdsFeed? = null
    
    // Cache for series publications (when navigating into a series)
    private val seriesPublicationsCache = mutableMapOf<ServerId, List<OpdsPublication>>()
    
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
        // Booklore doesn't expose user info via OPDS
        // Return a dummy user for compatibility
        return ServerUser(
            id = ServerId("booklore-user"),
            email = "booklore@local",
            isAdmin = false,
            roles = emptySet()
        )
    }
    
    override suspend fun getLibraries(): List<ServerLibrary> {
        logger.debug { "Fetching libraries from Booklore" }
        
        try {
            val librariesFeed = opdsClient.getFeed("$OPDS_LIBRARIES")
            
            // In OPDS 1.x, libraries might be in navigation links or publications
            // Try publications first (entries in Atom)
            val libraries = mutableListOf<ServerLibrary>()
            
            // Check publications (parsed from Atom entries)
            librariesFeed.publications?.forEachIndexed { index, pub ->
                val libraryId = extractLibraryId(pub) ?: "library-$index"
                libraries.add(ServerLibrary(
                    id = ServerId(libraryId),
                    name = pub.metadata.title,
                    unavailable = false
                ))
            }
            
            // Also check navigation links (in case libraries are provided as navigation)
            librariesFeed.navigation?.forEach { navLink ->
                // Extract library ID from navigation link
                val libraryId = navLink.href.let { href ->
                    Regex("libraryId=(\\d+)").find(href)?.groupValues?.get(1)
                        ?: Regex("/libraries/(\\d+)").find(href)?.groupValues?.get(1)
                }
                if (libraryId != null && libraries.none { it.id.value == libraryId }) {
                    libraries.add(ServerLibrary(
                        id = ServerId(libraryId),
                        name = navLink.title ?: "Library $libraryId",
                        unavailable = false
                    ))
                }
            }
            
            // Also check feed links with subsection relation
            librariesFeed.links.filter { 
                it.rel == OpdsLinkRel.SUBSECTION || it.rel?.contains("subsection") == true 
            }.forEach { link ->
                val libraryId = link.href.let { href ->
                    Regex("libraryId=(\\d+)").find(href)?.groupValues?.get(1)
                        ?: Regex("/libraries/(\\d+)").find(href)?.groupValues?.get(1)
                }
                if (libraryId != null && libraries.none { it.id.value == libraryId }) {
                    libraries.add(ServerLibrary(
                        id = ServerId(libraryId),
                        name = link.title ?: "Library $libraryId",
                        unavailable = false
                    ))
                }
            }
            
            return libraries.ifEmpty {
                // Fallback: create a single "All Books" library
                listOf(
                    ServerLibrary(
                        id = ServerId("all"),
                        name = "All Books",
                        unavailable = false
                    )
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch libraries, falling back to single library" }
            return listOf(
                ServerLibrary(
                    id = ServerId("all"),
                    name = "All Books",
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
        logger.debug { "Fetching series in library ${libraryId.value}, page=$page, search=$searchTerm" }
        
        val effectivePageSize = minOf(pageSize, MAX_PAGE_SIZE)
        
        // Build the URL for series endpoint
        val url = buildString {
            append(OPDS_SERIES)
            append("?page=${page + 1}") // Booklore uses 1-based pages
            append("&size=$effectivePageSize")
            
            if (libraryId.value != "all") {
                append("&libraryId=${libraryId.value}")
            }
            
            if (!searchTerm.isNullOrBlank()) {
                append("&q=${searchTerm}")
            }
        }
        
        try {
            val feed = opdsClient.getFeed(url)
            
            val series = feed.publications?.mapIndexed { index, pub ->
                publicationToSeries(pub, libraryId, index)
            } ?: emptyList()
            
            // Use pagination info from feed metadata if available
            val totalItems = feed.metadata.numberOfItems ?: series.size
            val totalPages = if (effectivePageSize > 0) {
                (totalItems + effectivePageSize - 1) / effectivePageSize
            } else 1
            
            return ServerPageResult(
                content = series,
                totalPages = totalPages,
                totalElements = totalItems,
                currentPage = page,
                pageSize = effectivePageSize,
                first = page == 0,
                last = page >= totalPages - 1,
                empty = series.isEmpty()
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch series, falling back to catalog grouping" }
            // Fall back to fetching books and grouping by series
            return getSeriesFromCatalog(libraryId, page, effectivePageSize, searchTerm)
        }
    }
    
    /**
     * Fallback: Get series by fetching books from catalog and grouping them
     */
    private suspend fun getSeriesFromCatalog(
        libraryId: ServerId,
        page: Int,
        pageSize: Int,
        searchTerm: String?
    ): ServerPageResult<ServerSeries> {
        val url = buildCatalogUrl(page, pageSize, libraryId, searchTerm)
        val feed = opdsClient.getFeed(url)
        
        val publications = feed.publications ?: emptyList()
        
        // Group publications by their series name
        val groupedBySeries = publications.groupBy { pub ->
            pub.metadata.belongsTo?.series?.firstOrNull()?.name ?: pub.metadata.title
        }
        
        val series = groupedBySeries.entries.mapIndexed { index, (seriesName, pubs) ->
            val firstPub = pubs.first()
            val seriesInfo = firstPub.metadata.belongsTo?.series?.firstOrNull()
            
            val seriesId = seriesInfo?.identifier 
                ?: "series-${seriesName.hashCode()}"
            
            // Cache the publications for this series
            seriesPublicationsCache[ServerId(seriesId)] = pubs
            
            ServerSeries(
                id = ServerId(seriesId),
                libraryId = libraryId,
                name = seriesName,
                sortName = seriesInfo?.sortAs ?: seriesName,
                status = SeriesStatus.UNKNOWN,
                booksCount = pubs.size,
                booksReadCount = 0,
                booksUnreadCount = pubs.size,
                booksInProgressCount = 0,
                description = firstPub.metadata.description,
                created = firstPub.metadata.published?.let { parseInstant(it) },
                lastModified = firstPub.metadata.modified?.let { parseInstant(it) },
                thumbnailUrl = firstPub.getThumbnailUrl(),
                metadata = metadataToSeriesMetadata(firstPub.metadata)
            )
        }
        
        return createPage(series, page, pageSize)
    }
    
    override suspend fun getSeries(seriesId: ServerId): ServerSeries {
        logger.debug { "Fetching series ${seriesId.value}" }
        
        // Try to fetch from the series endpoint first
        try {
            val url = "$OPDS_SERIES/${seriesId.value}"
            val feed = opdsClient.getFeed(url)
            val firstPub = feed.publications?.firstOrNull()
            
            return ServerSeries(
                id = seriesId,
                libraryId = ServerId("all"),
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
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch series from endpoint, checking cache" }
            
            // Check if we have cached publications for this series
            val cachedPubs = seriesPublicationsCache[seriesId]
            if (cachedPubs != null && cachedPubs.isNotEmpty()) {
                val firstPub = cachedPubs.first()
                val seriesName = firstPub.metadata.belongsTo?.series?.firstOrNull()?.name 
                    ?: firstPub.metadata.title
                
                return ServerSeries(
                    id = seriesId,
                    libraryId = ServerId("all"),
                    name = seriesName,
                    sortName = seriesName,
                    status = SeriesStatus.UNKNOWN,
                    booksCount = cachedPubs.size,
                    booksReadCount = 0,
                    booksUnreadCount = cachedPubs.size,
                    booksInProgressCount = 0,
                    description = firstPub.metadata.description,
                    created = firstPub.metadata.published?.let { parseInstant(it) },
                    lastModified = firstPub.metadata.modified?.let { parseInstant(it) },
                    thumbnailUrl = firstPub.getThumbnailUrl(),
                    metadata = metadataToSeriesMetadata(firstPub.metadata)
                )
            }
            
            throw IllegalArgumentException("Series not found: ${seriesId.value}")
        }
    }
    
    override suspend fun getBooksInSeries(
        seriesId: ServerId,
        page: Int,
        pageSize: Int,
        sort: ServerSort?
    ): ServerPageResult<ServerBook> {
        logger.debug { "Fetching books in series ${seriesId.value}" }
        
        // First, check if we have cached publications for this series
        val cachedPubs = seriesPublicationsCache[seriesId]
        
        val publications = if (cachedPubs != null) {
            cachedPubs
        } else {
            // Try to fetch from the series endpoint
            try {
                val url = "$OPDS_SERIES/${seriesId.value}?page=${page + 1}&size=$pageSize"
                val feed = opdsClient.getFeed(url)
                feed.publications ?: emptyList()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch series books: ${e.message}" }
                emptyList()
            }
        }
        
        val books = publications.mapIndexed { index, pub ->
            publicationToBook(pub, seriesId, ServerId("all"), index)
        }
        
        // Sort by position if available
        val sortedBooks = books.sortedWith(compareBy(nullsLast()) { it.sortNumber })
        
        return createPage(sortedBooks, page, pageSize)
    }
    
    override suspend fun getBook(bookId: ServerId): ServerBook {
        val pub = opdsClient.getPublication(bookId.value)
        return publicationToBook(pub, ServerId("unknown"), ServerId("all"), 0)
    }
    
    override suspend fun getBookPages(bookId: ServerId): List<ServerBookPage> {
        // OPDS doesn't provide page-level information
        return emptyList()
    }
    
    override fun getBookThumbnailUrl(bookId: ServerId): String {
        // Booklore provides thumbnails via the publication images
        return "${opdsClient.baseUrl}/api/v1/books/${bookId.value}/cover"
    }
    
    override fun getSeriesThumbnailUrl(seriesId: ServerId): String {
        return "${opdsClient.baseUrl}/api/v1/series/${seriesId.value}/cover"
    }
    
    override fun getBookPageUrl(bookId: ServerId, pageNumber: Int): String {
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
        logger.debug { "Searching series: $searchTerm" }
        
        // Search using the series endpoint with query
        try {
            val url = "$OPDS_SERIES?page=${page + 1}&size=$pageSize&q=$searchTerm"
            val feed = opdsClient.getFeed(url)
            
            val series = feed.publications?.mapIndexed { index, pub ->
                publicationToSeries(pub, ServerId("all"), index)
            } ?: emptyList()
            
            return createPageFromFeed(series, feed, page, pageSize)
        } catch (e: Exception) {
            logger.warn(e) { "Series search failed, falling back to catalog search" }
            // Fall back to catalog search
            return searchSeriesViaCatalog(searchTerm, page, pageSize)
        }
    }
    
    private suspend fun searchSeriesViaCatalog(
        searchTerm: String,
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerSeries> {
        val url = buildCatalogUrl(page, pageSize, null, searchTerm)
        val feed = opdsClient.getFeed(url)
        
        val series = feed.publications?.mapIndexed { index, pub ->
            publicationToSeries(pub, ServerId("all"), index)
        } ?: emptyList()
        
        return createPageFromFeed(series, feed, page, pageSize)
    }
    
    override suspend fun searchBooks(
        searchTerm: String,
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerBook> {
        logger.debug { "Searching books: $searchTerm" }
        
        val url = buildCatalogUrl(page, pageSize, null, searchTerm)
        val feed = opdsClient.getFeed(url)
        
        val books = feed.publications?.mapIndexed { index, pub ->
            publicationToBook(pub, ServerId("search"), ServerId("all"), index)
        } ?: emptyList()
        
        return createPageFromFeed(books, feed, page, pageSize)
    }
    
    override suspend fun getRecentlyAddedSeries(
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerSeries> {
        logger.debug { "Fetching recently added series" }
        
        val url = "$OPDS_RECENT?page=${page + 1}&size=$pageSize"
        val feed = opdsClient.getFeed(url)
        
        val series = feed.publications?.mapIndexed { index, pub ->
            publicationToSeries(pub, ServerId("all"), index)
        } ?: emptyList()
        
        return createPageFromFeed(series, feed, page, pageSize)
    }
    
    override suspend fun getRecentlyAddedBooks(
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerBook> {
        logger.debug { "Fetching recently added books" }
        
        val url = "$OPDS_RECENT?page=${page + 1}&size=$pageSize"
        val feed = opdsClient.getFeed(url)
        
        val books = feed.publications?.mapIndexed { index, pub ->
            publicationToBook(pub, ServerId("recent"), ServerId("all"), index)
        } ?: emptyList()
        
        return createPageFromFeed(books, feed, page, pageSize)
    }
    
    override suspend fun getInProgressBooks(
        page: Int,
        pageSize: Int
    ): ServerPageResult<ServerBook> {
        // Booklore OPDS doesn't track reading progress
        return ServerPageResult.empty()
    }
    
    override suspend fun updateReadProgress(
        bookId: ServerId,
        page: Int,
        isCompleted: Boolean
    ): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Booklore OPDS does not support read progress tracking"))
    }
    
    override suspend fun markBookAsRead(bookId: ServerId): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Booklore OPDS does not support read progress tracking"))
    }
    
    override suspend fun markBookAsUnread(bookId: ServerId): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Booklore OPDS does not support read progress tracking"))
    }
    
    // Helper methods
    
    private suspend fun getRootFeedCached(): OpdsFeed {
        return rootFeed ?: opdsClient.getFeed(OPDS_ROOT).also { rootFeed = it }
    }
    
    private fun buildCatalogUrl(
        page: Int,
        pageSize: Int,
        libraryId: ServerId?,
        searchTerm: String?
    ): String {
        return buildString {
            append(OPDS_CATALOG)
            append("?page=${page + 1}") // Booklore uses 1-based pages
            append("&size=$pageSize")
            
            if (libraryId != null && libraryId.value != "all") {
                append("&libraryId=${libraryId.value}")
            }
            
            if (!searchTerm.isNullOrBlank()) {
                append("&q=$searchTerm")
            }
        }
    }
    
    private fun extractLibraryId(pub: OpdsPublication): String? {
        // Try to extract library ID from the publication links
        // In Booklore, library links have format: /api/v1/opds/catalog?libraryId=X
        for (link in pub.links) {
            // Check for libraryId in URL
            if (link.href.contains("libraryId=")) {
                val match = Regex("libraryId=(\\d+)").find(link.href)
                match?.groupValues?.get(1)?.let { return it }
            }
            
            // Check for subsection link that contains the library ID
            if (link.rel == OpdsLinkRel.SUBSECTION || link.rel == "subsection") {
                val match = Regex("/libraries/(\\d+)").find(link.href)
                    ?: Regex("libraryId=(\\d+)").find(link.href)
                match?.groupValues?.get(1)?.let { return it }
            }
            
            // Check self link
            if (link.rel == OpdsLinkRel.SELF || link.rel == "self") {
                val match = Regex("/libraries/(\\d+)").find(link.href)
                match?.groupValues?.get(1)?.let { return it }
            }
        }
        
        // Use identifier or generate from title
        return pub.metadata.identifier ?: pub.metadata.title.hashCode().toString()
    }
    
    private fun publicationToSeries(pub: OpdsPublication, libraryId: ServerId, index: Int): ServerSeries {
        val seriesInfo = pub.metadata.belongsTo?.series?.firstOrNull()
        val seriesId = seriesInfo?.identifier 
            ?: pub.links.find { it.rel == OpdsLinkRel.SELF }?.href
            ?: pub.getAcquisitionLink()?.href 
            ?: "series-$index"
        
        val seriesName = seriesInfo?.name ?: pub.metadata.title
        
        return ServerSeries(
            id = ServerId(seriesId),
            libraryId = libraryId,
            name = seriesName,
            sortName = seriesInfo?.sortAs ?: pub.metadata.sortAs ?: seriesName,
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
        val bookId = acquisitionLink?.href 
            ?: pub.links.find { it.rel == OpdsLinkRel.SELF }?.href
            ?: pub.links.firstOrNull()?.href 
            ?: "book-$index"
        
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
            totalElements = items.size,
            currentPage = page,
            pageSize = pageSize,
            first = page == 0,
            last = page >= totalPages - 1,
            empty = pageContent.isEmpty()
        )
    }
    
    private fun <T> createPageFromFeed(items: List<T>, feed: OpdsFeed, page: Int, pageSize: Int): ServerPageResult<T> {
        val totalItems = feed.metadata.numberOfItems ?: items.size
        val totalPages = if (pageSize > 0 && totalItems > 0) {
            (totalItems + pageSize - 1) / pageSize
        } else 1
        
        return ServerPageResult(
            content = items,
            totalPages = totalPages,
            totalElements = totalItems,
            currentPage = page,
            pageSize = pageSize,
            first = page == 0,
            last = page >= totalPages - 1,
            empty = items.isEmpty()
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
