package com.dvgame.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dvgame.app.data.SelectionStore
import com.dvgame.app.data.SubscriptionRepository
import com.dvgame.app.data.SubscriptionSnapshot
import com.dvgame.app.model.AppScreen
import com.dvgame.app.model.ConnectionChoice
import com.dvgame.app.model.DvSubscription
import com.dvgame.app.model.InstalledGame
import com.dvgame.app.model.ServerProfile
import com.dvgame.app.model.TunnelStatus
import com.dvgame.app.update.UpdateManifest
import com.dvgame.app.update.UpdateService
import com.dvgame.app.vpn.TunnelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class AppUiState(
    val screen: AppScreen = AppScreen.HOME,
    val link: String = "",
    val subscription: DvSubscription? = null,
    val installedGames: List<InstalledGame> = emptyList(),
    val selectedGamePackage: String? = null,
    val selectedServerId: String? = null,
    val autoLaunch: Boolean = true,
    val loading: Boolean = true,
    val fromCache: Boolean = false,
    val message: String = "در حال آماده‌سازی…",
    val mirrorUrl: String = "",
    val updateStatus: String = "",
    val availableUpdate: UpdateManifest? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val subscriptions = SubscriptionRepository(application)
    private val tunnel: TunnelRepository = (application as DvGameApplication).tunnelRepository
    private val store = SelectionStore(application)
    private val updates = UpdateService(application)
    private val mutableUi = MutableStateFlow(
        AppUiState(link = subscriptions.savedLink(), autoLaunch = subscriptions.autoLaunchGame(), mirrorUrl = store.loadUpdateMirrorUrl())
    )
    val ui: StateFlow<AppUiState> = mutableUi.asStateFlow()
    val tunnelStatus = tunnel.status
    val telemetry = tunnel.telemetry

    init {
        viewModelScope.launch {
            val cached = subscriptions.restore()
            if (cached != null) applySnapshot(cached, "اطلاعات ذخیره‌شده آماده است")
            else mutableUi.update { it.copy(loading = false, message = "لینک اشتراک را وارد کنید") }
            if (mutableUi.value.link.isNotBlank() && subscriptions.shouldRefresh()) refresh(silent = cached != null)
        }
    }

    fun setScreen(value: AppScreen) = mutableUi.update { it.copy(screen = value) }
    fun setLink(value: String) = mutableUi.update { it.copy(link = value.trim()) }

    fun selectGame(packageName: String) {
        if (selectionLocked()) return
        mutableUi.update { it.copy(selectedGamePackage = packageName) }
        persistChoice()
    }

    fun selectServer(id: String) {
        if (selectionLocked()) return
        mutableUi.update { it.copy(selectedServerId = id) }
        persistChoice()
    }

    fun setAutoLaunch(enabled: Boolean) {
        mutableUi.update { it.copy(autoLaunch = enabled) }
        persistChoice()
    }

    fun onForeground() {
        if (mutableUi.value.link.isNotBlank() && subscriptions.shouldRefresh()) refresh(silent = true)
    }

    fun refresh(silent: Boolean = false) {
        val link = mutableUi.value.link
        if (link.isBlank()) {
            mutableUi.update { it.copy(loading = false, message = "لینک اشتراک را وارد کنید") }
            return
        }
        viewModelScope.launch {
            if (!silent) mutableUi.update { it.copy(loading = true, message = "در حال دریافت اشتراک…") }
            runCatching { subscriptions.refresh(link) }
                .onSuccess { applySnapshot(it, "اشتراک به‌روز شد") }
                .onFailure { error -> mutableUi.update { state ->
                    state.copy(loading = false, message = friendlyError(error))
                } }
        }
    }

    fun connectionChoice(): ConnectionChoice? {
        val state = mutableUi.value
        val subscription = state.subscription ?: return fail("ابتدا اشتراک را دریافت کنید")
        subscription.account.connectionBlockReason(subscription.serverTimeMs)?.let { return fail(it) }
        val profile = subscription.profiles.firstOrNull { it.id == state.selectedServerId }
            ?: return fail("یک سرور انتخاب کنید")
        val game = state.installedGames.firstOrNull { it.packageName == state.selectedGamePackage }
            ?: return fail("یک بازی نصب‌شده انتخاب کنید")
        return ConnectionChoice(subscription.account, profile, game, state.autoLaunch)
    }

    suspend fun connect(choice: ConnectionChoice) {
        val now = System.currentTimeMillis()
        tunnel.connect(
            rawConfig = choice.profile.config,
            approvedPackage = choice.game.packageName,
            restoreValidUntilMs = choice.account.localRestoreValidUntilMs(now),
            serverName = choice.profile.name,
        )
        mutableUi.update { it.copy(message = "اتصال برقرار شد") }
    }

    fun disconnect() = viewModelScope.launch {
        runCatching { tunnel.disconnect() }
            .onSuccess { mutableUi.update { it.copy(message = "اتصال قطع شد") } }
            .onFailure { mutableUi.update { state -> state.copy(message = friendlyError(it)) } }
    }

    fun reset() = viewModelScope.launch {
        runCatching { tunnel.disconnect(forget = true) }
        subscriptions.reset()
        mutableUi.value = AppUiState(loading = false, message = "اطلاعات برنامه پاک شد")
    }

    fun report(message: String) = mutableUi.update { it.copy(message = message) }

    fun setMirrorUrl(value: String) {
        val trimmed = value.trim()
        mutableUi.update { it.copy(mirrorUrl = trimmed) }
        store.saveUpdateMirrorUrl(trimmed)
    }

    fun checkForUpdate() = viewModelScope.launch {
        mutableUi.update { it.copy(updateStatus = "در حال بررسی نسخه جدید…", availableUpdate = null) }
        runCatching { updates.check(manifestSources()) }
            .onSuccess { manifest -> mutableUi.update { state ->
                val newer = manifest.isNewerThan(updates.currentVersionCode())
                state.copy(
                    availableUpdate = if (newer) manifest else null,
                    updateStatus = if (newer) "نسخه ${manifest.versionName} آماده نصب است" else "برنامه به‌روز است",
                )
            } }
            .onFailure { error -> mutableUi.update { it.copy(updateStatus = friendlyError(error)) } }
    }

    suspend fun downloadUpdate(manifest: UpdateManifest): File {
        mutableUi.update { it.copy(updateStatus = "در حال دانلود نسخه ${manifest.versionName}…") }
        return runCatching { updates.download(manifest) }
            .onSuccess { mutableUi.update { state -> state.copy(updateStatus = "فایل بررسی شد؛ نصب را تأیید کنید") } }
            .onFailure { error -> mutableUi.update { state -> state.copy(updateStatus = friendlyError(error)) } }
            .getOrThrow()
    }

    fun canInstallUpdates(): Boolean = updates.canInstall()

    private fun manifestSources(): List<String> = buildList {
        add(GITHUB_UPDATE_MANIFEST)
        val mirror = mutableUi.value.mirrorUrl
        if (UpdateManifest.isHttps(mirror)) add(mirror)
    }

    private fun applySnapshot(snapshot: SubscriptionSnapshot, message: String) {
        val old = mutableUi.value
        val game = snapshot.installedGames.firstOrNull { it.packageName == subscriptions.lastGamePackage() }
            ?: snapshot.installedGames.firstOrNull()
        val server = snapshot.subscription.profiles.firstOrNull { it.id == subscriptions.lastServerId() }
            ?: snapshot.subscription.profiles.firstOrNull()
        val blockReason = snapshot.subscription.account.connectionBlockReason(snapshot.subscription.serverTimeMs)
        mutableUi.value = old.copy(
            subscription = snapshot.subscription,
            installedGames = if (blockReason == null) snapshot.installedGames else emptyList(),
            selectedGamePackage = game?.packageName,
            selectedServerId = server?.id,
            loading = false,
            fromCache = snapshot.fromCache,
            message = blockReason ?: if (snapshot.installedGames.isEmpty()) "بازی مجاز نصب‌شده‌ای پیدا نشد" else message,
        )
        persistChoice()
    }

    private fun persistChoice() = subscriptions.saveChoice(
        mutableUi.value.selectedGamePackage,
        mutableUi.value.selectedServerId,
        mutableUi.value.autoLaunch,
    )

    private fun selectionLocked(): Boolean = tunnelStatus.value !is TunnelStatus.Idle

    private fun fail(message: String): Nothing? {
        report(message)
        return null
    }

    private fun friendlyError(error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("timed out", true) || text.contains("timeout", true) ->
                "سرور پاسخ نداد؛ دوباره تلاش کنید"
            text.contains("Unable to resolve", true) || text.contains("UnknownHost", true) ->
                "اتصال اینترنت یا DNS را بررسی کنید"
            text.contains("SSL", true) || text.contains("trust anchor", true) ->
                "ارتباط امن برقرار نشد؛ ساعت و شبکه دستگاه را بررسی کنید"
            text.contains("401") || text.contains("403") ->
                "لینک اشتراک معتبر نیست یا دسترسی ندارد"
            text.contains("404") ->
                "آدرس درخواستی پیدا نشد"
            text.contains("500") || text.contains("502") || text.contains("503") ->
                "سرور موقتاً در دسترس نیست؛ کمی بعد دوباره تلاش کنید"
            text.contains("apiVersion", true) || text.contains("JSON", true) ->
                "پاسخ سرور قابل خواندن نبود؛ نسخه برنامه یا پنل را بررسی کنید"
            text.isBlank() -> "خطای ناشناخته رخ داد"
            else -> text
        }
    }

    companion object {
        const val GITHUB_UPDATE_MANIFEST =
            "https://github.com/dalmaktube-creator/dv-game-android/releases/latest/download/dv-game-update.json"
    }
}
