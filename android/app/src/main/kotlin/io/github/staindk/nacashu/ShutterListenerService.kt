package io.github.staindk.nacashu

import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The Pebble app binds this service (PebbleKit 2) while the watchapp is open and forwards its
 * AppMessages here. Callbacks run on the main thread.
 */
class ShutterListenerService : BasePebbleListenerService() {
    private val sender by lazy { DefaultPebbleSender(applicationContext) }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != ShutterProtocol.WATCHAPP_UUID) return ReceiveResult.Nack
        // Numbers arrive as Int32 or UInt32, whatever size the watch sent.
        val cmd = when (val item = data[ShutterProtocol.KEY_CMD]) {
            is PebbleDictionaryItem.Int32 -> item.value
            is PebbleDictionaryItem.UInt32 -> item.value.toInt()
            else -> return ReceiveResult.Nack
        }
        Log.d(TAG, "cmd=$cmd from $watch")
        ShutterProtocol.run(cmd) { status ->
            Log.d(TAG, "cmd=$cmd -> ${ShutterProtocol.statusName(status)}")
            sendStatus(status, watch)
        }
        // Ack straight away so the watch's outbox frees up; the outcome follows as STATUS.
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != ShutterProtocol.WATCHAPP_UUID) return
        // Settings go when the watchapp opens; it can't receive messages while it's closed.
        send(ShutterProtocol.settingsMessage(this), watch)
        val service = ShutterAccessibilityService.instance
        if (service != null && Settings.autoOpenCamera(this)) {
            service.openCamera()
        }
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != ShutterProtocol.WATCHAPP_UUID) return
        if (Settings.accessibilityAutoOff(this)) {
            // Banking apps can refuse to run while any accessibility service is on. The user turns
            // it back on with Android's accessibility shortcut (hold both volume keys).
            ShutterAccessibilityService.instance?.disableSelf()
        }
    }

    override fun onDestroy() {
        sender.close()
        super.onDestroy()
    }

    private fun sendStatus(status: Int, watch: WatchIdentifier) =
        send(mapOf(ShutterProtocol.KEY_STATUS to PebbleDictionaryItem.UInt8(status.toUByte())), watch)

    private fun send(data: PebbleDictionary, watch: WatchIdentifier) {
        coroutineScope.launch {
            sender.sendDataToPebble(ShutterProtocol.WATCHAPP_UUID, data, listOf(watch))
        }
    }

    private companion object {
        const val TAG = "ShutterListener"
    }
}
