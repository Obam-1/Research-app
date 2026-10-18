package com.resellcomps.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.resellcomps.core.FreeVisionAi
import com.resellcomps.core.IdentifyPrompt
import com.resellcomps.core.ItemIdentification
import com.resellcomps.core.SoldComp
import com.resellcomps.core.SoldValue
import com.resellcomps.core.TagExpert
import java.io.IOException

/**
 * Gemini Nano running on the phone itself (through Android's AICore, via ML Kit's Prompt API):
 * free, unlimited and offline, but smaller and slower than the cloud models, so the app uses it
 * as the backup when the free cloud limit is used up or there's no signal.
 */
class OnPhoneAi {
    private val model: GenerativeModel = Generation.getClient(generationConfig { })

    class Unavailable(msg: String) : IOException(msg)

    /** Makes sure the on-phone model is there, letting Android download it the first time. */
    private suspend fun ensureReady(onProgress: (String) -> Unit) {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> return
            FeatureStatus.UNAVAILABLE -> throw Unavailable("This phone doesn't offer the on-phone AI (Gemini Nano).")
            else -> {
                onProgress("Getting the on-phone AI ready (one-time download)…")
                model.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadFailed -> throw Unavailable("Couldn't download the on-phone AI: ${status.e.message}")
                        is DownloadStatus.DownloadCompleted -> onProgress("On-phone AI ready")
                        else -> {}
                    }
                }
            }
        }
    }

    suspend fun identify(photos: List<ByteArray>, hint: String?, onProgress: (String) -> Unit): ItemIdentification {
        ensureReady(onProgress)
        onProgress("Identifying on the phone…")
        val text = ask(
            system = IdentifyPrompt.SYSTEM + "\n\n" + IdentifyPrompt.JSON_REPLY,
            prompt = IdentifyPrompt.userText(photos.size, hint),
            photos = photos,
        )
        return IdentifyPrompt.fromJson(FreeVisionAi.extractJsonObject(text))
    }

    suspend fun digDeeper(photos: List<ByteArray>, item: ItemIdentification, currentQuery: String, onProgress: (String) -> Unit): ItemIdentification {
        ensureReady(onProgress)
        onProgress("Checking the tag on the phone…")
        val text = ask(TagExpert.SYSTEM, TagExpert.userText(item), photos)
        return TagExpert.apply(item, FreeVisionAi.extractJsonObject(text), currentQuery)
    }

    /**
     * Sold listings from the text the phone read off the screenshots (the on-phone model can't
     * read small print from images, so it gets the text only).
     */
    suspend fun readSoldListings(screenText: String, item: ItemIdentification, query: String, onProgress: (String) -> Unit): List<SoldComp> {
        if (screenText.isBlank()) throw IOException("No text could be read from those screenshots.")
        ensureReady(onProgress)
        onProgress("Reading the sold listings on the phone…")
        val text = run(SoldValue.SYSTEM, SoldValue.userText(item, query, screenText), emptyList())
        return SoldValue.parse(FreeVisionAi.extractJsonObject(text))
    }

    /**
     * One request. Sends up to three photos (item, tag, extra); if the model won't take several
     * images at once, retries with the first photo only.
     */
    private suspend fun ask(system: String, prompt: String, photos: List<ByteArray>): String {
        val bitmaps = photos.take(3).mapNotNull { decode(it) }
        return try {
            run(system, prompt, bitmaps)
        } catch (e: Exception) {
            if (bitmaps.size <= 1) throw e
            run(system, prompt, bitmaps.take(1))
        }
    }

    private suspend fun run(system: String, prompt: String, bitmaps: List<Bitmap>): String {
        val content = Content.builder().apply {
            bitmaps.forEach { addPart(ImagePart(it)) }
            addPart(TextPart(prompt))
        }.build()
        val response = model.generateContent(
            generateContentRequest(content) {
                systemInstruction = SystemInstruction(system)
                maxOutputTokens = 1024
            }
        )
        return response.candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }
            ?: throw IOException("The on-phone AI didn't answer.")
    }

    /** Smaller images keep the on-phone model fast. */
    private fun decode(jpeg: ByteArray): Bitmap? {
        val small = Images.shrink(jpeg, 768)
        return BitmapFactory.decodeByteArray(small, 0, small.size)
    }

    fun close() = model.close()
}
