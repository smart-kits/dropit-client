package xyz.smart_kits.dropit.receive

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import xyz.smart_kits.dropit.app

/**
 * The system's push arrives here. It carries no content, only a type: `new` (something arrived: fetch it) or
 * `test` (the check made when background receiving is switched on). The system gives this about twenty
 * seconds; what does not finish in time is left to a retry.
 */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val receiving = app.receiving
        when (message.data["type"]) {
            "test" -> receiving.testArrived()
            "new" -> {
                val done = runBlocking { withTimeoutOrNull(15_000) { receiving.catchUp("push") } }
                if (done != true) CatchUpWorker.enqueue(this)
            }
        }
    }

    override fun onNewToken(token: String) {
        runBlocking { withTimeoutOrNull(15_000) { app.receiving.addressChanged(token) } }
    }
}
