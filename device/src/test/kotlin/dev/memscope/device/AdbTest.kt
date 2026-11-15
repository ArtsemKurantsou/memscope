package dev.memscope.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class AdbTest {
    @TempDir lateinit var tmp: Path

    private val adbPath: Path = Path.of("/sdk with space/platform-tools/adb")
    private fun adb(handler: (List<String>) -> ProcessResult): Pair<Adb, FakeProcessRunner> {
        val fake = FakeProcessRunner(handler)
        return Adb(adbPath, fake) to fake
    }

    private fun ok(out: String = "") = ProcessResult(0, out, "")
    private fun err(stderr: String, code: Int = 1, out: String = "") = ProcessResult(code, out, stderr)

    @Test
    fun `devices parses`() {
        val listing = "List of devices attached\nemulator-5554\tdevice product:x model:Pixel_9 device:y\nABC\toffline\n"
        val (adb, fake) = adb { args ->
            if ("getprop" in args) ok("37\n") else ok(listing)
        }
        val devices = adb.devices()
        assertEquals(2, devices.size)
        assertEquals(Device("emulator-5554", "device", "Pixel_9", 37), devices[0])
        assertEquals(Device("ABC", "offline", null, null), devices[1])
        // the offline device is never queried
        assertTrue(fake.calls.none { "ABC" in it })
    }

    @Test
    fun `devices ignores daemon banner lines and an empty list`() {
        val (adb, _) = adb { ok("* daemon not running; starting now at tcp:5037\n* daemon started successfully\nList of devices attached\n\n") }
        assertEquals(emptyList<Device>(), adb.devices())
    }

    @Test
    fun `shell maps failures`() {
        val (a1, _) = adb { err("error: device 'x' not found") }
        assertEquals(DeviceException.Kind.DISCONNECTED, assertThrows<DeviceException> { a1.shell("x", listOf("ls")) }.kind)

        val (a2, _) = adb { throw DeviceException(DeviceException.Kind.TIMEOUT, "slow", "retry") }
        assertEquals(DeviceException.Kind.TIMEOUT, assertThrows<DeviceException> { a2.shell("x", listOf("ls")) }.kind)

        val (a3, _) = adb { err("error: device offline") }
        assertEquals(DeviceException.Kind.DEVICE_OFFLINE, assertThrows<DeviceException> { a3.shell("x", listOf("ls")) }.kind)

        val (a4, _) = adb { err("adb: no devices/emulators found") }
        assertEquals(DeviceException.Kind.NO_DEVICE, assertThrows<DeviceException> { a4.shell("x", listOf("ls")) }.kind)
    }

    @Test
    fun `shell returns remote failures without throwing`() {
        val (adb, _) = adb { err("ls: /nope: No such file or directory", code = 1) }
        val r = adb.shell("x", listOf("ls", "/nope"))
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `every exception carries a hint`() {
        val (adb, _) = adb { err("error: device offline") }
        val e = assertThrows<DeviceException> { adb.shell("x", listOf("ls")) }
        assertFalse(e.hint.isBlank())
    }

    @Test
    fun `requireDebuggable`() {
        val (a1, _) = adb { err("run-as: package not debuggable: p") }
        assertEquals(DeviceException.Kind.NOT_DEBUGGABLE, assertThrows<DeviceException> { a1.requireDebuggable("s", "p") }.kind)

        val (a2, _) = adb { err("run-as: unknown package: p") }
        assertEquals(DeviceException.Kind.NOT_INSTALLED, assertThrows<DeviceException> { a2.requireDebuggable("s", "p") }.kind)

        // some Android versions print run-as errors on stdout
        val (a3, _) = adb { err("", out = "run-as: package not debuggable: p\n") }
        assertEquals(DeviceException.Kind.NOT_DEBUGGABLE, assertThrows<DeviceException> { a3.requireDebuggable("s", "p") }.kind)

        val (a4, _) = adb { err("run-as: something else entirely") }
        assertEquals(DeviceException.Kind.COMMAND_FAILED, assertThrows<DeviceException> { a4.requireDebuggable("s", "p") }.kind)

        val (a5, fake) = adb { ok("uid=10200(u0_a200)") }
        a5.requireDebuggable("s", "p")
        assertEquals(listOf(adbPath.toString(), "-s", "s", "shell", "run-as", "p", "id"), fake.calls.single())
    }

    @Test
    fun `args are never joined`() {
        val (adb, fake) = adb { ok() }
        adb.shell("s", listOf("ls", "/sdcard/My Files/a b.txt"))
        val args = fake.calls.single()
        assertEquals(adbPath.toString(), args[0])
        assertEquals(listOf("ls", "/sdcard/My Files/a b.txt"), args.takeLast(2))
        assertEquals("/sdcard/My Files/a b.txt", args.last())

        adb.pull("s", "/sdcard/x y.hprof", tmp.resolve("dir with space/out.hprof"), 5.seconds)
        assertTrue("/sdcard/x y.hprof" in fake.calls.last())
        assertTrue(tmp.resolve("dir with space/out.hprof").toString() in fake.calls.last())
    }

    @Test
    fun `execOutToFile deletes the partial file on every kind of failure`() {
        val dest = tmp.resolve("d.hprof")
        fun partial(h: (List<String>) -> ProcessResult): Adb {
            return adb { args -> Files.writeString(dest, "partial"); h(args) }.first
        }
        val timeouts = partial { throw DeviceException(DeviceException.Kind.TIMEOUT, "t", "h") }
        assertThrows<DeviceException> { timeouts.execOutToFile("s", listOf("cat", "x"), dest, 5.seconds) }
        assertFalse(Files.exists(dest))

        val nonZero = partial { err("cat: x: No such file", code = 1) }
        assertEquals(DeviceException.Kind.COMMAND_FAILED, assertThrows<DeviceException> { nonZero.execOutToFile("s", listOf("cat", "x"), dest, 5.seconds) }.kind)
        assertFalse(Files.exists(dest))

        val gone = partial { err("error: device 'x' not found") }
        assertEquals(DeviceException.Kind.DISCONNECTED, assertThrows<DeviceException> { gone.execOutToFile("s", listOf("cat", "x"), dest, 5.seconds) }.kind)
        assertFalse(Files.exists(dest))

        val boom = partial { throw IllegalStateException("boom") }
        assertThrows<IllegalStateException> { boom.execOutToFile("s", listOf("cat", "x"), dest, 5.seconds) }
        assertFalse(Files.exists(dest))
    }

    @Test
    fun `execOutToFile returns the byte count and uses exec-out`() {
        val dest = tmp.resolve("ok.bin")
        val (adb, fake) = adb { Files.write(dest, ByteArray(1234)); ok() }
        assertEquals(1234L, adb.execOutToFile("s", listOf("cat", "x"), dest, 5.seconds))
        assertEquals(listOf(adbPath.toString(), "-s", "s", "exec-out", "cat", "x"), fake.calls.single())
    }

    @Test
    fun `pull deletes the partial file on failure`() {
        val dest = tmp.resolve("p.hprof")
        val (adb, _) = adb { Files.writeString(dest, "partial"); err("adb: error: remote object '/x' does not exist") }
        assertEquals(DeviceException.Kind.COMMAND_FAILED, assertThrows<DeviceException> { adb.pull("s", "/x", dest, 5.seconds) }.kind)
        assertFalse(Files.exists(dest))
    }

    @Test
    fun `forward and jdwp`() {
        val (adb, fake) = adb { args ->
            when {
                "forward" in args && "--remove" !in args -> ok("54321\n")
                "jdwp" in args -> ok("123\n456\n\n")
                else -> ok()
            }
        }
        assertEquals(54321, adb.forwardJdwp("s", 123))
        assertEquals(listOf(adbPath.toString(), "-s", "s", "forward", "tcp:0", "jdwp:123"), fake.calls[0])
        adb.removeForward("s", 54321)
        assertEquals(listOf(adbPath.toString(), "-s", "s", "forward", "--remove", "tcp:54321"), fake.calls[1])
        assertEquals(setOf(123, 456), adb.jdwpPids("s"))
    }

    @Test
    fun `processes matches the package and its colon-named processes only`() {
        val ps = "PID NAME\n1 init\n100 com.example.app\n101 com.example.app:service\n102 com.example.application\n103 com.example\n"
        val (adb, _) = adb { ok(ps) }
        assertEquals(
            listOf(ProcessInfo(100, "com.example.app"), ProcessInfo(101, "com.example.app:service")),
            adb.processes("s", "com.example.app"),
        )
    }

    @Test
    fun `debuggablePackages keeps only packages where run-as succeeds`() {
        val (adb, _) = adb { args ->
            when {
                "pm" in args -> ok("package:a.b\npackage:c.d\n")
                "run-as" in args && "a.b" in args -> ok("uid=1")
                else -> err("run-as: package not debuggable: c.d")
            }
        }
        assertEquals(listOf("a.b"), adb.debuggablePackages("s"))
    }

    @Test
    fun `launcherComponent`() {
        val (adb, _) = adb { ok("priority=0 preferredOrder=0 match=0x0 specificIndex=-1 isDefault=false\ncom.p/.Main\n") }
        assertEquals("com.p/.Main", adb.launcherComponent("s", "com.p"))
        val (none, _) = adb { ok("No activity found\n") }
        assertNull(none.launcherComponent("s", "com.p"))
    }

    @Test
    fun `jdwpPids reads a never-ending adb jdwp stream and stops it`() {
        val script = tmp.resolve("fake adb")
        Files.writeString(script, "#!/bin/sh\necho 111\necho 222\nexec sleep 30\n")
        script.toFile().setExecutable(true)
        val start = System.nanoTime()
        assertEquals(setOf(111, 222), Adb(script).jdwpPids("s"))
        assertTrue((System.nanoTime() - start) < 6_000_000_000L)
    }

    @Test
    fun `trackJdwp streams into a file until cancelled and maps adb failures`() {
        val script = tmp.resolve("fake adb")
        Files.writeString(script, "#!/bin/sh\nprintf '0004111\\n'\nexec sleep 30\n")
        script.toFile().setExecutable(true)
        val dest = tmp.resolve("track.bin")
        val cancel = CancelToken()
        val t = Thread { Adb(script).trackJdwp("s", dest, 30.seconds, cancel) }.apply { start() }
        val deadline = System.nanoTime() + 5_000_000_000L
        while ((!Files.exists(dest) || Files.size(dest) < 8) && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals("0004111\n", Files.readString(dest))
        cancel.cancel()
        t.join(5_000)
        assertFalse(t.isAlive, "cancel is the normal way out and returns")

        val (gone, fake) = adb { err("adb: error: closed") }
        val e = assertThrows<DeviceException> { gone.trackJdwp("s", dest, 1.seconds, CancelToken()) }
        assertEquals(DeviceException.Kind.DISCONNECTED, e.kind)
        assertEquals(listOf(adbPath.toString(), "-s", "s", "track-jdwp"), fake.calls[0])
    }
}

class WmSizeTest {
    @Test
    fun `physical size`() = assertEquals(ScreenPx(1080, 2400), parseWmSize("Physical size: 1080x2400\n"))

    @Test
    fun `override size wins`() =
        assertEquals(ScreenPx(720, 1600), parseWmSize("Physical size: 1080x2400\nOverride size: 720x1600\r\n"))

    @Test
    fun `garbage gives null`() {
        assertNull(parseWmSize(""))
        assertNull(parseWmSize("Physical size: unknown\n"))
        assertNull(parseWmSize("Physical size: 0x0\n"))
    }
}
