package dev.memscope.device

/** Every device-side failure surfaces as this type; [hint] tells the user what to do next. */
class DeviceException(
    val kind: Kind,
    message: String,
    val hint: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        ADB_NOT_FOUND, NO_DEVICE, DEVICE_OFFLINE, DISCONNECTED, TIMEOUT,
        NOT_DEBUGGABLE, NOT_INSTALLED, COMMAND_FAILED, CANCELLED,
        /** The MemScope JVM itself ran out of heap (analysis of a large dump); not a device failure. */
        OUT_OF_MEMORY,
    }
}
