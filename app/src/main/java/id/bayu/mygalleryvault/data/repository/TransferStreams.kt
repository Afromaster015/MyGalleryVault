package id.bayu.mygalleryvault.data.repository

import id.bayu.mygalleryvault.domain.model.CancelledSignal

/** Throttles high-frequency byte callbacks to a UI-friendly rate. */
internal class ByteProgressPublisher(private val sink: (done: Long, total: Long) -> Unit) {

    private var lastMs = 0L
    private var lastFrac = -1f

    operator fun invoke(done: Long, total: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (total > 0 && done >= total) {
            lastMs = now
            lastFrac = 1f
            sink(done, total)
            return
        }
        if (total > 0) {
            val frac = done.toFloat() / total
            if (now - lastMs < THROTTLE_MS &&
                (lastFrac < 0f || frac - lastFrac < MIN_STEP)
            ) {
                return
            }
            lastFrac = frac
        } else {
            // Unknown size: emit on a slower fixed cadence so the bar still moves.
            if (now - lastMs < UNKNOWN_THROTTLE_MS) return
        }
        lastMs = now
        sink(done, total)
    }

    companion object {
        private const val THROTTLE_MS = 120L
        private const val MIN_STEP = 0.004f
        private const val UNKNOWN_THROTTLE_MS = 400L
    }
}

/**
 * Input side progress + cooperative cancellation for imports. Every read both
 * updates the counter and checks the cancel flag, so even the middle of a huge
 * copy aborts immediately instead of waiting for the file to finish.
 */
internal class ProgressInputStream(
    private val source: java.io.InputStream,
    private val totalBytes: Long,
    private val emit: (done: Long, total: Long) -> Unit,
    private val cancelled: (() -> Boolean)?,
) : java.io.InputStream() {

    private var count = 0L

    override fun read(): Int {
        checkCancel()
        val v = source.read()
        if (v >= 0) bump(1)
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkCancel()
        val n = source.read(b, off, len)
        if (n > 0) bump(n)
        return n
    }

    override fun available(): Int = source.available()

    override fun close() = source.close()

    private fun bump(n: Int) {
        count += n
        emit(count, totalBytes)
    }

    private fun checkCancel() {
        if (cancelled?.invoke() == true) throw CancelledSignal()
    }
}

/** Output-side progress + cancellation for exports (decrypt path writes through here). */
internal class ProgressOutputStream(
    private val sink: java.io.OutputStream,
    private val totalBytes: Long,
    private val publisher: ByteProgressPublisher,
    private val cancelled: (() -> Boolean)?,
) : java.io.OutputStream() {

    private var count = 0L

    override fun write(b: Int) {
        cancelled?.let { if (it()) throw CancelledSignal() }
        sink.write(b)
        bump(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        cancelled?.let { if (it()) throw CancelledSignal() }
        sink.write(b, off, len)
        bump(len)
    }

    override fun flush() = sink.flush()

    override fun close() = sink.close()

    private fun bump(n: Int) {
        count += n
        publisher(count, totalBytes)
    }
}

/** Cancellation-only passthrough used when no percent tracking is requested. */
internal class CancelCheckingOutputStream(
    private val sink: java.io.OutputStream,
    private val cancelled: () -> Boolean,
) : java.io.OutputStream() {

    override fun write(b: Int) {
        check()
        sink.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check()
        sink.write(b, off, len)
    }

    override fun flush() = sink.flush()

    override fun close() = sink.close()

    private fun check() {
        if (cancelled()) throw CancelledSignal()
    }
}
