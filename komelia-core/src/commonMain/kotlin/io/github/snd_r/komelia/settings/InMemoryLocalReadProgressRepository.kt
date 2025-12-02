package io.github.snd_r.komelia.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory implementation of LocalReadProgressRepository.
 * This serves as a simple fallback that doesn't persist across app restarts.
 * Can be replaced with a persistent implementation (SQLite, DataStore, etc.) later.
 */
class InMemoryLocalReadProgressRepository : LocalReadProgressRepository {
    
    private val progressMap = MutableStateFlow<Map<String, LocalReadProgress>>(emptyMap())
    
    override suspend fun getProgress(bookId: String): LocalReadProgress? {
        return progressMap.value[bookId]
    }
    
    override suspend fun getProgressBatch(bookIds: List<String>): Map<String, LocalReadProgress> {
        val currentMap = progressMap.value
        return bookIds.mapNotNull { id -> 
            currentMap[id]?.let { id to it }
        }.toMap()
    }
    
    override suspend fun updateProgress(bookId: String, progress: LocalReadProgress) {
        progressMap.value = progressMap.value + (bookId to progress)
    }
    
    override suspend fun markAsCompleted(bookId: String) {
        val current = progressMap.value[bookId]
        if (current != null) {
            progressMap.value = progressMap.value + (bookId to current.copy(
                isCompleted = true,
                currentPage = current.totalPages,
                percentComplete = 100f,
                lastReadTimestamp = System.currentTimeMillis()
            ))
        } else {
            // Create a completed entry even if we didn't have previous progress
            progressMap.value = progressMap.value + (bookId to LocalReadProgress(
                bookId = bookId,
                currentPage = 0,
                totalPages = 0,
                isCompleted = true,
                lastReadTimestamp = System.currentTimeMillis(),
                percentComplete = 100f
            ))
        }
    }
    
    override suspend fun markAsUnread(bookId: String) {
        progressMap.value = progressMap.value - bookId
    }
    
    override suspend fun getInProgressBooks(): List<InProgressBook> {
        return progressMap.value.values
            .filter { !it.isCompleted && it.currentPage > 0 }
            .map { progress ->
                InProgressBook(
                    bookId = progress.bookId,
                    currentPage = progress.currentPage,
                    totalPages = progress.totalPages,
                    lastReadTimestamp = progress.lastReadTimestamp,
                    percentComplete = progress.percentComplete
                )
            }
            .sortedByDescending { it.lastReadTimestamp }
    }
    
    override fun observeProgress(bookId: String): Flow<LocalReadProgress?> {
        return progressMap.map { it[bookId] }
    }
    
    override suspend fun getReadBooksCount(): Int {
        return progressMap.value.count { it.value.isCompleted }
    }
}
