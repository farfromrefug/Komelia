package io.github.snd_r.komelia.opds.client

import io.github.snd_r.komelia.opds.model.OpdsFeed
import io.github.snd_r.komelia.opds.model.OpdsPublication

/**
 * OPDS 2.0 Client Interface
 * Provides methods for fetching OPDS feeds and publications from an OPDS 2.0 server
 */
interface OpdsClient {
    /**
     * Fetch the root OPDS feed from the server
     */
    suspend fun getRootFeed(): OpdsFeed
    
    /**
     * Fetch an OPDS feed from a specific URL
     * @param url The URL of the feed to fetch
     */
    suspend fun getFeed(url: String): OpdsFeed
    
    /**
     * Fetch a single publication's details
     * @param url The URL of the publication to fetch
     */
    suspend fun getPublication(url: String): OpdsPublication
    
    /**
     * Search for publications
     * @param searchUrl The search URL (may be templated)
     * @param query The search query
     */
    suspend fun search(searchUrl: String, query: String): OpdsFeed
    
    /**
     * Download a publication file
     * @param acquisitionUrl The URL to download from
     * @return The raw bytes of the downloaded file
     */
    suspend fun downloadPublication(acquisitionUrl: String): ByteArray
    
    /**
     * Get the thumbnail image for a publication
     * @param thumbnailUrl The URL of the thumbnail
     * @return The raw bytes of the image
     */
    suspend fun getThumbnail(thumbnailUrl: String): ByteArray
    
    /**
     * Get the cover image for a publication
     * @param coverUrl The URL of the cover image
     * @return The raw bytes of the image
     */
    suspend fun getCover(coverUrl: String): ByteArray
    
    /**
     * Get the base URL of this OPDS server
     */
    val baseUrl: String
}

/**
 * Factory for creating OPDS clients
 */
interface OpdsClientFactory {
    /**
     * Create an OPDS client for a specific server
     * @param baseUrl The base URL of the OPDS server
     * @param username Optional username for authentication
     * @param password Optional password for authentication
     */
    fun createClient(
        baseUrl: String,
        username: String? = null,
        password: String? = null
    ): OpdsClient
}

/**
 * Exception thrown when an OPDS operation fails
 */
open class OpdsException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Exception thrown when authentication fails
 */
class OpdsAuthenticationException(
    message: String = "Authentication failed",
    cause: Throwable? = null
) : OpdsException(message, cause)

/**
 * Exception thrown when a resource is not found
 */
class OpdsNotFoundException(
    message: String = "Resource not found",
    cause: Throwable? = null
) : OpdsException(message, cause)

/**
 * Exception thrown when parsing OPDS data fails
 */
class OpdsParseException(
    message: String = "Failed to parse OPDS response",
    cause: Throwable? = null
) : OpdsException(message, cause)
