package dev.memscope.capture

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class MilestonesTest {
    @Test
    fun `defaults match the verified hook table`() {
        val d = Milestones.defaults.associateBy { it.id }
        assertEquals(listOf("process_start", "before_app_oncreate", "after_app_oncreate", "first_activity_created", "first_activity_resumed"), Milestones.defaults.map { it.id })
        assertEquals(Hook("android.app.Instrumentation", "callApplicationOnCreate", "(Landroid/app/Application;)V"), d.getValue("before_app_oncreate").hook)
        assertEquals(Hook("android.app.Instrumentation", "callApplicationOnCreate", "(Landroid/app/Application;)V", afterReturn = true), d.getValue("after_app_oncreate").hook)
        assertEquals(Hook("android.app.Activity", "performCreate", null, afterReturn = true), d.getValue("first_activity_created").hook)
        assertEquals(Hook("android.app.Activity", "performResume", null, afterReturn = true), d.getValue("first_activity_resumed").hook)
        assertTrue(Milestones.defaults.all { it.enabled && !it.custom })
        assertDoesNotThrow { Milestones.validate(Milestones.defaults) }
    }

    @Test
    fun `parseCustom accepts a fully qualified Class#method`() {
        val m = Milestones.parseCustom("a.b.C#m")
        assertEquals(Hook("a.b.C", "m"), m.hook)
        assertTrue(m.custom)
        assertTrue(m.enabled)
        assertFalse(m.hook.afterReturn)
        assertEquals("C#m", m.label)
        assertEquals("custom:a.b.C#m", m.id)
        assertEquals(Hook("dev.memscope.fixture.Outer\$Inner", "on_Create2"), Milestones.parseCustom("  dev.memscope.fixture.Outer\$Inner#on_Create2 ").hook)
    }

    @Test
    fun `parseCustom rejects malformed specs`() {
        for (bad in listOf("", "a.b.C", "C#m", "a.b.C#", "#m", "a.b.C#m#n", "a b.C#m", "a.b.C#m;rm -rf", "a..b.C#m", "a.b.C#m()", "1a.b.C#m", "a.b.C#<init>", "a.b.C.#m")) {
            val e = assertThrows<IllegalArgumentException>("'$bad'") { Milestones.parseCustom(bad) }
            assertTrue("ClassName#methodName" in e.message!!, e.message)
        }
    }

    @Test
    fun `validate rejects duplicates, empty selection and bad hooks`() {
        val c = Milestones.parseCustom("a.b.C#m")
        assertThrows<IllegalArgumentException> { Milestones.validate(Milestones.defaults + c + c) }
        assertThrows<IllegalArgumentException> { Milestones.validate(Milestones.defaults + c + c.copy(id = "custom:other")) } // same label
        assertThrows<IllegalArgumentException> { Milestones.validate(Milestones.defaults.map { it.copy(enabled = false) }) }
        assertThrows<IllegalArgumentException> { Milestones.validate(listOf(c.copy(hook = Hook("a.b.C;x", "m")))) }
        assertThrows<IllegalArgumentException> { Milestones.validate(listOf(c.copy(hook = Hook("a.b.C", "m", "nope")))) }
        assertThrows<IllegalArgumentException> { Milestones.validate(listOf(c.copy(hook = Hook("a.b.C", "m", afterReturn = true)))) }
    }

    @Test
    fun `startup config round-trips through JSON with defaults`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val parsed = json.decodeFromString(StartupConfig.serializer(), "{}")
        assertEquals(StartupConfig(), parsed)
        assertEquals(Milestones.defaults, parsed.milestones)
        assertEquals(120, parsed.milestoneTimeoutSec)
        val c = StartupConfig(Milestones.defaults + Milestones.parseCustom("a.b.C#m"), "dev.app:remote", "dev.app/.Main", 30)
        assertEquals(c, json.decodeFromString(StartupConfig.serializer(), json.encodeToString(StartupConfig.serializer(), c)))
    }
}
