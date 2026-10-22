package xyz.smart_kits.dropit.receive

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import xyz.smart_kits.dropit.App
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.FileState
import xyz.smart_kits.dropit.shared.state

/**
 * The Download button on the notification of one received file: saves it without opening dropit. It is the same save as a tap in the
 * app ([Downloader.save]): one read of the item for a fresh address, then the file; each counts against the day's downloads exactly as
 * it does there. One file at a time. The notification the button was on is replaced by a line saying how it went; a tap on a
 * saved one opens the file.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val letterId get() = inputData.getString(LETTER) ?: ""

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(applicationContext.app.letters.all.value.firstOrNull { it.id == letterId }?.files?.firstOrNull()?.name ?: "")

    private fun foreground(name: String): ForegroundInfo {
        val note = NotificationCompat.Builder(applicationContext, App.CHANNEL_SEND)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(applicationContext.getString(R.string.dl_saving, name))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        val id = letterId.hashCode() + 1
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, note)
    }

    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val letter = app.letters.all.value.firstOrNull { it.id == letterId } ?: return Result.failure()
        val now = System.currentTimeMillis()
        // The first file still to be saved (a letter this notification is about has one)
        val file = letter.files.firstOrNull { it.saved == null } ?: letter.files.firstOrNull() ?: return Result.failure()
        val words = applicationContext
        if (file.saved != null) {
            app.arrivals.downloadResult(letter.id, words.getString(R.string.dl_saved, file.name), file.saved, file.mime)
            return Result.success()
        }
        if (file.state(now) == FileState.EXPIRED) {
            app.arrivals.downloadResult(letter.id, words.getString(R.string.dl_expired))
            return Result.success()
        }
        when (val result = app.downloader.save(letter, file)) {
            is Downloader.Result.Saved -> app.arrivals.downloadResult(letter.id, words.getString(R.string.dl_saved, file.name), result.uri, file.mime)
            Downloader.Result.Expired -> app.arrivals.downloadResult(letter.id, words.getString(R.string.dl_expired))
            is Downloader.Result.LimitToday -> app.arrivals.downloadResult(letter.id, words.getString(R.string.dl_limit))
            Downloader.Result.Offline -> app.arrivals.downloadResult(letter.id, words.getString(R.string.dl_offline))
            Downloader.Result.Unsupported -> app.arrivals.downloadResult(letter.id, words.getString(R.string.dl_unsupported))
            Downloader.Result.Failed -> app.arrivals.downloadResult(letter.id, words.getString(R.string.dl_failed))
        }
        return Result.success()
    }

    companion object {
        private const val LETTER = "letter"

        fun enqueue(context: Context, letterId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(LETTER to letterId))
                .build()
            // Pressed twice: still one download
            WorkManager.getInstance(context).enqueueUniqueWork("download-$letterId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
