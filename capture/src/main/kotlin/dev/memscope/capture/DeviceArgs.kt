package dev.memscope.capture

/**
 * Values that end up inside `adb shell` command lines. `adb shell` joins its arguments with spaces and the device shell
 * parses the result again, so anything coming from a request (or a file) must match a strict pattern first.
 * Each check throws [IllegalArgumentException] (HTTP 400) naming the value.
 */
object DeviceArgs {
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private const val SERIAL_MAX = 200
    private val PROCESS = Regex("[A-Za-z][A-Za-z0-9_.]*(:[A-Za-z0-9_.]+)?")

    fun requirePackage(name: String) =
        require(PACKAGE.matches(name)) { "'$name' is not a valid package name (letters, digits, '_' and dots, e.g. com.example.app)." }

    /**
     * A serial only ever reaches adb as one argv entry (`adb -s <serial>`), never a device shell command line, and
     * wireless-debugging serials contain spaces and parentheses (`adb-XXXX (2)._adb-tls-connect._tcp`): only empty,
     * over-long and control-character values are refused.
     */
    fun requireSerial(serial: String) =
        require(serial.isNotEmpty() && serial.length <= SERIAL_MAX && serial.none { it.isISOControl() }) {
            "'${serial.take(SERIAL_MAX)}' is not a valid device serial."
        }

    fun requireProcessName(name: String) =
        require(PROCESS.matches(name)) { "'$name' is not a valid process name (e.g. com.example.app or com.example.app:remote)." }
}
