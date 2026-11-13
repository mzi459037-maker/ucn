package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object MediaUtils {
    fun readAndCompressImage(context: Context, uri: Uri, maxDim: Int = 1024, maxBytes: Int = 220_000): Pair<ByteArray, String>? {
        return try {
            val resolver = context.contentResolver
            val original = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return null

            val scale = max(original.width, original.height).let { largest ->
                if (largest > maxDim) maxDim.toFloat() / largest.toFloat() else 1f
            }
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt().coerceAtLeast(1),
                    (original.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                original
            }

            var quality = 84
            val baos = ByteArrayOutputStream()
            do {
                baos.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                quality -= 10
            } while (baos.size() > maxBytes && quality >= 30)

            val fileName = resolveDisplayName(context, uri) ?: "photo_${System.currentTimeMillis()}.jpg"
            baos.toByteArray() to fileName
        } catch (_: Exception) {
            null
        }
    }

    fun compressAvatarImage(context: Context, uri: Uri, targetSize: Int = 256, maxBytes: Int = 42_000): String? {
        return try {
            val original = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return null

            val side = min(original.width, original.height).coerceAtLeast(1)
            val xOffset = ((original.width - side) / 2).coerceAtLeast(0)
            val yOffset = ((original.height - side) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(original, xOffset, yOffset, side, side)
            val scaled = if (side > targetSize) {
                Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
            } else {
                cropped
            }

            var quality = 86
            val baos = ByteArrayOutputStream()
            do {
                baos.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                quality -= 8
            } while (baos.size() > maxBytes && quality >= 34)

            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun decodeAvatarBase64(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun readSmallDocumentBytes(context: Context, uri: Uri, maxBytes: Int = 350_000): Triple<ByteArray, String, String>? {
        return try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = resolveDisplayName(context, uri) ?: "attachment_${System.currentTimeMillis()}"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.size > maxBytes) return null
            Triple(bytes, mime, name)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun formatShortTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return ""
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }

    fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }
}
