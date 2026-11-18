package com.personal.bookmark_app.data.repository.user

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal const val MAX_AVATAR_BYTES = 10 * 1024 * 1024

/** Owns and closes the selected image stream, including on failure. */
internal fun InputStream.readAvatarBytes(): ByteArray = use { input ->
    val output = LimitedAvatarOutputStream("圖片大小不可超過 10 MB")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        // Read one extra byte to distinguish an exact-size image from an oversized one.
        val count = input.read(buffer, 0, minOf(buffer.size, MAX_AVATAR_BYTES - total + 1))
        if (count == -1) break
        output.write(buffer, 0, count)
        total += count
    }
    output.toByteArray().also { require(it.isNotEmpty()) { "所選圖片是空的" } }
}

internal class LimitedAvatarOutputStream(private val limitMessage: String) : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    private var exceeded = false

    override fun write(value: Int) {
        checkCapacity(1)
        buffer.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        checkCapacity(length)
        buffer.write(bytes, offset, length)
    }

    private fun checkCapacity(length: Int) {
        if (exceeded || length > MAX_AVATAR_BYTES - buffer.size()) {
            exceeded = true
            throw IOException(limitMessage)
        }
    }

    // Some encoders convert stream exceptions to a false return value.
    fun checkLimit() {
        if (exceeded) throw IOException(limitMessage)
    }

    fun toByteArray(): ByteArray {
        checkLimit()
        return buffer.toByteArray()
    }
}
