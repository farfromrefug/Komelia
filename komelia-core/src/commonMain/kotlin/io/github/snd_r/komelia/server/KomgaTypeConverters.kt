package io.github.snd_r.komelia.server

import snd.komga.client.book.KomgaAuthor
import snd.komga.client.book.KomgaBook
import snd.komga.client.book.KomgaBookId
import snd.komga.client.book.KomgaBookMetadata
import snd.komga.client.book.KomgaBookReadProgress
import snd.komga.client.book.KomgaMedia
import snd.komga.client.book.KomgaMediaStatus
import snd.komga.client.book.KomgaWebLink
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeries
import snd.komga.client.series.KomgaSeriesBookMetadata
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.series.KomgaSeriesMetadata
import snd.komga.client.series.KomgaSeriesStatus
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Utility functions to convert Server types to Komga types for UI compatibility.
 * These converters allow OPDS data to be used with the existing Komga-based UI components.
 */
object KomgaTypeConverters {

    /**
     * Convert a ServerBook to KomgaBook for UI compatibility
     */
    fun serverBookToKomgaBook(book: ServerBook): KomgaBook {
        return KomgaBook(
            id = KomgaBookId(book.id.value),
            seriesId = KomgaSeriesId(book.seriesId.value),
            seriesTitle = "",
            libraryId = KomgaLibraryId(book.libraryId.value),
            name = book.name,
            number = book.sortNumber?.toInt() ?: 0,
            url = book.thumbnailUrl ?: "",
            sizeBytes = book.fileSize,
            size = "${book.fileSize / 1024}KB",
            media = KomgaMedia(
                status = KomgaMediaStatus.READY,
                mediaType = book.mediaType ?: "",
                pagesCount = book.pageCount,
                comment = "",
                epubDivinaCompatible = false
            ),
            metadata = KomgaBookMetadata(
                title = book.metadata.title,
                titleLock = false,
                summary = book.metadata.summary ?: "",
                summaryLock = false,
                number = book.metadata.number ?: "",
                numberLock = false,
                numberSort = book.sortNumber ?: 0.0,
                numberSortLock = false,
                releaseDate = null,
                releaseDateLock = false,
                authors = book.metadata.authors.map { 
                    KomgaAuthor(it.name, it.role) 
                },
                authorsLock = false,
                tags = book.metadata.tags.toSet(),
                tagsLock = false,
                isbn = "",
                isbnLock = false,
                links = book.metadata.links.map { 
                    KomgaWebLink(it.label, it.url) 
                },
                linksLock = false,
                created = book.created,
                lastModified = book.lastModified
            ),
            readProgress = book.readProgress?.let {
                KomgaBookReadProgress(
                    page = it.page,
                    completed = it.isCompleted,
                    readDate = it.readDate,
                    created = it.readDate,
                    lastModified = it.lastReadDate,
                    deviceId = "",
                    deviceName = ""
                )
            },
            deleted = false,
            fileHash = "",
            fileLastModified = book.lastModified,
            oneshot = false
        )
    }

    /**
     * Convert a ServerSeries to KomgaSeries for UI compatibility
     */
    fun serverSeriesToKomgaSeries(series: ServerSeries): KomgaSeries {
        return KomgaSeries(
            id = KomgaSeriesId(series.id.value),
            libraryId = KomgaLibraryId(series.libraryId.value),
            name = series.name,
            url = series.thumbnailUrl ?: "",
            booksCount = series.booksCount,
            booksReadCount = series.booksReadCount,
            booksUnreadCount = series.booksUnreadCount,
            booksInProgressCount = series.booksInProgressCount,
//            created = series.created,
//            lastModified = series.lastModified,
//            fileLastModified = series.lastModified,
            deleted = false,
            oneshot = false,
            metadata = KomgaSeriesMetadata(
                title = series.metadata.title,
                titleLock = false,
                titleSort = series.metadata.sortTitle ?: series.metadata.title,
                titleSortLock = false,
                summary = series.metadata.summary ?: "",
                summaryLock = false,
                status = when (series.status) {
                    SeriesStatus.ENDED -> KomgaSeriesStatus.ENDED
                    SeriesStatus.ONGOING -> KomgaSeriesStatus.ONGOING
                    SeriesStatus.ABANDONED -> KomgaSeriesStatus.ABANDONED
                    SeriesStatus.HIATUS -> KomgaSeriesStatus.HIATUS
                    SeriesStatus.UNKNOWN -> KomgaSeriesStatus.ENDED
                },
                statusLock = false,
                readingDirection = null,
                readingDirectionLock = false,
                publisher = series.metadata.publisher ?: "",
                publisherLock = false,
                ageRating = null,
                ageRatingLock = false,
                language = series.metadata.language ?: "",
                languageLock = false,
                genres = series.metadata.genres.toList(),
                genresLock = false,
                tags = series.metadata.tags.toList(),
                tagsLock = false,
                totalBookCount = series.metadata.totalBookCount,
                totalBookCountLock = false,
                sharingLabels = emptyList(),
                sharingLabelsLock = false,
                links = emptyList(),
                linksLock = false,
                alternateTitles = emptyList(),
                alternateTitlesLock = false,
//                created = series.created,
//                lastModified = series.lastModified
            ),
            booksMetadata = KomgaSeriesBookMetadata(
                authors = series.metadata.authors,
                tags = series.metadata.tags,
                summary = series.metadata.summary ?: "",
                summaryNumber = "",
                created = series.created ?: Clock.System.now(),
                lastModified = series.lastModified ?: Clock.System.now(),
                releaseDate = null
            )
        )
    }
}
