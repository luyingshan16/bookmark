package com.personal.bookmark_app.data.repository.user

import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class AvatarStreamsTest {
    @Test
    fun preservesSmallImageBytes() {
        val bytes = byteArrayOf(1, 2, 3, -1)
        assertArrayEquals(bytes, bytes.inputStream().readAvatarBytes())
    }

    @Test
    fun acceptsExactLimitAndClosesStream() {
        val input = CountingStream(MAX_AVATAR_BYTES)
        val bytes = input.readAvatarBytes()
        assertEquals(MAX_AVATAR_BYTES, bytes.size)
        assertTrue(bytes.all { it == 7.toByte() })
        assertTrue(input.closed)
    }

    @Test
    fun stopsAfterOneExtraByteAndClosesStream() {
        val input = CountingStream(Int.MAX_VALUE)
        val error = assertThrows(IOException::class.java) { input.readAvatarBytes() }
        assertEquals("圖片大小不可超過 10 MB", error.message)
        assertEquals(MAX_AVATAR_BYTES + 1, input.consumed)
        assertTrue(input.closed)
    }

    @Test
    fun rejectsEmptyImageAndClosesStream() {
        val input = CountingStream(0)
        val error = assertThrows(IllegalArgumentException::class.java) { input.readAvatarBytes() }
        assertEquals("所選圖片是空的", error.message)
        assertTrue(input.closed)
    }

    @Test
    fun closesStreamOnReadFailure() {
        val failure = IOException("read failed")
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw failure
            override fun close() { closed = true }
        }
        assertSame(failure, assertThrows(IOException::class.java) { input.readAvatarBytes() })
        assertTrue(closed)
    }

    @Test
    fun outputAcceptsExactLimitAndRejectsSingleExtraByte() {
        val output = LimitedAvatarOutputStream("處理後的圖片不可超過 10 MB")
        val chunk = ByteArray(1024)
        repeat(MAX_AVATAR_BYTES / chunk.size) { output.write(chunk) }
        assertEquals(MAX_AVATAR_BYTES, output.toByteArray().size)
        assertThrows(IOException::class.java) { output.write(1) }
        // Even if an encoder swallows the write exception, no partial PNG can escape.
        val error = assertThrows(IOException::class.java) { output.checkLimit() }
        assertEquals("處理後的圖片不可超過 10 MB", error.message)
        assertThrows(IOException::class.java) { output.toByteArray() }
    }

    @Test
    fun outputRejectsOversizedChunkAndRemainsFailed() {
        val output = LimitedAvatarOutputStream("too large")
        assertThrows(IOException::class.java) { output.write(ByteArray(MAX_AVATAR_BYTES + 1)) }
        assertThrows(IOException::class.java) { output.write(byteArrayOf(1)) }
        assertThrows(IOException::class.java) { output.toByteArray() }
    }

    @Test
    fun outputPreservesOffsetsAndSingleBytes() {
        val output = LimitedAvatarOutputStream("too large")
        output.write(byteArrayOf(9, 2, 3, 9), 1, 2)
        output.write(255)
        assertArrayEquals(byteArrayOf(2, 3, -1), output.toByteArray())
    }

    private class CountingStream(private val length: Int) : InputStream() {
        var consumed = 0
        var closed = false

        override fun read(): Int = if (consumed == length) -1 else {
            consumed++
            7
        }

        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (consumed == length) return -1
            val actual = minOf(count, length - consumed)
            bytes.fill(7, offset, offset + actual)
            consumed += actual
            return actual
        }

        override fun close() { closed = true }
    }
}
