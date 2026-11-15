package dev.memscope.server

import dev.memscope.capture.SessionStore
import dev.memscope.device.Adb
import dev.memscope.device.AdbLocator
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

const val LOOPBACK = "127.0.0.1"
private const val FIRST_PORT = 7070

/** Starts the server on [LOOPBACK] only; requests must carry `Host: localhost:<port>` or `127.0.0.1:<port>`. */
fun startServer(deps: ServerDeps, port: Int, wait: Boolean = true): NettyApplicationEngine =
    embeddedServer(Netty, port = port, host = LOOPBACK) {
        memscopeModule(deps, setOf("localhost:$port", "$LOOPBACK:$port"))
    }.start(wait)

private fun firstFreePort(): Int {
    for (port in FIRST_PORT until FIRST_PORT + 100) {
        try {
            ServerSocket(port, 1, InetAddress.getByName(LOOPBACK)).use { return port }
        } catch (_: java.io.IOException) { /* taken */ }
    }
    error("No free port in $FIRST_PORT..${FIRST_PORT + 99}; pass --port <n>.")
}

private fun openBrowser(url: String) {
    try {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(URI(url))
        } else if (System.getProperty("os.name").lowercase().contains("mac")) {
            ProcessBuilder("open", url).start()
        }
    } catch (_: Throwable) {
        System.err.println("Could not open a browser; open $url yourself.")
    }
}

private const val USAGE = "Usage: server [--port <n>] [--no-open]"

private fun usageError(message: String): Nothing {
    System.err.println("$message\n$USAGE")
    kotlin.system.exitProcess(2)
}

/** Browsers omit the default port from Host and Origin, so 80/443 would never match the "host:port" guard; also reject out-of-range ports. */
fun checkPort(port: Int): String? = when {
    port == 80 || port == 443 -> "Port $port is reserved for web servers; pick another with --port (e.g. 7070)."
    port !in 1..65535 -> "Port $port is out of range (1-65535)."
    else -> null
}

fun main(args: Array<String>) {
    var port: Int? = null
    var open = true
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: usageError("--port needs a number.")
            "--no-open" -> open = false
            else -> usageError("Unknown argument '${args[i]}'.")
        }
        i++
    }
    port?.let { p -> checkPort(p)?.let { usageError(it) } }
    val chosen = port ?: firstFreePort()

    val adb: Adb by lazy { Adb(AdbLocator.find()) } // not found is reported per request, with its hint
    val deps = ServerDeps(SessionStore(), { adb })
    val engine = startServer(deps, chosen, wait = false)
    Runtime.getRuntime().addShutdownHook(Thread {
        engine.stop(0, 2000)
        deps.close()
    })
    val url = "http://localhost:$chosen/"
    println("MemScope is running at $url")
    if (open) openBrowser(url)
    Thread.currentThread().join()
}
