package dev.memscope.capture

/**
 * Values that end up inside `adb shell` command lines. `adb shell` joins its arguments with spaces and the device shell
 * parses the result again, so anything coming from a request (or a file) must match a strict pattern first.
 * Each check throws [IllegalArgumentException] (HTTP 400) naming the value.
 */
object DeviceArgs {
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val SERIAL = Regex("[A-Za-z0-9._:-]{1,128}")
    private val PROCESS = Regex("[A-Za-z][A-Za-z0-9_.]*(:[A-Za-z0-9_.]+)?")

    fun requirePackage(name: String) =
        require(PACKAGE.matches(name)) { "'$name' is not a valid package name (letters, digits, '_' and dots, e.g. com.example.app)." }

    fun requireSerial(serial: String) =
        require(SERIAL.matches(serial)) { "'$serial' is not a valid device serial." }

    fun requireProcessName(name: String) =
        require(PROCESS.matches(name)) { "'$name' is not a valid process name (e.g. com.example.app or com.example.app:remote)." }
}
