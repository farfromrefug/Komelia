package io.github.snd_r.komelia.server

/**
 * Abstract interface for media server operations
 * 
 * This interface provides a common API that can be implemented by different
 * media server backends (Komga, OPDS/Booklore, etc.)
 */
interface MediaServer {
    
    /**
     * Get information about the server
     */
    suspend fun getServerInfo(): ServerInfo
    
    /**
     * Authenticate and get the current user
     */
    suspend fun getCurrentUser(): ServerUser
    
    /**
     * Get all libraries accessible to the current user
     */
    suspend fun getLibraries(): List<ServerLibrary>
    
    /**
     * Get a specific library by ID
     */
    suspend fun getLibrary(libraryId: ServerId): ServerLibrary
    
    /**
     * Get series in a library
     */
    suspend fun getSeriesInLibrary(
        libraryId: ServerId,
        page: Int = 0,
        pageSize: Int = 20,
        sort: ServerSort? = null,
        searchTerm: String? = null
    ): ServerPageResult<ServerSeries>
    
    /**
     * Get a specific series by ID
     */
    suspend fun getSeries(seriesId: ServerId): ServerSeries
    
    /**
     * Get books in a series
     */
    suspend fun getBooksInSeries(
        seriesId: ServerId,
        page: Int = 0,
        pageSize: Int = 20,
        sort: ServerSort? = null
    ): ServerPageResult<ServerBook>
    
    /**
     * Get a specific book by ID
     */
    suspend fun getBook(bookId: ServerId): ServerBook
    
    /**
     * Get pages for a book
     */
    suspend fun getBookPages(bookId: ServerId): List<ServerBookPage>
    
    /**
     * Get the thumbnail URL for a book
     */
    fun getBookThumbnailUrl(bookId: ServerId): String
    
    /**
     * Get the thumbnail URL for a series
     */
    fun getSeriesThumbnailUrl(seriesId: ServerId): String
    
    /**
     * Get the URL for a specific page in a book
     */
    fun getBookPageUrl(bookId: ServerId, pageNumber: Int): String
    
    /**
     * Get the download URL for a book
     */
    fun getBookDownloadUrl(bookId: ServerId): String
    
    /**
     * Search for series across all libraries
     */
    suspend fun searchSeries(
        searchTerm: String,
        page: Int = 0,
        pageSize: Int = 20
    ): ServerPageResult<ServerSeries>
    
    /**
     * Search for books across all libraries
     */
    suspend fun searchBooks(
        searchTerm: String,
        page: Int = 0,
        pageSize: Int = 20
    ): ServerPageResult<ServerBook>
    
    /**
     * Get recently added series
     */
    suspend fun getRecentlyAddedSeries(
        page: Int = 0,
        pageSize: Int = 20
    ): ServerPageResult<ServerSeries>
    
    /**
     * Get recently added books
     */
    suspend fun getRecentlyAddedBooks(
        page: Int = 0,
        pageSize: Int = 20
    ): ServerPageResult<ServerBook>
    
    /**
     * Get books currently being read (in progress)
     */
    suspend fun getInProgressBooks(
        page: Int = 0,
        pageSize: Int = 20
    ): ServerPageResult<ServerBook>
    
    /**
     * Update reading progress for a book
     * Note: This may not be supported by all backends (e.g., standard OPDS)
     */
    suspend fun updateReadProgress(
        bookId: ServerId,
        page: Int,
        isCompleted: Boolean
    ): Result<Unit>
    
    /**
     * Mark a book as read
     * Note: This may not be supported by all backends
     */
    suspend fun markBookAsRead(bookId: ServerId): Result<Unit>
    
    /**
     * Mark a book as unread
     * Note: This may not be supported by all backends
     */
    suspend fun markBookAsUnread(bookId: ServerId): Result<Unit>
    
    /**
     * Check if write operations are supported
     * OPDS servers typically don't support write operations
     */
    val supportsWriteOperations: Boolean
    
    /**
     * Check if real-time events are supported
     * Komga supports SSE, OPDS typically doesn't
     */
    val supportsRealTimeEvents: Boolean
    
    /**
     * Check if server-side read progress tracking is supported.
     * If false, the app should use local storage for progress tracking.
     */
    val supportsServerSideProgress: Boolean
        get() = supportsWriteOperations
}

/**
 * Extension interface for servers that support write operations
 * (primarily Komga, not standard OPDS)
 */
interface WriteableMediaServer : MediaServer {
    
    /**
     * Update series metadata
     */
    suspend fun updateSeriesMetadata(
        seriesId: ServerId,
        metadata: ServerSeriesMetadata
    ): ServerSeries
    
    /**
     * Update book metadata
     */
    suspend fun updateBookMetadata(
        bookId: ServerId,
        metadata: ServerBookMetadata
    ): ServerBook
    
    /**
     * Scan a library for new/changed content
     */
    suspend fun scanLibrary(libraryId: ServerId)
    
    /**
     * Analyze all books in a library
     */
    suspend fun analyzeLibrary(libraryId: ServerId)
    
    /**
     * Refresh metadata for a library
     */
    suspend fun refreshLibraryMetadata(libraryId: ServerId)
}

/**
 * Factory for creating media server instances
 */
interface MediaServerFactory {
    /**
     * Create a media server connection
     * The implementation will determine the server type based on the response
     */
    suspend fun createServer(
        baseUrl: String,
        username: String? = null,
        password: String? = null
    ): MediaServer
    
    /**
     * Create a Komga-specific server connection
     */
    fun createKomgaServer(
        baseUrl: String,
        username: String,
        password: String
    ): MediaServer
    
    /**
     * Create an OPDS-based server connection
     */
    fun createOpdsServer(
        baseUrl: String,
        username: String? = null,
        password: String? = null
    ): MediaServer
}
