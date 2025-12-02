package io.github.snd_r.komelia.settings

import kotlinx.coroutines.flow.Flow

/**
 * Repository for storing local read progress when the server doesn't support it (e.g., OPDS).
 * This keeps the reading progress feature working locally.
 */
interface LocalReadProgressRepository {
    
    /**
     * Get the read progress for a specific book
     * @param bookId The unique identifier for the book
     * @return The read progress data, or null if no progress exists
     */
    suspend fun getProgress(bookId: String): LocalReadProgress?
    
    /**
     * Get read progress for multiple books
     * @param bookIds List of book identifiers
     * @return Map of bookId to read progress
     */
    suspend fun getProgressBatch(bookIds: List<String>): Map<String, LocalReadProgress>
    
    /**
     * Update the read progress for a book
     * @param bookId The unique identifier for the book
     * @param progress The progress data to save
     */
    suspend fun updateProgress(bookId: String, progress: LocalReadProgress)
    
    /**
     * Mark a book as completed
     * @param bookId The unique identifier for the book
     */
    suspend fun markAsCompleted(bookId: String)
    
    /**
     * Mark a book as unread (remove all progress)
     * @param bookId The unique identifier for the book
     */
    suspend fun markAsUnread(bookId: String)
    
    /**
     * Get all books that are currently in progress (started but not completed)
     */
    suspend fun getInProgressBooks(): List<InProgressBook>
    
    /**
     * Observe read progress changes for a specific book
     */
    fun observeProgress(bookId: String): Flow<LocalReadProgress?>
    
    /**
     * Get total count of read books
     */
    suspend fun getReadBooksCount(): Int
}

/**
 * Local read progress data
 */
data class LocalReadProgress(
    val bookId: String,
    val currentPage: Int,
    val totalPages: Int,
    val isCompleted: Boolean,
    val lastReadTimestamp: Long,
    val percentComplete: Float = if (totalPages > 0) (currentPage.toFloat() / totalPages) * 100 else 0f
)

/**
 * A book that is currently being read (in progress)
 */
data class InProgressBook(
    val bookId: String,
    val currentPage: Int,
    val totalPages: Int,
    val lastReadTimestamp: Long,
    val percentComplete: Float
)
