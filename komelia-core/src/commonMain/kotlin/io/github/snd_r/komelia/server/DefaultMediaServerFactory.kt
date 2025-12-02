package io.github.snd_r.komelia.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.snd_r.komelia.opds.client.HttpOpdsClient
import io.github.snd_r.komelia.opds.client.OpdsClient
import io.github.snd_r.komelia.opds.client.OpdsException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import snd.komga.client.book.KomgaBookClient
import snd.komga.client.library.KomgaLibraryClient
import snd.komga.client.series.KomgaSeriesClient
import snd.komga.client.user.KomgaUserClient

private val logger = KotlinLogging.logger {}

/**
 * Default implementation of MediaServerFactory
 * 
 * This factory can detect the server type and create the appropriate MediaServer implementation.
 */
class DefaultMediaServerFactory(
    private val httpClientProvider: () -> HttpClient
) : MediaServerFactory {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    override suspend fun createServer(
        baseUrl: String,
        username: String?,
        password: String?
    ): MediaServer {
        val serverType = detectServerType(baseUrl, username, password)
        
        return when (serverType) {
            ServerType.KOMGA -> {
                requireNotNull(username) { "Username required for Komga server" }
                requireNotNull(password) { "Password required for Komga server" }
                createKomgaServer(baseUrl, username, password)
            }
            ServerType.OPDS -> {
                createOpdsServer(baseUrl, username, password)
            }
        }
    }
    
    /**
     * Create a Komga server connection.
     * 
     * Note: This method is provided for interface compatibility but requires
     * proper DI setup with Komga client instances. For direct Komga usage,
     * use the existing Komga client infrastructure through DI.
     * 
     * @throws UnsupportedOperationException if Komga clients are not provided
     */
    override fun createKomgaServer(
        baseUrl: String,
        username: String,
        password: String
    ): MediaServer {
        throw UnsupportedOperationException(
            "KomgaMediaServer creation requires Komga client instances. " +
            "Use DefaultMediaServerFactory.withKomgaClients() or the existing DI setup instead."
        )
    }
    
    /**
     * Create a factory with Komga client support
     */
    fun withKomgaClients(
        userClient: KomgaUserClient,
        libraryClient: KomgaLibraryClient,
        seriesClient: KomgaSeriesClient,
        bookClient: KomgaBookClient
    ): KomgaCapableMediaServerFactory {
        return KomgaCapableMediaServerFactory(
            httpClientProvider = httpClientProvider,
            userClient = userClient,
            libraryClient = libraryClient,
            seriesClient = seriesClient,
            bookClient = bookClient
        )
    }
    
    override fun createOpdsServer(
        baseUrl: String,
        username: String?,
        password: String?
    ): MediaServer {
        val httpClient = httpClientProvider()
        val opdsClient = HttpOpdsClient(
            baseUrl = baseUrl,
            username = username,
            password = password,
            httpClient = httpClient
        )
        return OpdsMediaServer(opdsClient, serverName = "OPDS Server")
    }
    
    /**
     * Detect the type of server at the given URL
     * 
     * This method tries to identify whether the server is a Komga instance
     * or a generic OPDS server by checking characteristic responses.
     */
    private suspend fun detectServerType(
        baseUrl: String,
        username: String?,
        password: String?
    ): ServerType {
        val httpClient = httpClientProvider()
        
        try {
            // Try to detect Komga first by checking for the actuator endpoint
            val komgaDetected = tryDetectKomga(httpClient, baseUrl, username, password)
            if (komgaDetected) {
                logger.info { "Detected Komga server at $baseUrl" }
                return ServerType.KOMGA
            }
            
            // Try OPDS detection
            val opdsDetected = tryDetectOpds(httpClient, baseUrl, username, password)
            if (opdsDetected) {
                logger.info { "Detected OPDS server at $baseUrl" }
                return ServerType.OPDS
            }
            
            // Default to OPDS if we can't determine
            logger.warn { "Could not determine server type, defaulting to OPDS" }
            return ServerType.OPDS
            
        } catch (e: Exception) {
            logger.error(e) { "Error detecting server type at $baseUrl" }
            throw OpdsException("Failed to detect server type", e)
        }
    }
    
    private suspend fun tryDetectKomga(
        httpClient: HttpClient,
        baseUrl: String,
        username: String?,
        password: String?
    ): Boolean {
        return try {
            val authHeader = createAuthHeader(username, password)
            val response = httpClient.get("$baseUrl/api/v1/users/me") {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
            }
            
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                // Komga returns a user object with specific fields
                body.contains("\"id\"") && body.contains("\"email\"") && body.contains("\"roles\"")
            } else {
                false
            }
        } catch (e: Exception) {
            logger.debug(e) { "Not a Komga server: ${e.message}" }
            false
        }
    }
    
    private suspend fun tryDetectOpds(
        httpClient: HttpClient,
        baseUrl: String,
        username: String?,
        password: String?
    ): Boolean {
        return try {
            val authHeader = createAuthHeader(username, password)
            val response = httpClient.get(baseUrl) {
                header(HttpHeaders.Accept, "application/opds+json, application/json, */*")
                authHeader?.let { header(HttpHeaders.Authorization, it) }
            }
            
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                // Check for OPDS 2.0 characteristics
                body.contains("\"metadata\"") && 
                (body.contains("\"links\"") || body.contains("\"navigation\"") || body.contains("\"publications\""))
            } else {
                false
            }
        } catch (e: Exception) {
            logger.debug(e) { "Not an OPDS server: ${e.message}" }
            false
        }
    }
    
    private fun createAuthHeader(username: String?, password: String?): String? {
        if (username != null && password != null) {
            val credentials = "$username:$password"
            return "Basic ${credentials.encodeToByteArray().encodeBase64()}"
        }
        return null
    }
}

/**
 * Factory that has access to Komga clients and can create both Komga and OPDS servers
 */
class KomgaCapableMediaServerFactory(
    private val httpClientProvider: () -> HttpClient,
    private val userClient: KomgaUserClient,
    private val libraryClient: KomgaLibraryClient,
    private val seriesClient: KomgaSeriesClient,
    private val bookClient: KomgaBookClient
) : MediaServerFactory {
    
    override suspend fun createServer(
        baseUrl: String,
        username: String?,
        password: String?
    ): MediaServer {
        // Delegate to default factory for detection logic
        val defaultFactory = DefaultMediaServerFactory(httpClientProvider)
        return try {
            // Try detection first
            defaultFactory.createServer(baseUrl, username, password)
        } catch (e: UnsupportedOperationException) {
            // If it detected Komga but couldn't create it, use our Komga clients
            if (username != null && password != null) {
                createKomgaServer(baseUrl, username, password)
            } else {
                throw e
            }
        }
    }
    
    override fun createKomgaServer(
        baseUrl: String,
        username: String,
        password: String
    ): MediaServer {
        return KomgaMediaServer(
            baseUrl = baseUrl,
            userClient = userClient,
            libraryClient = libraryClient,
            seriesClient = seriesClient,
            bookClient = bookClient
        )
    }
    
    override fun createOpdsServer(
        baseUrl: String,
        username: String?,
        password: String?
    ): MediaServer {
        val httpClient = httpClientProvider()
        val opdsClient = HttpOpdsClient(
            baseUrl = baseUrl,
            username = username,
            password = password,
            httpClient = httpClient
        )
        return OpdsMediaServer(opdsClient)
    }
}

/**
 * Extension function to create a simple HTTP client for server detection
 */
fun createDetectionHttpClient(): HttpClient {
    return HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 10000
            connectTimeoutMillis = 5000
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
}
