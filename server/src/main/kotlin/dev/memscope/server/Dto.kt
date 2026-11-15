package dev.memscope.server

import dev.memscope.capture.SessionMeta
import dev.memscope.capture.StartupConfig
import dev.memscope.device.Device
import dev.memscope.device.ProcessInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON configuration of every request and response body. */
val apiJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

// `device` types are not @Serializable, so the wire shapes live here.
@Serializable
data class DeviceDto(val serial: String, val state: String, val model: String?, val apiLevel: Int?)

@Serializable
data class ProcessDto(val pid: Int, val name: String)

@Serializable
data class AppDto(val packageName: String, val running: Boolean, val processes: List<ProcessDto>)

fun Device.toDto() = DeviceDto(serial, state, model, apiLevel)
fun ProcessInfo.toDto() = ProcessDto(pid, name)

/** Body of every 4xx/5xx response. [kind] is a DeviceException kind or one of BAD_REQUEST, NOT_FOUND, ... */
@Serializable
data class ErrorBody(val error: String, val hint: String, val kind: String)

@Serializable
data class JobRef(val jobId: String)

@Serializable
data class IndexBody(val index: Int)

/** [startup] is used in STARTUP mode only (default milestones, launcher activity, package process when absent). */
@Serializable
data class CreateSessionRequest(
    val serial: String,
    val packageName: String,
    val mode: SessionMeta.Mode,
    val appPackagePrefixes: List<String>? = null,
    val startup: StartupConfig? = null,
)

/** The launcher activity (`pkg/.Activity`) of a package, or null if it has none. */
@Serializable
data class LauncherDto(val component: String?)

/** Answer to a STARTUP session request: the new session and the job recording it. */
@Serializable
data class StartupStarted(val session: SessionMeta, val jobId: String)

@Serializable
data class DumpRequest(val processName: String? = null, val label: String? = null)
