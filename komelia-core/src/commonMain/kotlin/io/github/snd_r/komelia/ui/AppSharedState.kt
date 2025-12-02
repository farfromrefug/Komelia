package io.github.snd_r.komelia.ui

import io.github.snd_r.komelia.server.MediaServer
import io.github.snd_r.komelia.server.ServerLibrary
import io.github.snd_r.komelia.server.ServerType
import io.github.snd_r.komelia.server.ServerUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import snd.komga.client.library.KomgaLibrary
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.library.ScanInterval
import snd.komga.client.user.KomgaUser
import snd.komga.client.user.KomgaUserId

/**
 * Unified app state that works with both Komga and OPDS backends.
 * This provides a bridge between the new MediaServer abstraction and the existing
 * Komga-based UI code.
 */
class AppSharedState {
    private val _serverType = MutableStateFlow<ServerType>(ServerType.KOMGA)
    private val _mediaServer = MutableStateFlow<MediaServer?>(null)
    private val _serverUser = MutableStateFlow<ServerUser?>(null)
    private val _serverLibraries = MutableStateFlow<List<ServerLibrary>>(emptyList())
    
    // Compatibility layer for existing Komga-based code
    private val _authenticatedUser = MutableStateFlow<KomgaUser?>(null)
    private val _libraries = MutableStateFlow<List<KomgaLibrary>>(emptyList())
    private val _state = MutableStateFlow<DataState>(DataState.AuthenticationRequired)
    
    val serverType = _serverType.asStateFlow()
    val mediaServer = _mediaServer.asStateFlow()
    val serverUser = _serverUser.asStateFlow()
    val serverLibraries = _serverLibraries.asStateFlow()
    
    // Compatibility accessors for existing Komga-based code
    val authenticatedUser = _authenticatedUser.asStateFlow()
    val libraries = _libraries.asStateFlow()
    val state = _state.asStateFlow()
    
    val isOpdsMode: Boolean
        get() = _serverType.value == ServerType.OPDS
    
    val isKomgaMode: Boolean
        get() = _serverType.value == ServerType.KOMGA
    
    /**
     * Set state for OPDS server connection
     */
    fun setOpdsState(
        mediaServer: MediaServer,
        user: ServerUser,
        libraries: List<ServerLibrary>
    ) {
        _serverType.value = ServerType.OPDS
        _mediaServer.value = mediaServer
        _serverUser.value = user
        _serverLibraries.value = libraries
        
        // Create compatibility objects for existing UI code
        _authenticatedUser.value = serverUserToKomgaUser(user)
        _libraries.value = libraries.map { serverLibraryToKomgaLibrary(it) }
        _state.value = DataState.Loaded
    }
    
    /**
     * Set state for Komga server connection (existing behavior)
     */
    fun setKomgaState(user: KomgaUser, libraries: List<KomgaLibrary>) {
        _serverType.value = ServerType.KOMGA
        _authenticatedUser.value = user
        _libraries.value = libraries
        _state.value = DataState.Loaded
        
        // Also update the abstraction layer
        _serverUser.value = komgaUserToServerUser(user)
        _serverLibraries.value = libraries.map { komgaLibraryToServerLibrary(it) }
    }
    
    fun updateLibraries(libraries: List<KomgaLibrary>) {
        _libraries.value = libraries
        _serverLibraries.value = libraries.map { komgaLibraryToServerLibrary(it) }
    }
    
    fun updateServerLibraries(libraries: List<ServerLibrary>) {
        _serverLibraries.value = libraries
        _libraries.value = libraries.map { serverLibraryToKomgaLibrary(it) }
    }
    
    fun reset() {
        _serverType.value = ServerType.KOMGA
        _mediaServer.value = null
        _serverUser.value = null
        _serverLibraries.value = emptyList()
        _authenticatedUser.value = null
        _libraries.value = emptyList()
        _state.value = DataState.AuthenticationRequired
    }
    
    // Conversion helpers
    private fun serverUserToKomgaUser(user: ServerUser): KomgaUser {
        return KomgaUser(
            id = KomgaUserId(user.id.value),
            email = user.email,
            roles = user.roles.toSet(),
            sharedAllLibraries = true,
            sharedLibrariesIds = emptySet(),
            labelsAllow = emptySet(),
            labelsExclude = emptySet(),
            ageRestriction = null,
        )
    }
    
    private fun komgaUserToServerUser(user: KomgaUser): ServerUser {
        return ServerUser(
            id = io.github.snd_r.komelia.server.ServerId(user.id.value),
            email = user.email,
            isAdmin = user.roles.contains("ADMIN"),
            roles = user.roles.toSet()
        )
    }
    
    private fun serverLibraryToKomgaLibrary(library: ServerLibrary): KomgaLibrary {
        return KomgaLibrary(
            id = KomgaLibraryId(library.id.value),
            name = library.name,
            root = "",
            importComicInfoBook = false,
            importComicInfoSeries = false,
            importComicInfoCollection = false,
            importComicInfoReadList = false,
            importComicInfoSeriesAppendVolume = false,
            importEpubBook = false,
            importEpubSeries = false,
            importMylarSeries = false,
            importLocalArtwork = false,
            importBarcodeIsbn = false,
            scanForceModifiedTime = false,
            scanInterval = ScanInterval.DISABLED,
            scanOnStartup = false,
            scanCbx = false,
            scanPdf = false,
            scanEpub = false,
            scanDirectoryExclusions = emptyList(),
            repairExtensions = false,
            convertToCbz = false,
            emptyTrashAfterScan = false,
            seriesCover = snd.komga.client.library.SeriesCover.FIRST,
            hashFiles = false,
            hashPages = false,
            analyzeDimensions = false,
            unavailable = library.unavailable,
            oneshotsDirectory = null
        )
    }
    
    private fun komgaLibraryToServerLibrary(library: KomgaLibrary): ServerLibrary {
        return ServerLibrary(
            id = io.github.snd_r.komelia.server.ServerId(library.id.value),
            name = library.name,
            unavailable = library.unavailable
        )
    }
    
    sealed interface DataState {
        data object Loaded : DataState
        data object AuthenticationRequired : DataState
    }
}
