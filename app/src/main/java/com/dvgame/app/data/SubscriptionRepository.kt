package com.dvgame.app.data

import android.content.Context
import com.dvgame.app.model.DvSubscription
import com.dvgame.app.model.InstalledGame
import com.dvgame.app.net.SubscriptionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SubscriptionSnapshot(
    val subscription: DvSubscription,
    val installedGames: List<InstalledGame>,
    val fromCache: Boolean,
)

class SubscriptionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = SelectionStore(appContext)

    fun savedLink(): String = store.loadSubscriptionLink()
    fun lastGamePackage(): String? = store.loadLastGamePackage()
    fun lastServerId(): String? = store.loadLastServerId()
    fun autoLaunchGame(): Boolean = store.loadAutoLaunchGame()

    fun saveChoice(gamePackage: String?, serverId: String?, autoLaunch: Boolean) {
        store.saveLastGamePackage(gamePackage)
        store.saveLastServerId(serverId)
        store.saveAutoLaunchGame(autoLaunch)
    }

    fun shouldRefresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - store.loadFetchedAt() >= REFRESH_AFTER_MS

    suspend fun restore(): SubscriptionSnapshot? = withContext(Dispatchers.IO) {
        val raw = store.loadCachedSubscription() ?: return@withContext null
        runCatching { snapshot(SubscriptionClient.parse(raw), true) }.getOrNull()
    }

    suspend fun refresh(link: String): SubscriptionSnapshot = withContext(Dispatchers.IO) {
        val cleanLink = link.trim()
        require(cleanLink.isNotBlank()) { "لینک اشتراک را وارد کنید" }
        val fetched = SubscriptionClient.fetchPayload(cleanLink)
        store.saveSubscriptionLink(cleanLink)
        store.saveCachedSubscription(fetched.raw)
        store.saveFetchedAt(System.currentTimeMillis())
        snapshot(fetched.value, false)
    }

    fun reset() = store.resetProductState()

    private fun snapshot(subscription: DvSubscription, fromCache: Boolean) = SubscriptionSnapshot(
        subscription = subscription,
        installedGames = GameScanner.findApprovedInstalledGames(appContext, subscription.games),
        fromCache = fromCache,
    )

    private companion object {
        const val REFRESH_AFTER_MS = 15 * 60 * 1000L
    }
}
