package com.jokerhub.orzmc.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class MainDispatchTest {
    @Test
    fun `backup version exits zero`() {
        assertEquals(0, Main.dispatch(arrayOf("--version")))
    }

    @Test
    fun `merge version exits zero`() {
        assertEquals(0, Main.dispatch(arrayOf("merge", "--version")))
    }

    @Test
    fun `merge help exits zero`() {
        assertEquals(0, Main.dispatch(arrayOf("merge", "--help")))
    }

    @Test
    fun `no arguments exits non-zero`() {
        assertNotEquals(0, Main.dispatch(arrayOf()))
    }
}
