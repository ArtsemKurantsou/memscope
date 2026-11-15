package dev.memscope.server

/** One-line-per-event logging to stderr, so a user can paste the terminal output into a bug report. */
object ServerLog {
    @Volatile var sink: (String) -> Unit = { System.err.println(it) }

    fun error(message: String, t: Throwable? = null) {
        sink(if (t == null) message else message + "\n" + t.stackTraceToString().trimEnd())
    }
}
