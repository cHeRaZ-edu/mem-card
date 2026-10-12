package com.cheraz.memcard.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MemCardCoreTest {
    @Test
    fun protocolVersionStartsAtOne() {
        assertEquals(1, MemCardCore.PROTOCOL_VERSION)
    }
}
