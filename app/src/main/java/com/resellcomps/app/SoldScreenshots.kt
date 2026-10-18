package com.resellcomps.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * The user's screenshots of eBay's sold results, prepared for the AI: tall phone screenshots are
 * cut into overlapping pieces so the small print stays readable at the size free AI tiers accept,
 * and the phone's own text reader copies every word and price to keep the numbers exact.
 */
object SoldScreenshots {
    /** One screenshot: its readable pieces, and the text on it top to bottom. */
    class Shot(val pieces: List<ByteArray>, val text: String)

    private const val PIECE_EDGE = 1280
    private const val OVERLAP = 120

    suspend fun load(context: Context, uri: Uri): Shot {
        val bitmap = decode(context, uri)
        return Shot(cut(bitmap), readText(bitmap))
    }

    private fun decode(context: Context, uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                ?: error("Could not read the screenshot")
        }

    /** Pieces about as tall as they are wide (plus overlap), each shrunk to [PIECE_EDGE]. */
    private fun cut(bitmap: Bitmap): List<ByteArray> {
        val w = bitmap.width
        val pieceHeight = (w * 1.25).toInt().coerceAtLeast(1)
        if (bitmap.height <= pieceHeight + OVERLAP) return listOf(jpeg(bitmap))
        val pieces = mutableListOf<ByteArray>()
        var top = 0
        while (top < bitmap.height) {
            val h = min(pieceHeight, bitmap.height - top)
            pieces += jpeg(Bitmap.createBitmap(bitmap, 0, top, w, h))
            if (top + h >= bitmap.height) break
            top += pieceHeight - OVERLAP
        }
        return pieces
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val edge = max(bitmap.width, bitmap.height)
        val scaled = if (edge > PIECE_EDGE) {
            val s = PIECE_EDGE.toFloat() / edge
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true)
        } else bitmap
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    /** Every line of text on the screenshot, top to bottom, read at full size on the phone. */
    private suspend fun readText(bitmap: Bitmap): String {
        val result = runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bitmap, 0)).await()
        }.getOrNull() ?: return ""
        return result.textBlocks.flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .joinToString("\n") { it.text }
    }
}
