package io.github.snd_r.komelia.ui.series

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.snd_r.komelia.AppNotifications
import io.github.snd_r.komelia.server.KomgaTypeConverters
import io.github.snd_r.komelia.server.MediaServer
import io.github.snd_r.komelia.server.ServerId
import io.github.snd_r.komelia.settings.CommonSettingsRepository
import io.github.snd_r.komelia.ui.LoadState
import io.github.snd_r.komelia.ui.LoadState.Error
import io.github.snd_r.komelia.ui.LoadState.Loading
import io.github.snd_r.komelia.ui.LoadState.Success
import io.github.snd_r.komelia.ui.LoadState.Uninitialized
import io.github.snd_r.komelia.ui.common.cards.defaultCardWidth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import snd.komga.client.library.KomgaLibrary
import snd.komga.client.series.KomgaSeries
import snd.komga.client.series.KomgaSeriesId
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly

/**
 * OPDS-specific SeriesViewModel that uses MediaServer instead of Komga clients.
 * This is a simplified version without collections support (OPDS doesn't have it).
 */
class OpdsSeriesViewModel(
    series: KomgaSeries?,
    private val libraries: StateFlow<List<KomgaLibrary>>,
    private val seriesId: KomgaSeriesId,
    private val notifications: AppNotifications,
    private val mediaServer: MediaServer,
    settingsRepository: CommonSettingsRepository,
) : StateScreenModel<LoadState<Unit>>(Uninitialized) {

    val series = MutableStateFlow(series?.withSortedTags())
    val library = MutableStateFlow<KomgaLibrary?>(null)
    
    // No tabs in OPDS mode - just books
    var currentTab by mutableStateOf(SeriesViewModel.SeriesTab.BOOKS)
        private set
    
    val cardWidth = settingsRepository.getCardWidth().map { it.dp }
        .stateIn(screenModelScope, Eagerly, defaultCardWidth.dp)

    val booksState = OpdsSeriesBooksState(
        series = this.series,
        settingsRepository = settingsRepository,
        notifications = notifications,
        mediaServer = mediaServer,
        screenModelScope = screenModelScope,
        cardWidth = cardWidth,
    )
    
    // No collections in OPDS mode
    val collectionsState: Any? = null

    suspend fun initialize() {
        if (state.value !is Uninitialized) return

        val providedSeries = series.value
        if (providedSeries == null) loadSeries()
        else {
            runCatching {
                library.value = getLibraryOrThrow(providedSeries)
                mutableState.value = Success(Unit)
            }.onFailure { mutableState.value = Error(it) }
        }

        series.filterNotNull().combine(libraries) { series, libraries ->
            val newLibrary = libraries.firstOrNull { it.id == series.libraryId }
            if (newLibrary == null) {
                mutableState.value =
                    Error(IllegalStateException("Failed to find library for series ${series.metadata.title}"))
            }
            library.value = newLibrary
        }.launchIn(screenModelScope)

        booksState.initialize()
    }

    fun reload() {
        screenModelScope.launch {
            mutableState.value = Loading
            loadSeries()
            booksState.reload()
        }
    }

    // No menu actions for OPDS (read-only)
    fun seriesMenuActions() = null

    fun onTabChange(tab: SeriesViewModel.SeriesTab) {
        // Only BOOKS tab available in OPDS mode
        this.currentTab = SeriesViewModel.SeriesTab.BOOKS
    }

    private suspend fun loadSeries() {
        notifications.runCatchingToNotifications {
            mutableState.value = Loading
            val serverSeries = mediaServer.getSeries(ServerId(seriesId.value))
            val komgaSeries = KomgaTypeConverters.serverSeriesToKomgaSeries(serverSeries)
            this.series.value = komgaSeries.withSortedTags()
            this.library.value = getLibraryOrThrow(komgaSeries)

            mutableState.value = Success(Unit)

        }.onFailure { mutableState.value = Error(it) }
    }

    private fun getLibraryOrThrow(series: KomgaSeries): KomgaLibrary {
        val library = this.libraries.value.firstOrNull { it.id == series.libraryId }
        if (library == null) {
            throw IllegalStateException("Failed to find library for series ${series.metadata.title}")
        }
        return library
    }

    // No SSE events in OPDS mode
    fun stopKomgaEventHandler() {
        booksState.stopKomgaEventHandler()
    }

    fun startKomgaEventHandler() {
        booksState.startKomgaEventHandler()
    }

    private fun KomgaSeries.withSortedTags() = this.copy(
        metadata = this.metadata.copy(
            tags = this.metadata.tags.sorted(),
            genres = this.metadata.genres.sorted()
        )
    )
}
