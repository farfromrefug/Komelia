package io.github.snd_r.komelia.opds.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.snd_r.komelia.opds.model.OpdsFeed
import io.github.snd_r.komelia.opds.model.OpdsMediaType
import io.github.snd_r.komelia.opds.model.OpdsPublication
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * HTTP implementation of the OPDS client using Ktor
 */
class HttpOpdsClient(
    override val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val httpClient: HttpClient
) : OpdsClient {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    
    private fun getAuthHeader(): String? {
        if (username != null && password != null) {
            val credentials = "$username:$password"
            return "Basic ${credentials.encodeToByteArray().encodeBase64()}"
        }
        return null
    }
    
    override suspend fun getRootFeed(): OpdsFeed {
        return getFeed(baseUrl)
    }
    
    override suspend fun getFeed(url: String): OpdsFeed {
        val fullUrl = resolveUrl(url)
        logger.debug { "Fetching OPDS feed from: $fullUrl" }
        
        val response = httpClient.get(fullUrl) {
            header(HttpHeaders.Accept, OpdsMediaType.OPDS_FEED)
            getAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        
        handleErrors(response)
        
        return try {
            val bodyText = response.bodyAsText()
            json.decodeFromString<OpdsFeed>(bodyText)
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse OPDS feed from: $fullUrl" }
            throw OpdsParseException("Failed to parse OPDS feed", e)
        }
    }
    
    override suspend fun getPublication(url: String): OpdsPublication {
        val fullUrl = resolveUrl(url)
        logger.debug { "Fetching OPDS publication from: $fullUrl" }
        
        val response = httpClient.get(fullUrl) {
            header(HttpHeaders.Accept, OpdsMediaType.OPDS_PUBLICATION)
            getAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        
        handleErrors(response)
        
        return try {
            val bodyText = response.bodyAsText()
            json.decodeFromString<OpdsPublication>(bodyText)
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse OPDS publication from: $fullUrl" }
            throw OpdsParseException("Failed to parse OPDS publication", e)
        }
    }
    
    override suspend fun search(searchUrl: String, query: String): OpdsFeed {
        // Handle templated URLs (e.g., /search{?query})
        val resolvedUrl = if (searchUrl.contains("{?")) {
            val baseSearchUrl = searchUrl.substringBefore("{?")
            val params = searchUrl.substringAfter("{?").removeSuffix("}").split(",")
            val queryParam = params.firstOrNull() ?: "query"
            "$baseSearchUrl?$queryParam=$query"
        } else if (searchUrl.contains("?")) {
            "$searchUrl&query=$query"
        } else {
            "$searchUrl?query=$query"
        }
        
        return getFeed(resolvedUrl)
    }
    
    override suspend fun downloadPublication(acquisitionUrl: String): ByteArray {
        val fullUrl = resolveUrl(acquisitionUrl)
        logger.debug { "Downloading publication from: $fullUrl" }
        
        val response = httpClient.get(fullUrl) {
            getAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        
        handleErrors(response)
        
        return response.body()
    }
    
    override suspend fun getThumbnail(thumbnailUrl: String): ByteArray {
        val fullUrl = resolveUrl(thumbnailUrl)
        logger.debug { "Fetching thumbnail from: $fullUrl" }
        
        val response = httpClient.get(fullUrl) {
            getAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        
        handleErrors(response)
        
        return response.body()
    }
    
    override suspend fun getCover(coverUrl: String): ByteArray {
        val fullUrl = resolveUrl(coverUrl)
        logger.debug { "Fetching cover from: $fullUrl" }
        
        val response = httpClient.get(fullUrl) {
            getAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        
        handleErrors(response)
        
        return response.body()
    }
    
    private fun resolveUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else if (url.startsWith("/")) {
            // Remove trailing slash from baseUrl if present
            val base = baseUrl.trimEnd('/')
            "$base$url"
        } else {
            val base = baseUrl.trimEnd('/')
            "$base/$url"
        }
    }
    
    private fun handleErrors(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            when (response.status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> {
                    throw OpdsAuthenticationException("Authentication failed: ${response.status}")
                }
                HttpStatusCode.NotFound -> {
                    throw OpdsNotFoundException("Resource not found: ${response.status}")
                }
                else -> {
                    throw OpdsException("OPDS request failed: ${response.status}")
                }
            }
        }
    }
}

/**
 * Factory for creating HTTP OPDS clients
 */
class HttpOpdsClientFactory(
    private val httpClientProvider: () -> HttpClient
) : OpdsClientFactory {
    
    override fun createClient(
        baseUrl: String,
        username: String?,
        password: String?
    ): OpdsClient {
        return HttpOpdsClient(
            baseUrl = baseUrl,
            username = username,
            password = password,
            httpClient = httpClientProvider()
        )
    }
}
