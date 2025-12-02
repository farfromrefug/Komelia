package io.github.snd_r.komelia.opds.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.snd_r.komelia.opds.model.OpdsBelongsTo
import io.github.snd_r.komelia.opds.model.OpdsContributor
import io.github.snd_r.komelia.opds.model.OpdsFeed
import io.github.snd_r.komelia.opds.model.OpdsFeedMetadata
import io.github.snd_r.komelia.opds.model.OpdsLink
import io.github.snd_r.komelia.opds.model.OpdsMediaType
import io.github.snd_r.komelia.opds.model.OpdsMetadata
import io.github.snd_r.komelia.opds.model.OpdsPublication
import io.github.snd_r.komelia.opds.model.OpdsSeries
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
 * Supports both OPDS 1.x (Atom/XML) and OPDS 2.0 (JSON) formats
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
            // Accept both OPDS 2.0 JSON and OPDS 1.x Atom/XML
            header(HttpHeaders.Accept, "${OpdsMediaType.OPDS_FEED}, ${OpdsMediaType.ATOM}, application/xml, */*")
            getAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        
        handleErrors(response)
        
        val bodyText = response.bodyAsText()
        val contentType = response.contentType()?.toString() ?: ""
        
        return try {
            // Detect format based on content type or content
            if (isAtomXml(contentType, bodyText)) {
                logger.debug { "Parsing OPDS 1.x (Atom/XML) feed" }
                parseAtomFeed(bodyText)
            } else {
                logger.debug { "Parsing OPDS 2.0 (JSON) feed" }
                json.decodeFromString<OpdsFeed>(bodyText)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse OPDS feed from: $fullUrl" }
            throw OpdsParseException("Failed to parse OPDS feed", e)
        }
    }
    
    override suspend fun getPublication(url: String): OpdsPublication {
        val fullUrl = resolveUrl(url)
        logger.debug { "Fetching OPDS publication from: $fullUrl" }
        
        val response = httpClient.get(fullUrl) {
            header(HttpHeaders.Accept, "${OpdsMediaType.OPDS_PUBLICATION}, ${OpdsMediaType.ATOM}, application/xml, */*")
            getAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        
        handleErrors(response)
        
        val bodyText = response.bodyAsText()
        val contentType = response.contentType()?.toString() ?: ""
        
        return try {
            if (isAtomXml(contentType, bodyText)) {
                logger.debug { "Parsing OPDS 1.x (Atom/XML) entry" }
                parseAtomEntry(bodyText)
            } else {
                logger.debug { "Parsing OPDS 2.0 (JSON) publication" }
                json.decodeFromString<OpdsPublication>(bodyText)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse OPDS publication from: $fullUrl" }
            throw OpdsParseException("Failed to parse OPDS publication", e)
        }
    }
    
    /**
     * Detect if the response is Atom/XML format (OPDS 1.x)
     */
    private fun isAtomXml(contentType: String, body: String): Boolean {
        // Check content type
        if (contentType.contains("atom") || contentType.contains("xml")) {
            return true
        }
        // Check content - XML starts with < or <?xml
        val trimmedBody = body.trim()
        return trimmedBody.startsWith("<?xml") || trimmedBody.startsWith("<feed") || trimmedBody.startsWith("<entry")
    }
    
    /**
     * Parse OPDS 1.x Atom feed XML into OpdsFeed
     * This is a simple regex-based parser for Atom XML
     */
    private fun parseAtomFeed(xml: String): OpdsFeed {
        val title = extractXmlElement(xml, "title") ?: "OPDS Feed"
        val id = extractXmlElement(xml, "id")
        val updated = extractXmlElement(xml, "updated")
        
        // Parse feed-level links
        val links = parseAtomLinks(xml, isFeed = true)
        
        // Parse entries as publications
        val entries = extractAtomEntries(xml)
        val publications = entries.map { parseAtomEntry(it) }
        
        // Extract pagination info if available
        val totalResults = extractXmlElement(xml, "opensearch:totalResults")?.toIntOrNull()
            ?: extractXmlElement(xml, "totalResults")?.toIntOrNull()
        val itemsPerPage = extractXmlElement(xml, "opensearch:itemsPerPage")?.toIntOrNull()
            ?: extractXmlElement(xml, "itemsPerPage")?.toIntOrNull()
        val startIndex = extractXmlElement(xml, "opensearch:startIndex")?.toIntOrNull()
            ?: extractXmlElement(xml, "startIndex")?.toIntOrNull()
        
        return OpdsFeed(
            metadata = OpdsFeedMetadata(
                title = title,
                modified = updated,
                numberOfItems = totalResults,
                itemsPerPage = itemsPerPage,
                currentPage = if (startIndex != null && itemsPerPage != null && itemsPerPage > 0) {
                    (startIndex / itemsPerPage) + 1
                } else null
            ),
            links = links,
            publications = publications.ifEmpty { null }
        )
    }
    
    /**
     * Parse a single OPDS 1.x Atom entry into OpdsPublication
     */
    private fun parseAtomEntry(xml: String): OpdsPublication {
        val title = extractXmlElement(xml, "title") ?: "Unknown"
        val id = extractXmlElement(xml, "id")
        val updated = extractXmlElement(xml, "updated")
        val published = extractXmlElement(xml, "published")
        val summary = extractXmlElement(xml, "summary") ?: extractXmlElement(xml, "content")
        
        // Parse authors
        val authors = extractAtomAuthors(xml)
        
        // Parse links
        val links = parseAtomLinks(xml, isFeed = false)
        
        // Separate image links from other links
        val imageLinks = links.filter { link ->
            link.rel?.contains("image") == true || 
            link.rel?.contains("thumbnail") == true ||
            link.rel?.contains("cover") == true ||
            link.type?.startsWith("image/") == true
        }
        val otherLinks = links.filter { it !in imageLinks }
        
        // Parse series information from dc:relation or series element
        val seriesName = extractXmlElement(xml, "schema:Series") 
            ?: extractXmlAttributeValue(xml, "link", "rel", "series", "title")
        val seriesPosition = extractXmlElement(xml, "schema:position")?.toDoubleOrNull()
            ?: extractXmlAttributeValue(xml, "link", "rel", "series", "number")?.toDoubleOrNull()
        
        val belongsTo = if (seriesName != null) {
            OpdsBelongsTo(
                series = listOf(OpdsSeries(
                    name = seriesName,
                    position = seriesPosition
                ))
            )
        } else null
        
        return OpdsPublication(
            metadata = OpdsMetadata(
                identifier = id,
                title = title,
                modified = updated,
                published = published,
                description = summary,
                author = authors.ifEmpty { null },
                belongsTo = belongsTo
            ),
            links = otherLinks,
            images = imageLinks.ifEmpty { null }
        )
    }
    
    /**
     * Parse Atom links from XML
     */
    private fun parseAtomLinks(xml: String, isFeed: Boolean): List<OpdsLink> {
        val linkPattern = Regex("""<link\s+([^>]*)(?:/>|>)""", RegexOption.IGNORE_CASE)
        val links = mutableListOf<OpdsLink>()
        
        linkPattern.findAll(xml).forEach { match ->
            val attrs = match.groupValues[1]
            val href = extractAttribute(attrs, "href")
            if (href != null) {
                val rel = extractAttribute(attrs, "rel") ?: ""
                val type = extractAttribute(attrs, "type")
                val title = extractAttribute(attrs, "title")
                
                // Map OPDS 1.x rel values to OPDS 2.0 style
                val mappedRel = mapAtomRelToOpds2(rel)
                
                links.add(OpdsLink(
                    href = href,
                    rel = mappedRel,
                    type = type,
                    title = title
                ))
            }
        }
        
        return links
    }
    
    /**
     * Map OPDS 1.x link relations to OPDS 2.0 equivalents
     */
    private fun mapAtomRelToOpds2(rel: String): String {
        return when {
            rel.contains("acquisition") -> rel // Keep acquisition rels as-is
            rel == "http://opds-spec.org/image" -> "http://opds-spec.org/image"
            rel == "http://opds-spec.org/image/thumbnail" -> "http://opds-spec.org/image/thumbnail"
            rel == "http://opds-spec.org/thumbnail" -> "http://opds-spec.org/image/thumbnail"
            rel == "http://opds-spec.org/cover" -> "http://opds-spec.org/image"
            rel.contains("thumbnail") -> "http://opds-spec.org/image/thumbnail"
            rel.contains("cover") -> "http://opds-spec.org/image"
            else -> rel
        }
    }
    
    /**
     * Extract all entry elements from an Atom feed
     */
    private fun extractAtomEntries(xml: String): List<String> {
        val entryPattern = Regex("""<entry[^>]*>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
        return entryPattern.findAll(xml).map { it.groupValues[1] }.toList()
    }
    
    /**
     * Extract authors from Atom entry
     */
    private fun extractAtomAuthors(xml: String): List<OpdsContributor> {
        val authorPattern = Regex("""<author[^>]*>(.*?)</author>""", RegexOption.DOT_MATCHES_ALL)
        return authorPattern.findAll(xml).mapNotNull { match ->
            val authorXml = match.groupValues[1]
            val name = extractXmlElement(authorXml, "name")
            val uri = extractXmlElement(authorXml, "uri")
            if (name != null) {
                OpdsContributor(name = name, identifier = uri)
            } else null
        }.toList()
    }
    
    /**
     * Extract simple XML element content
     */
    private fun extractXmlElement(xml: String, tagName: String): String? {
        // Handle namespaced tags
        val patterns = listOf(
            Regex("""<$tagName[^>]*>([^<]*)</$tagName>""", RegexOption.IGNORE_CASE),
            Regex("""<[^:]+:$tagName[^>]*>([^<]*)</[^:]+:$tagName>""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            pattern.find(xml)?.let { return it.groupValues[1].trim() }
        }
        return null
    }
    
    /**
     * Extract attribute value from XML
     */
    private fun extractAttribute(attrs: String, attrName: String): String? {
        val pattern = Regex("""$attrName\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return pattern.find(attrs)?.groupValues?.get(1)
    }
    
    /**
     * Extract attribute from link element with specific rel
     */
    private fun extractXmlAttributeValue(xml: String, element: String, relAttr: String, relValue: String, targetAttr: String): String? {
        val linkPattern = Regex("""<$element\s+([^>]*)(?:/>|>)""", RegexOption.IGNORE_CASE)
        linkPattern.findAll(xml).forEach { match ->
            val attrs = match.groupValues[1]
            val rel = extractAttribute(attrs, relAttr)
            if (rel?.contains(relValue) == true) {
                return extractAttribute(attrs, targetAttr)
            }
        }
        return null
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
