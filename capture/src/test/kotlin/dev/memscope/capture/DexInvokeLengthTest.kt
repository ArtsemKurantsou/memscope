package dev.memscope.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DexInvokeLengthTest {
    /** Dex code units are little-endian 16-bit words; the opcode is the low byte of the first unit. */
    private fun code(vararg opcodes: Int): ByteArray = ByteArray(opcodes.size * 2).also { b ->
        opcodes.forEachIndexed { i, op -> b[i * 2] = op.toByte(); b[i * 2 + 1] = 0x10 }
    }

    @Test
    fun `invoke kinds and their lengths in code units`() {
        val bytes = code(0x0e, 0x6e, 0x72, 0x74, 0x78, 0xfa, 0xfb, 0xfc, 0xfd, 0xe9)
        assertNull(dexInvokeUnits(bytes, 0), "return-void is not an invoke")
        assertEquals(3, dexInvokeUnits(bytes, 1)) // invoke-virtual
        assertEquals(3, dexInvokeUnits(bytes, 2)) // invoke-interface
        assertEquals(3, dexInvokeUnits(bytes, 3)) // invoke-virtual/range
        assertEquals(3, dexInvokeUnits(bytes, 4)) // invoke-interface/range
        assertEquals(4, dexInvokeUnits(bytes, 5)) // invoke-polymorphic
        assertEquals(4, dexInvokeUnits(bytes, 6)) // invoke-polymorphic/range
        assertEquals(3, dexInvokeUnits(bytes, 7)) // invoke-custom
        assertEquals(3, dexInvokeUnits(bytes, 8)) // invoke-custom/range
        assertNull(dexInvokeUnits(bytes, 9), "unknown (e.g. quickened) opcodes are not guessed")
        assertNull(dexInvokeUnits(bytes, 10))
        assertNull(dexInvokeUnits(bytes, -1))
    }
}
