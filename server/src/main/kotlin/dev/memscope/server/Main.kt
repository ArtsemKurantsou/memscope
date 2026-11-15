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

fun main(args: Array<String>) {
    var port: Int? = null
    var open = true
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: error("--port needs a number")
            "--no-open" -> open = false
            else -> error("Unknown argument '${args[i]}'. Usage: [--port <n>] [--no-open]")
        }
        i++
    }
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
