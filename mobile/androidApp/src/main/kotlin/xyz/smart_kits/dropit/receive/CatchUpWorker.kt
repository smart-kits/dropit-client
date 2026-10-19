package xyz.smart_kits.dropit.receive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import xyz.smart_kits.dropit.app
import java.util.concurrent.TimeUnit

/** A pull that a push could not finish in time (no network, a slow answer): tried again when there is a network */
class CatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return if (applicationContext.app.receiving.catchUp("retry")) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CatchUpWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("catch-up", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
