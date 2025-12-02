package io.github.snd_r.komelia.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.snd_r.komelia.AppNotifications
import io.github.snd_r.komelia.server.KomgaTypeConverters
import io.github.snd_r.komelia.server.MediaServer
import io.github.snd_r.komelia.server.ServerId
import io.github.snd_r.komelia.settings.CommonSettingsRepository
import io.github.snd_r.komelia.ui.LoadState
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import snd.komga.client.library.KomgaLibrary
import snd.komga.client.series.KomgaSeries

/**
 * OPDS-specific series tab state that uses MediaServer instead of Komga clients.
 * This is a simplified version without advanced filtering since OPDS doesn't support it.
 */
class OpdsLibrarySeriesTabState(
    private val mediaServer: MediaServer,
    private val notifications: AppNotifications,
    private val settingsRepository: CommonSettingsRepository,
    private val library: StateFlow<KomgaLibrary?>,
    val cardWidth: StateFlow<Dp>,
) : StateScreenModel<LoadState<Unit>>(LoadState.Uninitialized) {
    
    val pageLoadSize = MutableStateFlow(50)
    var series by mutableStateOf<List<KomgaSeries>>(emptyList())
        private set
    var totalSeriesPages by mutableStateOf(1)
        private set
    var totalSeriesCount by mutableStateOf(0)
        private set
    var currentSeriesPage by mutableStateOf(1)
        private set

    val isInEditMode = MutableStateFlow(false)
    var selectedSeries by mutableStateOf<List<KomgaSeries>>(emptyList())
        private set
    
    // Simple search term for OPDS
    var searchTerm by mutableStateOf("")
        private set

    fun initialize(filter: SeriesScreenFilter? = null) {
        if (state.value !is LoadState.Uninitialized) return

        screenModelScope.launch {
            pageLoadSize.value = settingsRepository.getSeriesPageLoadSize().first()
            loadSeriesPage(1)

            settingsRepository.getSeriesPageLoadSize()
                .onEach {
                    if (pageLoadSize.value != it) {
                        pageLoadSize.value = it
                        loadSeriesPage(1)
                    }
                }.launchIn(screenModelScope)
        }
    }

    fun reload() {
        screenModelScope.launch {
            loadSeriesPage(1)
        }
    }

    fun onPageSizeChange(pageSize: Int) {
        pageLoadSize.value = pageSize
        screenModelScope.launch { settingsRepository.putSeriesPageLoadSize(pageSize) }
        notifications.runCatchingToNotifications(screenModelScope) {
            loadSeriesPage(1)
        }
    }

    fun onPageChange(pageNumber: Int) {
        onEditModeChange(false)
        screenModelScope.launch { loadSeriesPage(pageNumber) }
    }

    fun onEditModeChange(editMode: Boolean) {
        this.isInEditMode.value = editMode
        if (!editMode) {
            selectedSeries = emptyList()
        }
    }

    fun onSeriesSelect(series: KomgaSeries) {
        if (selectedSeries.any { it.id == series.id }) {
            selectedSeries = selectedSeries.filter { it.id != series.id }
        } else this.selectedSeries += series

        if (selectedSeries.isNotEmpty() && !isInEditMode.value) onEditModeChange(true)
    }
    
    fun onSearchTermChange(term: String) {
        searchTerm = term
        screenModelScope.launch { loadSeriesPage(1) }
    }

    private suspend fun loadSeriesPage(page: Int) {
        notifications.runCatchingToNotifications {
            val loadStateDelay = delayLoadState()
            currentSeriesPage = page
            
            val libraryId = library.value?.id?.let { ServerId(it.value) }
            
            val seriesPage = if (searchTerm.isNotBlank()) {
                // Use search when there's a search term
                mediaServer.searchSeries(
                    searchTerm = searchTerm,
                    page = page - 1,
                    pageSize = pageLoadSize.value
                )
            } else if (libraryId != null) {
                // Filter by library
                mediaServer.getSeriesInLibrary(
                    libraryId = libraryId,
                    page = page - 1,
                    pageSize = pageLoadSize.value
                )
            } else {
                // Get all recently added series (no library filter)
                mediaServer.getRecentlyAddedSeries(
                    page = page - 1,
                    pageSize = pageLoadSize.value
                )
            }

            loadStateDelay.cancel()

            currentSeriesPage = seriesPage.pageNumber + 1
            totalSeriesPages = seriesPage.totalPages
            totalSeriesCount = seriesPage.totalElements
            series = seriesPage.content.map { KomgaTypeConverters.serverSeriesToKomgaSeries(it) }
            mutableState.value = LoadState.Success(Unit)
        }.onFailure { mutableState.value = LoadState.Error(it) }
    }

    private fun delayLoadState(): Deferred<Unit> {
        return screenModelScope.async {
            delay(200)
            if (state.value !is LoadState.Error) mutableState.value = LoadState.Loading
        }
    }
}
