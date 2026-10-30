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
        val cmd = number(data, ShutterProtocol.KEY_CMD)?.toInt() ?: return ReceiveResult.Nack
        if (cmd == ShutterProtocol.CMD_PING) return ReceiveResult.Ack
        Log.d(TAG, "cmd=$cmd from $watch")
        if (cmd == ShutterProtocol.CMD_RUN_END) {
            RunLog.ended(
                this,
                number(data, ShutterProtocol.KEY_PREV_RTT) ?: -1,
                number(data, ShutterProtocol.KEY_PREV_ACK) ?: -1,
            )
            return ReceiveResult.Ack
        }
        val seq = number(data, ShutterProtocol.KEY_SEQ)?.toInt()
        if (cmd == ShutterProtocol.CMD_SHUTTER && seq != null) {
            RunLog.received(
                seq,
                number(data, ShutterProtocol.KEY_T_SENT) ?: -1,
                number(data, ShutterProtocol.KEY_RETRIES)?.toInt() ?: -1,
                number(data, ShutterProtocol.KEY_PREV_RTT) ?: -1,
                number(data, ShutterProtocol.KEY_PREV_ACK) ?: -1,
                number(data, ShutterProtocol.KEY_FAST_BT) == 1L,
            )
        }
        if (cmd == ShutterProtocol.CMD_QUICK_LAUNCH && !Settings.snapOnQuickLaunch(this)) {
            return ReceiveResult.Ack
        }
        ShutterProtocol.run(cmd) { status ->
            Log.d(TAG, "cmd=$cmd -> ${ShutterProtocol.statusName(status)}")
            if (seq != null) RunLog.answered(seq, status)
            sendStatus(status, watch, seq)
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

    private fun sendStatus(status: Int, watch: WatchIdentifier, seq: Int? = null) {
        val data = mutableMapOf<UInt, PebbleDictionaryItem>(
            ShutterProtocol.KEY_STATUS to PebbleDictionaryItem.UInt8(status.toUByte()),
        )
        if (seq != null) data[ShutterProtocol.KEY_SEQ] = PebbleDictionaryItem.UInt16(seq.toUShort())
        send(data, watch) { if (seq != null) RunLog.answerSent(this, seq) }
    }

    private fun send(data: PebbleDictionary, watch: WatchIdentifier, onSent: () -> Unit = {}) {
        coroutineScope.launch {
            sender.sendDataToPebble(ShutterProtocol.WATCHAPP_UUID, data, listOf(watch))
            onSent()
        }
    }

    /** A number of any size the watch sent, or null if the key is missing. */
    private fun number(data: PebbleDictionary, key: UInt): Long? = when (val item = data[key]) {
        is PebbleDictionaryItem.Int8 -> item.value.toLong()
        is PebbleDictionaryItem.Int16 -> item.value.toLong()
        is PebbleDictionaryItem.Int32 -> item.value.toLong()
        is PebbleDictionaryItem.UInt8 -> item.value.toLong()
        is PebbleDictionaryItem.UInt16 -> item.value.toLong()
        is PebbleDictionaryItem.UInt32 -> item.value.toLong()
        else -> null
    }

    private companion object {
        const val TAG = "ShutterListener"
    }
}
