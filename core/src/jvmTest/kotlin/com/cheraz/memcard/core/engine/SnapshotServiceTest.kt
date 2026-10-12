package com.cheraz.memcard.core.engine

import com.cheraz.memcard.core.source.SaveFile
import com.cheraz.memcard.core.source.SaveSink
import com.cheraz.memcard.core.source.SaveSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

private fun bytes(n: Int, seed: Int = 1) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

private class MemorySource(
    private val files: Map<String, ByteArray>,
    private val sizeOverride: Map<String, Long> = emptyMap(),
) : SaveSource {
    override fun list(): List<SaveFile> =
        files.map { (path, data) -> SaveFile(path, sizeOverride[path] ?: data.size.toLong()) }

    override fun open(path: String): InputStream = ByteArrayInputStream(files.getValue(path))
}

private class MemorySink : SaveSink {
    val files = mutableMapOf<String, ByteArray>()
    override fun write(path: String, content: InputStream) {
        files[path] = content.readBytes()
    }

    override fun delete(path: String) {
        files.remove(path)
    }
}

class SnapshotServiceTest {
    private val service = SnapshotService()

    @Test
    fun emptySourceHashesLikeEmptyInput() {
        val result = service.snapshot(MemorySource(emptyMap()), null)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", result.hash)
        assertEquals(0, result.fileCount)
    }

    @Test
    fun hashIgnoresListingOrder() {
        val a = bytes(100, 1)
        val b = bytes(200, 2)
        val first = service.snapshot(MemorySource(linkedMapOf("a.bin" to a, "b.bin" to b)), null)
        val second = service.snapshot(MemorySource(linkedMapOf("b.bin" to b, "a.bin" to a)), null)
        assertEquals(first.hash, second.hash)
    }

    @Test
    fun hashChangesWithContentAndWithPath() {
        val base = service.snapshot(MemorySource(mapOf("a.bin" to bytes(100))), null).hash
        val content = service.snapshot(MemorySource(mapOf("a.bin" to bytes(100, 2))), null).hash
        val path = service.snapshot(MemorySource(mapOf("b.bin" to bytes(100))), null).hash
        assertNotEquals(base, content)
        assertNotEquals(base, path)
    }

    @Test
    fun hashOnlyEqualsHashWithZip() {
        val source = MemorySource(mapOf("PARAM.SFO" to bytes(1000), "sub/DATA.BIN" to bytes(5000, 7)))
        val zip = ByteArrayOutputStream()
        val withZip = service.snapshot(source, zip)
        val hashOnly = service.snapshot(source, null)
        assertEquals(hashOnly.hash, withZip.hash)
        assertEquals(zip.size().toLong(), withZip.zipSize)
        assertEquals(0L, hashOnly.zipSize)
    }

    @Test
    fun failsWhenAFileIsLargerThanListed() {
        val source = MemorySource(mapOf("a" to bytes(10)), sizeOverride = mapOf("a" to 9L))
        assertFailsWith<SourceChangedException> { service.snapshot(source, null) }
    }

    @Test
    fun failsWhenAFileIsSmallerThanListed() {
        val source = MemorySource(mapOf("a" to bytes(10)), sizeOverride = mapOf("a" to 11L))
        assertFailsWith<SourceChangedException> { service.snapshot(source, null) }
    }

    @Test
    fun restoreRoundTripLeavesTargetEqualToSnapshot() {
        val original = mapOf(
            "PARAM.SFO" to bytes(1000),
            "sub/DATA.BIN" to bytes(5000, 7),
            "ICON0.PNG" to bytes(10, 3),
        )
        val zip = ByteArrayOutputStream()
        val snap = service.snapshot(MemorySource(original), zip)

        val sink = MemorySink().apply {
            files["extra.txt"] = byteArrayOf(9)
            files["PARAM.SFO"] = byteArrayOf(0)
        }
        val existing = MemorySource(sink.files.toMap()).list()

        val result = SaveRestorer().restore(ByteArrayInputStream(zip.toByteArray()), existing, sink)

        assertEquals(3, result.written)
        assertEquals(1, result.deleted)
        assertEquals(original.keys, sink.files.keys)
        original.forEach { (path, data) -> assertContentEquals(data, sink.files.getValue(path)) }
        assertEquals(snap.hash, service.snapshot(MemorySource(sink.files), null).hash)
    }

    @Test
    fun emptySnapshotRestoresToAnEmptyTarget() {
        val zip = ByteArrayOutputStream()
        service.snapshot(MemorySource(emptyMap()), zip)
        val sink = MemorySink().apply { files["old.bin"] = byteArrayOf(1) }
        val existing = MemorySource(sink.files.toMap()).list()

        val result = SaveRestorer().restore(ByteArrayInputStream(zip.toByteArray()), existing, sink)

        assertEquals(0, result.written)
        assertEquals(1, result.deleted)
        assertEquals(emptySet(), sink.files.keys)
    }

    @Test
    fun restoreRejectsZipSlip() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("../evil.txt"))
            z.write(1)
            z.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> {
            SaveRestorer().restore(ByteArrayInputStream(bos.toByteArray()), emptyList(), MemorySink())
        }
    }
}
