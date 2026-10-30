package io.github.staindk.nacashu

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Temporary debug timings for issue 35: what happened to each photo of the last run, on the watch
 * and on the phone, so slow first photos can be traced. The watch sends its side with each photo;
 * this adds the phone's side and writes a table that "Debug: share last run timings" shares.
 */
object RunLog {
    private class Photo(
        val seq: Int,
        val watchSentMs: Long,
        val retries: Int,
        val fastBluetooth: Boolean,
        val receivedAt: Long,
    ) {
        var rootMs = -1L
        var findMs = -1L
        var clickMs = -1L
        var answeredMs = -1L
        var answerSentMs = -1L
        var status = -1
        var watchRttMs = -1L
        var watchAckMs = -1L
    }

    private val photos = mutableListOf<Photo>()
    private var started = ""
    private var current: Photo? = null

    fun file(context: Context) = File(context.filesDir, "run-timings.txt")

    private fun now() = SystemClock.elapsedRealtime()

    /** A photo arrived from the watch. Photo 1 starts a new run. */
    fun received(seq: Int, watchSentMs: Long, retries: Int, prevRttMs: Long, prevAckMs: Long, fastBluetooth: Boolean) {
        if (seq == 1) {
            photos.clear()
            started = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        }
        photos.lastOrNull()?.let {
            it.watchRttMs = prevRttMs
            it.watchAckMs = prevAckMs
        }
        current = Photo(seq, watchSentMs, retries, fastBluetooth, now()).also { photos.add(it) }
    }

    /** Steps inside the shutter press, timed from the photo's arrival. */
    fun foundRoot() { current?.let { it.rootMs = now() - it.receivedAt } }
    fun foundShutter() { current?.let { it.findMs = now() - it.receivedAt } }
    fun clicked() { current?.let { it.clickMs = now() - it.receivedAt } }

    fun answered(seq: Int, status: Int) {
        photos.lastOrNull { it.seq == seq }?.let {
            it.answeredMs = now() - it.receivedAt
            it.status = status
        }
    }

    /** Also saves the table, so a run that stops early still has one. */
    fun answerSent(context: Context, seq: Int) {
        photos.lastOrNull { it.seq == seq }?.let { it.answerSentMs = now() - it.receivedAt }
        write(context)
    }

    /** The watch's round trip for the last photo, sent after the run. */
    fun ended(context: Context, lastRttMs: Long, lastAckMs: Long) {
        photos.lastOrNull()?.let {
            it.watchRttMs = lastRttMs
            it.watchAckMs = lastAckMs
        }
        write(context)
    }

    fun write(context: Context) {
        try {
            file(context).writeText(table())
        } catch (e: IOException) {
            // Debug only; nothing to tell the user.
        }
    }

    private fun Long.cell() = if (this < 0) "-" else toString()

    private fun table(): String {
        val first = photos.firstOrNull() ?: return "No run yet\n"
        val sb = StringBuilder()
        sb.append("Nacashu run timings, $started, ${photos.size} photo(s)\n")
        sb.append("All times in ms. Watch columns use the watch's clock, phone columns the phone's.\n\n")
        sb.append("#  fastBT sent retry | arrive drift | root find click answer handed | rtt ack bt status\n")
        for (p in photos) {
            val arrive = p.receivedAt - first.receivedAt
            // How much later this photo arrived than photo 1 would suggest. Negative means photo 1's
            // trip to the phone was slower than this one's.
            val drift = arrive - (p.watchSentMs - first.watchSentMs)
            // Round trip minus the phone's own work: Bluetooth plus the Pebble app, both ways.
            val bt = if (p.watchRttMs < 0 || p.answeredMs < 0) -1 else p.watchRttMs - p.answeredMs
            sb.append(
                listOf(
                    p.seq, if (p.fastBluetooth) "on" else "off", p.watchSentMs, p.retries, "|",
                    arrive, drift, "|",
                    p.rootMs.cell(), p.findMs.cell(), p.clickMs.cell(), p.answeredMs.cell(), p.answerSentMs.cell(), "|",
                    p.watchRttMs.cell(), p.watchAckMs.cell(), bt.cell(), ShutterProtocol.statusName(p.status),
                ).joinToString(" "),
            )
            sb.append('\n')
        }
        sb.append(
            "\nsent: from SELECT to the watch sending it. arrive: when it reached Nacashu, from photo 1.\n" +
                "drift: arrive minus the watch's own gap since photo 1; big negative values mean photo 1 was slow to arrive.\n" +
                "root/find/click/answer: Nacashu finding the camera, then the shutter, clicking it, and having a status.\n" +
                "handed: the status handed to the Pebble app. rtt: watch send to status back. ack: watch send to\n" +
                "the Pebble app acking it. bt: rtt minus answer, the time spent in Bluetooth and the Pebble app.\n",
        )
        return sb.toString()
    }
}
