package io.github.snd_r.komelia.opds.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OPDS 2.0 Data Models
 * Based on the OPDS 2.0 specification: https://drafts.opds.io/opds-2.0
 * 
 * These models support the Readium Web Publication Manifest format used by OPDS 2.0
 */

/**
 * Represents an OPDS 2.0 Feed (catalog)
 * A feed contains navigation links, publications, groups, and facets
 */
@Serializable
data class OpdsFeed(
    val metadata: OpdsFeedMetadata,
    val links: List<OpdsLink> = emptyList(),
    val navigation: List<OpdsLink>? = null,
    val publications: List<OpdsPublication>? = null,
    val groups: List<OpdsGroup>? = null,
    val facets: List<OpdsFacet>? = null
)

/**
 * Metadata for an OPDS feed
 */
@Serializable
data class OpdsFeedMetadata(
    val title: String,
    val subtitle: String? = null,
    val modified: String? = null,
    @SerialName("@type")
    val type: String? = null,
    val currentPage: Int? = null,
    val itemsPerPage: Int? = null,
    val numberOfItems: Int? = null
)

/**
 * Represents an OPDS publication (book/comic/etc)
 */
@Serializable
data class OpdsPublication(
    val metadata: OpdsMetadata,
    val links: List<OpdsLink> = emptyList(),
    val images: List<OpdsLink>? = null
)

/**
 * Metadata for a publication - based on Readium Web Publication Manifest
 */
@Serializable
data class OpdsMetadata(
    val identifier: String? = null,
    @SerialName("@type")
    val type: String? = null,
    val title: String,
    val subtitle: String? = null,
    val sortAs: String? = null,
    val author: List<OpdsContributor>? = null,
    val translator: List<OpdsContributor>? = null,
    val editor: List<OpdsContributor>? = null,
    val artist: List<OpdsContributor>? = null,
    val illustrator: List<OpdsContributor>? = null,
    val letterer: List<OpdsContributor>? = null,
    val penciler: List<OpdsContributor>? = null,
    val colorist: List<OpdsContributor>? = null,
    val inker: List<OpdsContributor>? = null,
    val narrator: List<OpdsContributor>? = null,
    val contributor: List<OpdsContributor>? = null,
    val publisher: List<OpdsContributor>? = null,
    val imprint: List<OpdsContributor>? = null,
    val language: List<String>? = null,
    val modified: String? = null,
    val published: String? = null,
    val description: String? = null,
    val readingProgression: String? = null,
    val numberOfPages: Int? = null,
    val duration: Double? = null,
    val abridged: Boolean? = null,
    val subject: List<OpdsSubject>? = null,
    val belongsTo: OpdsBelongsTo? = null
)

/**
 * Contributor information (author, publisher, etc)
 */
@Serializable
data class OpdsContributor(
    val name: String,
    val sortAs: String? = null,
    val identifier: String? = null,
    val role: List<String>? = null,
    val links: List<OpdsLink>? = null
)

/**
 * Subject/genre information
 */
@Serializable
data class OpdsSubject(
    val name: String,
    val sortAs: String? = null,
    val scheme: String? = null,
    val code: String? = null,
    val links: List<OpdsLink>? = null
)

/**
 * Collection membership information
 */
@Serializable
data class OpdsBelongsTo(
    val series: List<OpdsSeries>? = null,
    val collection: List<OpdsCollection>? = null
)

/**
 * Series information
 */
@Serializable
data class OpdsSeries(
    val name: String,
    val sortAs: String? = null,
    val identifier: String? = null,
    val position: Double? = null,
    val links: List<OpdsLink>? = null
)

/**
 * Collection information
 */
@Serializable
data class OpdsCollection(
    val name: String,
    val sortAs: String? = null,
    val identifier: String? = null,
    val position: Double? = null,
    val links: List<OpdsLink>? = null
)

/**
 * OPDS Link - used throughout the specification for navigation, resources, etc
 */
@Serializable
data class OpdsLink(
    val href: String,
    val type: String? = null,
    val rel: String? = null,
    val title: String? = null,
    val templated: Boolean? = null,
    val height: Int? = null,
    val width: Int? = null,
    val bitrate: Double? = null,
    val duration: Double? = null,
    val language: List<String>? = null,
    val alternate: List<OpdsLink>? = null,
    val children: List<OpdsLink>? = null,
    val properties: OpdsLinkProperties? = null
)

/**
 * Properties for OPDS links
 */
@Serializable
data class OpdsLinkProperties(
    val numberOfItems: Int? = null,
    val price: OpdsPrice? = null,
    val indirectAcquisition: List<OpdsAcquisition>? = null,
    val authenticate: OpdsAuthentication? = null
)

/**
 * Price information for paid content
 */
@Serializable
data class OpdsPrice(
    val currency: String,
    val value: Double
)

/**
 * Acquisition information for downloads
 */
@Serializable
data class OpdsAcquisition(
    val type: String,
    val child: List<OpdsAcquisition>? = null
)

/**
 * Authentication requirements
 */
@Serializable
data class OpdsAuthentication(
    val scheme: String,
    val additionalProperties: Map<String, String>? = null
)

/**
 * Group of publications within a feed
 */
@Serializable
data class OpdsGroup(
    val metadata: OpdsFeedMetadata,
    val links: List<OpdsLink>? = null,
    val publications: List<OpdsPublication>? = null,
    val navigation: List<OpdsLink>? = null
)

/**
 * Facet for filtering publications
 */
@Serializable
data class OpdsFacet(
    val metadata: OpdsFacetMetadata,
    val links: List<OpdsLink>
)

/**
 * Metadata for a facet
 */
@Serializable
data class OpdsFacetMetadata(
    val title: String,
    val numberOfItems: Int? = null
)

/**
 * Well-known OPDS link relations
 */
object OpdsLinkRel {
    // Navigation
    const val SELF = "self"
    const val START = "start"
    const val SEARCH = "search"
    const val UP = "up"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val FIRST = "first"
    const val LAST = "last"
    const val SUBSECTION = "subsection"
    
    // Acquisition
    const val ACQUISITION = "http://opds-spec.org/acquisition"
    const val ACQUISITION_OPEN_ACCESS = "http://opds-spec.org/acquisition/open-access"
    const val ACQUISITION_BORROW = "http://opds-spec.org/acquisition/borrow"
    const val ACQUISITION_BUY = "http://opds-spec.org/acquisition/buy"
    const val ACQUISITION_SAMPLE = "http://opds-spec.org/acquisition/sample"
    const val ACQUISITION_SUBSCRIBE = "http://opds-spec.org/acquisition/subscribe"
    
    // Images
    const val IMAGE = "http://opds-spec.org/image"
    const val IMAGE_THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    const val COVER = "cover"
    const val THUMBNAIL = "thumbnail"
}

/**
 * Well-known OPDS media types
 */
object OpdsMediaType {
    // OPDS 2.0 (JSON)
    const val OPDS_FEED = "application/opds+json"
    const val OPDS_PUBLICATION = "application/opds-publication+json"
    const val OPDS_AUTHENTICATION = "application/opds-authentication+json"
    
    // OPDS 1.x (Atom/XML)
    const val ATOM = "application/atom+xml"
    const val ATOM_CATALOG = "application/atom+xml;profile=opds-catalog"
    const val ATOM_ENTRY = "application/atom+xml;type=entry"
    
    const val OPENSEARCH = "application/opensearchdescription+xml"
    
    // Common book formats
    const val EPUB = "application/epub+zip"
    const val PDF = "application/pdf"
    const val CBZ = "application/vnd.comicbook+zip"
    const val CBR = "application/vnd.comicbook-rar"
    const val MOBI = "application/x-mobipocket-ebook"
    
    // Image formats
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"
}

/**
 * Extension functions for working with OPDS data
 */

/**
 * Get the thumbnail URL from a publication's images
 */
fun OpdsPublication.getThumbnailUrl(): String? {
    return images?.firstOrNull { link ->
        link.rel == OpdsLinkRel.IMAGE_THUMBNAIL || 
        link.rel == OpdsLinkRel.THUMBNAIL ||
        link.rel?.contains("thumbnail") == true
    }?.href ?: images?.firstOrNull()?.href
}

/**
 * Get the cover image URL from a publication's images
 */
fun OpdsPublication.getCoverUrl(): String? {
    return images?.firstOrNull { link ->
        link.rel == OpdsLinkRel.IMAGE || 
        link.rel == OpdsLinkRel.COVER ||
        link.rel?.contains("cover") == true
    }?.href ?: images?.firstOrNull()?.href
}

/**
 * Get the acquisition/download link from a publication
 */
fun OpdsPublication.getAcquisitionLink(): OpdsLink? {
    return links.firstOrNull { link ->
        link.rel?.startsWith(OpdsLinkRel.ACQUISITION) == true ||
        link.rel == OpdsLinkRel.ACQUISITION_OPEN_ACCESS
    }
}

/**
 * Get all authors as a comma-separated string
 */
fun OpdsMetadata.getAuthorsString(): String {
    return author?.joinToString(", ") { it.name } ?: ""
}

/**
 * Get the self link from a feed
 */
fun OpdsFeed.getSelfLink(): OpdsLink? {
    return links.firstOrNull { it.rel == OpdsLinkRel.SELF }
}

/**
 * Get the search link from a feed
 */
fun OpdsFeed.getSearchLink(): OpdsLink? {
    return links.firstOrNull { it.rel == OpdsLinkRel.SEARCH }
}

/**
 * Get the next page link from a feed
 */
fun OpdsFeed.getNextLink(): OpdsLink? {
    return links.firstOrNull { it.rel == OpdsLinkRel.NEXT }
}

/**
 * Get the previous page link from a feed
 */
fun OpdsFeed.getPreviousLink(): OpdsLink? {
    return links.firstOrNull { it.rel == OpdsLinkRel.PREVIOUS }
}
