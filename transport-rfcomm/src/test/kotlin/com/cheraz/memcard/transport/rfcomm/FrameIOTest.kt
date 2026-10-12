package com.cheraz.memcard.transport.rfcomm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameIOTest {

    private fun reader(bytes: ByteArray) = FrameIO(ByteArrayInputStream(bytes), ByteArrayOutputStream())

    @Test
    fun framesRoundTripInOrder() {
        val wire = ByteArrayOutputStream()
        val writer = FrameIO(ByteArrayInputStream(ByteArray(0)), wire)
        writer.write(FrameType.HELLO, "hola".toByteArray())
        writer.write(FrameType.DATA, ByteArray(70_000) { (it % 251).toByte() }, flush = false)
        writer.write(FrameType.BYE)
        writer.flush()

        val io = reader(wire.toByteArray())
        val hello = io.read()
        assertEquals(FrameType.HELLO, hello.type)
        assertArrayEquals("hola".toByteArray(), hello.payload)

        val data = io.read()
        assertEquals(FrameType.DATA, data.type)
        assertEquals(70_000, data.payload.size)
        assertEquals((69_999 % 251).toByte(), data.payload.last())

        val bye = io.read()
        assertEquals(FrameType.BYE, bye.type)
        assertEquals(0, bye.payload.size)
    }

    @Test
    fun rejectsOversizedLengthInTheHeader() {
        val wire = ByteArrayOutputStream()
        DataOutputStream(wire).apply {
            writeByte(FrameType.DATA)
            writeInt(FrameIO.MAX_PAYLOAD + 1)
            flush()
        }
        assertThrows(IOException::class.java) { reader(wire.toByteArray()).read() }
    }

    @Test
    fun rejectsNegativeLengthInTheHeader() {
        val wire = ByteArrayOutputStream()
        DataOutputStream(wire).apply {
            writeByte(FrameType.DATA)
            writeInt(-1)
            flush()
        }
        assertThrows(IOException::class.java) { reader(wire.toByteArray()).read() }
    }

    @Test
    fun truncatedFrameThrowsEof() {
        val wire = ByteArrayOutputStream()
        DataOutputStream(wire).apply {
            writeByte(FrameType.DATA)
            writeInt(100)
            write(ByteArray(10)) // faltan 90 bytes
            flush()
        }
        assertThrows(EOFException::class.java) { reader(wire.toByteArray()).read() }
    }

    @Test
    fun writeRejectsOversizedPayload() {
        val io = FrameIO(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        assertThrows(IllegalArgumentException::class.java) {
            io.write(FrameType.DATA, ByteArray(FrameIO.MAX_PAYLOAD + 1))
        }
    }
}
