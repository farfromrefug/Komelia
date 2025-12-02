package io.github.snd_r.komelia.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.snd_r.komelia.AppNotification
import io.github.snd_r.komelia.AppNotifications
import io.github.snd_r.komelia.opds.client.HttpOpdsClient
import io.github.snd_r.komelia.platform.PlatformType
import io.github.snd_r.komelia.platform.PlatformType.DESKTOP
import io.github.snd_r.komelia.platform.PlatformType.MOBILE
import io.github.snd_r.komelia.platform.PlatformType.WEB_KOMF
import io.github.snd_r.komelia.server.OpdsMediaServer
import io.github.snd_r.komelia.server.ServerType
import io.github.snd_r.komelia.server.createDetectionHttpClient
import io.github.snd_r.komelia.settings.CommonSettingsRepository
import io.github.snd_r.komelia.settings.SecretsRepository
import io.github.snd_r.komelia.ui.AppSharedState
import io.github.snd_r.komelia.ui.KomgaSharedState
import io.github.snd_r.komelia.ui.LoadState
import io.github.snd_r.komelia.ui.LoadState.Uninitialized
import io.github.snd_r.komelia.ui.error.formatExceptionMessage
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.http.HttpStatusCode.Companion.Unauthorized
import io.ktor.utils.io.*
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import snd.komga.client.library.KomgaLibrary
import snd.komga.client.library.KomgaLibraryClient
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.library.ScanInterval
import snd.komga.client.library.SeriesCover
import snd.komga.client.user.KomgaUser
import snd.komga.client.user.KomgaUserClient
import snd.komga.client.user.KomgaUserId

class LoginViewModel(
    private val settingsRepository: CommonSettingsRepository,
    private val secretsRepository: SecretsRepository,
    private val komgaUserClient: KomgaUserClient,
    private val komgaLibraryClient: KomgaLibraryClient,
    private val komgaSharedState: KomgaSharedState,
    private val notifications: AppNotifications,
    private val platform: PlatformType,
    private val appSharedState: AppSharedState? = null,
) : StateScreenModel<LoadState<Unit>>(Uninitialized) {

    var url by mutableStateOf("")
    var user by mutableStateOf("")
    var password by mutableStateOf("")
    var mServerType by mutableStateOf(ServerType.KOMGA)
    var userLoginError by mutableStateOf<String?>(null)
    var autoLoginError by mutableStateOf<String?>(null)

    fun initialize() {
        if (state.value !is Uninitialized) return

        screenModelScope.launch {
            url = settingsRepository.getServerUrl().first()
            user = settingsRepository.getCurrentUser().first()
            mServerType = settingsRepository.getServerType().first()
            when (platform) {
                MOBILE, DESKTOP -> {
                    if (secretsRepository.getCookie(url) != null) {
                        tryAutologin()
                    } else {
                        mutableState.value = LoadState.Error(RuntimeException("Not logged in"))
                    }
                }

                WEB_KOMF -> tryAutologin()
            }
        }
    }
    
    fun setServerType(type: ServerType) {
        mServerType = type
        screenModelScope.launch {
            settingsRepository.putServerType(type)
        }
    }

    fun retryAutoLogin() {
        screenModelScope.launch {
            mutableState.value = LoadState.Loading
            tryAutologin()
        }
    }

    fun cancel() {
        screenModelScope.coroutineContext.cancelChildren()
        mutableState.value = LoadState.Error(RuntimeException("Cancelled login attempt"))
        userLoginError = "Cancelled login attempt"
    }

    fun loginWithCredentials() {
        screenModelScope.launch {
            userLoginError = null
            settingsRepository.putServerUrl(url)
            settingsRepository.putCurrentUser(user)
            settingsRepository.putServerType(mServerType)
            tryUserLogin(user, password)
        }
    }

    private suspend fun tryAutologin() {
        try {
            tryLogin()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoTransformationFoundException) {
            val message = "Unexpected response for url $url"
            autoLoginError = message
            notifications.add(AppNotification.Error(message))
            mutableState.value = LoadState.Error(e)
        } catch (e: ClientRequestException) {
            if (e.response.status == Unauthorized) {
                autoLoginError = null
            } else {
                autoLoginError = "Login error: ${e::class.simpleName} ${e.message}"
                notifications.add(AppNotification.Error(e.message))
            }
            mutableState.value = LoadState.Error(e)
        } catch (e: Error) { // wasm fetch error
            val errorMessage = "Login error: ${e::class.simpleName} ${e.message}"
            mutableState.value = LoadState.Error(e)
            notifications.add(AppNotification.Error(errorMessage))
        } catch (e: Throwable) {
            val errorMessage = "Login error: ${e::class.simpleName} ${e.message}"
            autoLoginError = errorMessage
            mutableState.value = LoadState.Error(e)
            notifications.add(AppNotification.Error(errorMessage))
        }
    }

    private suspend fun tryUserLogin(username: String, password: String) {
        try {
            tryLogin(username, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoTransformationFoundException) {
            val message = "Unexpected response for url $url"
            userLoginError = message
            mutableState.value = LoadState.Error(e)
        } catch (e: ClientRequestException) {
            userLoginError = if (e.response.status == Unauthorized) "Invalid credentials"
            else "Login error ${e::class.simpleName}: ${e.message}"
            mutableState.value = LoadState.Error(e)
        } catch (e: Throwable) {
            userLoginError = formatExceptionMessage(e)
            mutableState.value = LoadState.Error(e)
        }
    }

    private suspend fun tryLogin(
        username: String? = null,
        password: String? = null
    ) {
        when (mServerType) {
            ServerType.KOMGA -> tryKomgaLogin(username, password)
            ServerType.OPDS -> tryOpdsLogin(username, password)
        }
    }
    
    private suspend fun tryKomgaLogin(
        username: String? = null,
        password: String? = null
    ) {
        val user =
            if (username != null && password != null) komgaUserClient.getMe(username, password, true)
            else komgaUserClient.getMe()

        val libraries = komgaLibraryClient.getLibraries()
        komgaSharedState.setStateValues(user, libraries)
        appSharedState?.setKomgaState(user, libraries)
        mutableState.value = LoadState.Success(Unit)
    }
    
    private suspend fun tryOpdsLogin(
        username: String? = null,
        password: String? = null
    ) {
        // Create OPDS client and server dynamically based on current URL and credentials
        val client = createDetectionHttpClient()
        val opdsClient = HttpOpdsClient(
            baseUrl = url,
            username = username,
            password = password,
            httpClient = client
        )
        val server = OpdsMediaServer(opdsClient, "OPDS Server")
        
        // Get user and libraries from OPDS server
        val serverUser = server.getCurrentUser()
        val serverLibraries = server.getLibraries()
        
        // Update the shared state for OPDS mode
        appSharedState?.setOpdsState(server, serverUser, serverLibraries)
        
        // Also update the Komga shared state for compatibility with existing UI code
        val komgaUser = KomgaUser(
            id = KomgaUserId(serverUser.id.value),
            email = serverUser.email,
            roles = serverUser.roles.toSet(),
            sharedAllLibraries = true,
            sharedLibrariesIds = emptySet(),
            labelsAllow = emptySet(),
            labelsExclude = emptySet(),
            ageRestriction = null,
        )
        val komgaLibraries = serverLibraries.map { lib ->
            KomgaLibrary(
                id = KomgaLibraryId(lib.id.value),
                name = lib.name,
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
                seriesCover = SeriesCover.FIRST,
                hashFiles = false,
                hashPages = false,
                analyzeDimensions = false,
                unavailable = lib.unavailable,
                oneshotsDirectory = null
            )
        }
        komgaSharedState.setStateValues(komgaUser, komgaLibraries)
        
        mutableState.value = LoadState.Success(Unit)
    }
}