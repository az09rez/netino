package com.netino.vpn.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.netino.vpn.data.Repository
import java.util.concurrent.TimeUnit

/**
 * Auto-updates subscriptions in the background. Runs every 30 minutes (when online) and refreshes
 * only the subscriptions whose own period (1 / 6 / 12 / 24 h) has elapsed.
 */
class SubscriptionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Repository.refreshDueSubscriptions()
        SubscriptionAlerts.check(applicationContext)
        // The optional Iran / ads lists: weekly, and only on Wi-Fi (they are ~25 MB)
        val s = Repository.settings.value
        val cm = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
        if (s.geoUpdatedAt > 0 && System.currentTimeMillis() - s.geoUpdatedAt > 7 * 86_400_000L && cm?.isActiveNetworkMetered == false) {
            // Used from the next connection on: no reconnect in the background just for this
            com.netino.vpn.core.GeoFiles.update().onSuccess { Repository.updateSettings { it.copy(geoUpdatedAt = System.currentTimeMillis()) } }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "subscription-auto-update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
