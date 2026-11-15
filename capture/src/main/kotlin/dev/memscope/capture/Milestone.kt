package dev.memscope.capture

import kotlinx.serialization.Serializable

/**
 * Where a milestone stops the app: the entry of [className].[methodName] (all overloads declared in that class, or
 * only the one with JNI [signature]). With [afterReturn] the stop is where the first call returns to its caller,
 * implemented as an entry breakpoint that arms a thread-filtered breakpoint at the caller's return location
 * (never STEP_OUT, which makes the stepped code ~280x slower).
 */
@Serializable
data class Hook(
    val className: String,
    val methodName: String,
    val signature: String? = null,
    val afterReturn: Boolean = false,
)

/** One heap dump point of a startup session. Fires once, on the first hit. */
@Serializable
data class Milestone(
    val id: String,
    val label: String,
    val hook: Hook,
    val enabled: Boolean = true,
    val custom: Boolean = false,
)

object Milestones {
    /** Handled by id: the return of `ActivityThread.waitForDebugger` into the main thread's `handleBindApplication`. */
    const val PROCESS_START = "process_start"

    /** Hooks verified on API 37 (spike/FINDINGS.md A.2). */
    val defaults: List<Milestone> = listOf(
        Milestone(PROCESS_START, "Process start", Hook("android.app.ActivityThread", "waitForDebugger", afterReturn = true)),
        Milestone(
            "before_app_oncreate", "Before Application.onCreate",
            Hook("android.app.Instrumentation", "callApplicationOnCreate", "(Landroid/app/Application;)V"),
        ),
        Milestone(
            "after_app_oncreate", "After Application.onCreate",
            Hook("android.app.Instrumentation", "callApplicationOnCreate", "(Landroid/app/Application;)V", afterReturn = true),
        ),
        Milestone("first_activity_created", "First Activity created", Hook("android.app.Activity", "performCreate", afterReturn = true)),
        Milestone("first_activity_resumed", "First Activity resumed", Hook("android.app.Activity", "performResume", afterReturn = true)),
    )

    private const val IDENT = "[A-Za-z_$][A-Za-z0-9_$]*"
    private val CLASS_NAME = Regex("$IDENT(\\.$IDENT)+")
    private val METHOD_NAME = Regex(IDENT)
    private val SIGNATURE = Regex("\\([A-Za-z0-9_$/;\\[]*\\)[A-Za-z0-9_$/;\\[]+")
    private val SPEC = Regex("($IDENT(?:\\.$IDENT)+)#($IDENT)")

    /** Custom entry milestone from `fully.qualified.Class#method` (nested classes as `Outer$Inner`). */
    fun parseCustom(spec: String): Milestone {
        val text = spec.trim()
        val m = SPEC.matchEntire(text) ?: throw IllegalArgumentException(
            "'$spec' is not a milestone: use fully.qualified.ClassName#methodName (for example com.example.MainActivity#onCreate)",
        )
        val (cls, method) = m.destructured
        return Milestone("custom:$cls#$method", "${cls.substringAfterLast('.')}#$method", Hook(cls, method), custom = true)
    }

    /** Throws [IllegalArgumentException] naming the first problem: unknown hook shape, duplicate ids or labels. */
    fun validate(milestones: List<Milestone>) {
        require(milestones.any { it.enabled }) { "Select at least one milestone." }
        val ids = HashSet<String>()
        val labels = HashSet<String>()
        for (m in milestones) {
            require(m.id.isNotBlank() && m.label.isNotBlank()) { "Milestone id and label must not be empty." }
            require(ids.add(m.id)) { "Milestone '${m.id}' is listed twice." }
            require(labels.add(m.label)) { "Two milestones are labelled '${m.label}'." }
            require(CLASS_NAME.matches(m.hook.className)) { "'${m.hook.className}' is not a fully qualified class name." }
            require(METHOD_NAME.matches(m.hook.methodName)) { "'${m.hook.methodName}' is not a method name." }
            m.hook.signature?.let { require(SIGNATURE.matches(it)) { "'$it' is not a JNI method signature." } }
            if (m.custom) require(!m.hook.afterReturn) { "Custom milestones stop at method entry only." }
            if (m.id == PROCESS_START) require(!m.custom) { "'$PROCESS_START' is a built-in milestone id." }
        }
    }
}
