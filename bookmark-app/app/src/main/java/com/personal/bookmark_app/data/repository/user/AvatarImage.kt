package com.personal.bookmark_app.data.repository.user

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

data class AvatarImage(val bitmap: Bitmap, val format: Bitmap.CompressFormat)

internal const val AVATAR_EDGE = 512
internal const val MAX_AVATAR_ZOOM = 3f

internal fun ContentResolver.readAvatar(uri: Uri): AvatarImage {
    val bytes = openInputStream(uri)?.readAvatarBytes() ?: error("無法讀取所選的圖片")
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "無法讀取所選的圖片" }
    val format = when (options.outMimeType) {
        "image/jpeg" -> Bitmap.CompressFormat.JPEG
        "image/png", "image/webp" -> Bitmap.CompressFormat.PNG
        else -> error("僅支援 JPG、PNG 或 WebP 圖片")
    }
    require(options.outWidth.toLong() * options.outHeight <= 16_000_000) {
        "頭像不可超過 1600 萬像素"
    }

    // Keep enough detail for the tightest crop when the source permits it.
    options.inSampleSize = Integer.highestOneBit(
        (minOf(options.outWidth, options.outHeight) / (AVATAR_EDGE * MAX_AVATAR_ZOOM).toInt()).coerceAtLeast(1)
    )
    options.inJustDecodeBounds = false
    val exif = ExifInterface(bytes.inputStream())
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: error("無法讀取所選的圖片")
    if (exif.rotationDegrees == 0 && !exif.isFlipped) return AvatarImage(bitmap, format)

    val orientation = Matrix().apply {
        if (exif.isFlipped) setScale(-1f, 1f)
        postRotate(exif.rotationDegrees.toFloat())
    }
    val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, orientation, false)
    if (upright !== bitmap) bitmap.recycle()
    return AvatarImage(upright, format)
}
