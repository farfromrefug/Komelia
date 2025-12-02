package io.github.snd_r.komelia.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.snd_r.komelia.AppNotifications
import io.github.snd_r.komelia.server.MediaServer
import io.github.snd_r.komelia.settings.CommonSettingsRepository
import io.github.snd_r.komelia.ui.LoadState
import io.github.snd_r.komelia.ui.LoadState.Error
import io.github.snd_r.komelia.ui.LoadState.Loading
import io.github.snd_r.komelia.ui.LoadState.Success
import io.github.snd_r.komelia.ui.LoadState.Uninitialized
import io.github.snd_r.komelia.ui.common.cards.defaultCardWidth
import io.github.snd_r.komelia.ui.common.menus.LibraryMenuActions
import io.github.snd_r.komelia.ui.library.LibraryTab.COLLECTIONS
import io.github.snd_r.komelia.ui.library.LibraryTab.READ_LISTS
import io.github.snd_r.komelia.ui.library.LibraryTab.SERIES
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import snd.komga.client.collection.KomgaCollectionClient
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.library.KomgaLibrary
import snd.komga.client.library.KomgaLibraryClient
import snd.komga.client.readlist.KomgaReadListClient
import snd.komga.client.referential.KomgaReferentialClient
import snd.komga.client.series.KomgaSeriesClient
import snd.komga.client.sse.KomgaEvent
import snd.komga.client.sse.KomgaEvent.CollectionAdded
import snd.komga.client.sse.KomgaEvent.CollectionDeleted
import snd.komga.client.sse.KomgaEvent.ReadListAdded
import snd.komga.client.sse.KomgaEvent.ReadListDeleted

class LibraryViewModel(
    private val libraryClient: KomgaLibraryClient?,
    private val collectionClient: KomgaCollectionClient?,
    private val readListsClient: KomgaReadListClient?,
    seriesClient: KomgaSeriesClient?,
    referentialClient: KomgaReferentialClient?,

    private val appNotifications: AppNotifications,
    private val komgaEvents: SharedFlow<KomgaEvent>?,
    libraryFlow: Flow<KomgaLibrary?>,
    settingsRepository: CommonSettingsRepository,
    private val mediaServer: MediaServer? = null,
    private val isOpdsMode: Boolean = false,
) : StateScreenModel<LoadState<Unit>>(Uninitialized) {
    val library = libraryFlow.stateIn(screenModelScope, SharingStarted.Eagerly, null)
    val cardWidth = settingsRepository.getCardWidth().map { Dp(it.toFloat()) }
        .stateIn(screenModelScope, SharingStarted.Eagerly, defaultCardWidth.dp)

    var currentTab by mutableStateOf(SERIES)
    var collectionsCount by mutableStateOf(0)
        private set
    var readListsCount by mutableStateOf(0)
        private set

    private val reloadEventsEnabled = MutableStateFlow(true)
    private val reloadJobsFlow = MutableSharedFlow<Unit>(1, 0, DROP_OLDEST)

    val seriesTabState: LibrarySeriesTabState? = if (!isOpdsMode && seriesClient != null && referentialClient != null && komgaEvents != null) {
        LibrarySeriesTabState(
            seriesClient = seriesClient,
            referentialClient = referentialClient,
            notifications = appNotifications,
            komgaEvents = komgaEvents,
            settingsRepository = settingsRepository,
            library = library,
            cardWidth = cardWidth,
        )
    } else null
    
    val opdsSeriesTabState: OpdsLibrarySeriesTabState? = if (isOpdsMode && mediaServer != null) {
        OpdsLibrarySeriesTabState(
            mediaServer = mediaServer,
            notifications = appNotifications,
            settingsRepository = settingsRepository,
            library = library,
            cardWidth = cardWidth,
        )
    } else null
    
    val collectionsTabState: LibraryCollectionsTabState? = if (!isOpdsMode && collectionClient != null && komgaEvents != null) {
        LibraryCollectionsTabState(
            collectionClient = collectionClient,
            appNotifications = appNotifications,
            events = komgaEvents,
            library = library,
            cardWidth = cardWidth
        )
    } else null
    
    val readListsTabState: LibraryReadListsTabState? = if (!isOpdsMode && readListsClient != null && komgaEvents != null) {
        LibraryReadListsTabState(
            readListClient = readListsClient,
            appNotifications = appNotifications,
            komgaEvents = komgaEvents,
            library = library,
            cardWidth = cardWidth
        )
    } else null
    
    val showToolbar = if (seriesTabState != null) {
        seriesTabState.isInEditMode.map { !it }
            .stateIn(screenModelScope, SharingStarted.Eagerly, true)
    } else {
        MutableStateFlow(true)
    }

    fun initialize(seriesFilter: SeriesScreenFilter? = null) {
        if (state.value !is Uninitialized) return

        if (seriesFilter != null) toBrowseTab()

        screenModelScope.launch { loadItemCounts() }
        if (!isOpdsMode && komgaEvents != null) {
            startKomgaEventListener()
        }

        reloadJobsFlow.onEach {
            reloadEventsEnabled.first { it }
            loadItemCounts()
            delay(1000)
        }.launchIn(screenModelScope)
    }

    fun reload() {
        mutableState.value = Loading
        screenModelScope.launch {
            loadItemCounts()
            when (currentTab) {
                SERIES -> {
                    seriesTabState?.reload()
                    opdsSeriesTabState?.reload()
                }
                COLLECTIONS -> collectionsTabState?.reload()
                READ_LISTS -> readListsTabState?.reload()
            }
        }
    }

    private suspend fun loadItemCounts() {
        if (state.value is Error) return

        appNotifications.runCatchingToNotifications {
            mutableState.value = Loading
            
            if (isOpdsMode) {
                // OPDS mode: Collections and ReadLists not available
                collectionsCount = 0
                readListsCount = 0
                currentTab = SERIES
            } else if (collectionClient != null && readListsClient != null) {
                val pageRequest = KomgaPageRequest(size = 0)
                val libraryIds = listOfNotNull(library.value?.id)
                collectionsCount = collectionClient.getAll(libraryIds = libraryIds, pageRequest = pageRequest).totalElements
                readListsCount = readListsClient.getAll(libraryIds = libraryIds, pageRequest = pageRequest).totalElements

                if (collectionsCount == 0 && currentTab == COLLECTIONS) currentTab = SERIES
                if (readListsCount == 0 && currentTab == READ_LISTS) currentTab = SERIES
            }
            mutableState.value = Success(Unit)
        }.onFailure { mutableState.value = Error(it) }
    }

    fun toBrowseTab() {
        currentTab = SERIES
    }

    fun toCollectionsTab() {
        if (!isOpdsMode) currentTab = COLLECTIONS
    }

    fun toReadListsTab() {
        if (!isOpdsMode) currentTab = READ_LISTS
    }

    fun libraryActions(): LibraryMenuActions? {
        return if (!isOpdsMode && libraryClient != null) {
            LibraryMenuActions(libraryClient, appNotifications, screenModelScope)
        } else null
    }

    fun stopKomgaEventHandler() {
        reloadEventsEnabled.value = false
    }

    fun startKomgaEventHandler() {
        reloadEventsEnabled.value = true

    }

    private fun startKomgaEventListener() {
        komgaEvents?.onEach { event ->
            when (event) {
                is ReadListAdded, is ReadListDeleted -> reloadJobsFlow.tryEmit(Unit)
                is CollectionAdded, is CollectionDeleted -> reloadJobsFlow.tryEmit(Unit)

                else -> {}
            }
        }?.launchIn(screenModelScope)
    }
}

enum class LibraryTab {
    SERIES,
    COLLECTIONS,
    READ_LISTS
}

