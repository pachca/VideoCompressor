package com.primaverahq.videocompressor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamableVideoTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `moov before mdat is optimized`() {
        val input = videoFile(atom("moov"), atom("mdat"))

        assertTrue(StreamableVideo.isFastStartOptimized(input))
    }

    @Test
    fun `mdat before moov is not optimized`() {
        val input = videoFile(atom("mdat"), atom("moov"))

        assertFalse(StreamableVideo.isFastStartOptimized(input))
    }

    @Test
    fun `empty mdat is ignored`() {
        val input = videoFile(atom("mdat", byteArrayOf()), atom("moov"), atom("mdat"))

        assertTrue(StreamableVideo.isFastStartOptimized(input))
    }

    @Test
    fun `unknown atoms are skipped`() {
        val input = videoFile(atom("sidx"), atom("moov"), atom("mdat"))

        assertTrue(StreamableVideo.isFastStartOptimized(input))
    }

    @Test
    fun `extended atom sizes are supported`() {
        val input = videoFile(extendedAtom("moov"), extendedAtom("mdat"))

        assertTrue(StreamableVideo.isFastStartOptimized(input))
    }

    @Test
    fun `mdat extending to eof is supported`() {
        val input = videoFile(atom("moov"), eofAtom("mdat"))

        assertTrue(StreamableVideo.isFastStartOptimized(input))
    }

    @Test
    fun `invalid atom size is not optimized`() {
        val invalidAtom = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(7)
            .put("free".toByteArray())
            .array()

        assertFalse(StreamableVideo.isFastStartOptimized(videoFile(invalidAtom)))
    }

    @Test
    fun `atom exceeding file size is not optimized`() {
        val invalidAtom = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(100)
            .put("free".toByteArray())
            .array()

        assertFalse(StreamableVideo.isFastStartOptimized(videoFile(invalidAtom)))
    }

    @Test
    fun `truncated header is not optimized`() {
        assertFalse(StreamableVideo.isFastStartOptimized(videoFile(byteArrayOf(0, 0, 0, 8))))
    }

    @Test
    fun `file without media data is not optimized`() {
        assertFalse(StreamableVideo.isFastStartOptimized(videoFile(atom("moov"))))
    }

    @Test
    fun `inspection stops at first non-empty mdat`() {
        val trailingInvalidAtom = byteArrayOf(0, 0, 0, 7)
        val optimized = videoFile(atom("moov"), atom("mdat"), trailingInvalidAtom)
        val notOptimized = videoFile(atom("mdat"), trailingInvalidAtom)

        assertTrue(StreamableVideo.isFastStartOptimized(optimized))
        assertFalse(StreamableVideo.isFastStartOptimized(notOptimized))
    }

    @Test
    fun `missing file is not optimized`() {
        assertFalse(StreamableVideo.isFastStartOptimized(File(temporaryFolder.root, "missing.mp4")))
    }

    private fun videoFile(vararg atoms: ByteArray): File {
        return temporaryFolder.newFile().apply {
            outputStream().use { output ->
                atoms.forEach(output::write)
            }
        }
    }

    private fun atom(type: String, payload: ByteArray = byteArrayOf(0)): ByteArray {
        return ByteBuffer.allocate(8 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(8 + payload.size)
            .put(type.toByteArray())
            .put(payload)
            .array()
    }

    private fun extendedAtom(type: String, payload: ByteArray = byteArrayOf(0)): ByteArray {
        return ByteBuffer.allocate(16 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(1)
            .put(type.toByteArray())
            .putLong((16 + payload.size).toLong())
            .put(payload)
            .array()
    }

    private fun eofAtom(type: String, payload: ByteArray = byteArrayOf(0)): ByteArray {
        return ByteBuffer.allocate(8 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .put(type.toByteArray())
            .put(payload)
            .array()
    }
}
