package com.resellcomps.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/**
 * Free AI identification through providers with no-card free tiers that host
 * vision models behind the common "chat completions" API:
 *  - Groq (keys start with "gsk_"): Meta Llama 4 vision models.
 *  - OpenRouter (keys start with "sk-or-"): its free router, which picks a free
 *    model that can read images.
 * The provider is detected from the key, so the user only pastes one key.
 */
class FreeVisionAi(
    private val apiKey: String,
    /** Your own server (e.g. Ollama on a home PC); when set, it's used instead of the key's provider. */
    serverUrl: String? = null,
    http: OkHttpClient = Http.client,
) {
    enum class Provider(val label: String, val baseUrl: String, val maxImages: Int) {
        GROQ("Groq", "https://api.groq.com/openai/v1", 5),
        OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", 6),
        SELF("Your server", "", 6),
    }

    /** Thrown when your own server doesn't answer (computer off or asleep), so the app can fall back. */
    class ServerOffline(msg: String) : IOException(msg)

    val provider: Provider = if (!serverUrl.isNullOrBlank()) Provider.SELF else detect(apiKey)
        ?: throw IOException("That key isn't a Groq key (starts with gsk_) or an OpenRouter key (starts with sk-or-).")

    private val baseUrl: String = if (provider == Provider.SELF) normalizeServerUrl(serverUrl!!) else provider.baseUrl

    // A sleeping home PC never answers, so give up on connecting quickly and fall back.
    private val http: OkHttpClient =
        if (provider == Provider.SELF) http.newBuilder().connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build() else http

    /**
     * @param onWait called with the seconds the app is pausing when the provider's
     *   per-minute free limit is hit, so the screen can say so instead of failing.
     */
    fun identify(photos: List<ByteArray>, hint: String? = null, onWait: (Int) -> Unit = {}): ItemIdentification =
        withWorkingModel(onWait) { model ->
            val content = chat(model, IdentifyPrompt.SYSTEM + "\n\n" + IdentifyPrompt.JSON_REPLY, IdentifyPrompt.userText(photos.size, hint), photos, 1000)
            IdentifyPrompt.fromJson(extractJsonObject(content))
        }

    /**
     * "Dig deeper": a second pass that only dates the tags, listing evidence for an older and a
     * newer date before deciding. [currentQuery] is the search on screen, adjusted for the new call.
     */
    fun digDeeper(photos: List<ByteArray>, item: ItemIdentification, currentQuery: String, onWait: (Int) -> Unit = {}): ItemIdentification =
        withWorkingModel(onWait) { model ->
            val content = chat(model, TagExpert.SYSTEM, TagExpert.userText(item), photos, 1400)
            TagExpert.apply(item, extractJsonObject(content), currentQuery)
        }

    /**
     * Reads the sold listings off the user's screenshots of eBay's sold results. [screenText] is
     * what the phone's text reader got from the same screenshots, to keep the numbers exact.
     */
    fun readSoldListings(
        screenshots: List<ByteArray>, item: ItemIdentification, query: String, screenText: String?,
        onWait: (Int) -> Unit = {},
    ): List<SoldComp> =
        withWorkingModel(onWait) { model ->
            val content = chat(model, SoldValue.SYSTEM, SoldValue.userText(item, query, screenText), screenshots, 3000)
            SoldValue.parse(extractJsonObject(content))
        }

    /** Finds a model that reads photos (remembering it), and waits out per-minute limits. */
    private fun <T> withWorkingModel(onWait: (Int) -> Unit, request: (String) -> T): T {
        val working = workingModels[baseUrl]
        val candidates = when (provider) {
            Provider.OPENROUTER -> listOf(OPENROUTER_FREE)
            // Groq and your own server: rank the models they list, best photo readers first.
            else -> listOfNotNull(working) +
                (rankedModels[baseUrl] ?: rankGroqModels(get("$baseUrl/models")).also { rankedModels[baseUrl] = it })
                    .filter { it != working }
        }
        if (candidates.isEmpty()) throw IOException("No AI model on your ${provider.label} account can read photos right now.")
        var last: IOException? = null
        // Groq retires models often and doesn't say which ones read images, so try likely ones in
        // order and remember the first that accepts the photo.
        for (model in candidates.take(MAX_TRIES)) {
            try {
                val result = waitingOutLimits(onWait) { request(model) }
                workingModels[baseUrl] = model
                return result
            } catch (e: ModelUnusable) {
                if (model == workingModels[baseUrl]) workingModels.remove(baseUrl)
                last = e
            }
            // LimitException (a long wait = the daily limit, shared by all models) propagates.
        }
        throw last ?: IOException("${provider.label} didn't answer.")
    }

    /**
     * Free tiers also cap requests and tokens per minute, and photos use a lot of tokens, so a
     * second scan right after the first can be refused for a few seconds. Wait that out (up to
     * [MAX_WAIT_SECONDS] at a time, twice) rather than failing.
     */
    private fun <T> waitingOutLimits(onWait: (Int) -> Unit, call: () -> T): T {
        var waits = 0
        while (true) {
            try {
                return try {
                    call()
                } catch (e: java.io.InterruptedIOException) {
                    call() // one retry on a slow reply
                }
            } catch (e: LimitException) {
                val wait = e.waitSeconds
                if (wait == null || wait > MAX_WAIT_SECONDS || waits >= 2) throw e
                waits++
                val secs = kotlin.math.ceil(wait).toInt().coerceAtLeast(1)
                onWait(secs)
                Thread.sleep(secs * 1000L + 500)
            }
        }
    }

    /** The provider's free limit was hit (per minute, or the daily one if the wait is long). */
    class LimitException(msg: String, val waitSeconds: Double?) : IOException(msg)

    /** The model is gone, or it can't take images: try the next one. */
    private class ModelUnusable(msg: String) : IOException(msg)

    /** One chat request with the photos; returns the reply text. */
    private fun chat(model: String, system: String, userText: String, photos: List<ByteArray>, maxTokens: Int): String {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", maxTokens)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", userText) }
                        photos.take(provider.maxImages).forEach { photo ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(photo))
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
        return contentOf(execute(req))
    }

    private fun get(url: String): String = execute(Request.Builder().url(url).get())

    private fun execute(req: Request.Builder): String {
        val call = req.header("Authorization", "Bearer $apiKey")
            .apply { if (provider == Provider.OPENROUTER) header("X-Title", "Resell Comps") }
            .build()
        val response = try {
            http.newCall(call).execute()
        } catch (e: IOException) {
            // Your own server: a refused/unreachable connection means the computer is off.
            if (provider == Provider.SELF && isUnreachable(e)) {
                throw ServerOffline("Your computer didn't answer (it may be off or asleep).")
            }
            throw e
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.isSuccessful -> return text
                resp.code == 401 -> throw IOException("${provider.label} rejected the key. Check it in Settings.")
                resp.code == 429 -> {
                    val wait = retryAfterSeconds(resp.header("retry-after"), text)
                    throw LimitException(
                        if (wait != null && wait > MAX_WAIT_SECONDS) {
                            "${provider.label}'s free daily limit is used up. It resets in about ${formatWait(wait)}."
                        } else {
                            "${provider.label}'s free limit is used up for now. Try again in a minute."
                        },
                        wait,
                    )
                }
                resp.code == 402 -> throw IOException("${provider.label} says this account needs credit for that request.")
                resp.code == 400 || resp.code == 404 || resp.code == 422 -> throw ModelUnusable(
                    "${provider.label} has no model that can read photos for this account right now (${errorMessage(text)}). " +
                        "An OpenRouter key (openrouter.ai, free) works as a backup."
                )
                else -> throw IOException("${provider.label} error (HTTP ${resp.code}): ${errorMessage(text)}")
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** OpenRouter's router that picks a free model supporting the request (images included). */
        const val OPENROUTER_FREE = "openrouter/free"

        /** Per server: the model that last accepted a photo, tried first next time. */
        private val workingModels = java.util.concurrent.ConcurrentHashMap<String, String>()

        private const val MAX_TRIES = 4

        /** Longest pause the app makes by itself for a per-minute limit. */
        const val MAX_WAIT_SECONDS = 45.0

        private val rankedModels = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

        /** "100.1.2.3:11434" or "http://pc:11434/" → "http://100.1.2.3:11434/v1" (Ollama's OpenAI-style API). */
        fun normalizeServerUrl(url: String): String {
            var u = url.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            if (!u.endsWith("/v1")) u = "$u/v1"
            return u
        }

        private fun isUnreachable(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
            it is java.net.ConnectException || it is java.net.NoRouteToHostException ||
                it is java.net.UnknownHostException || it is java.net.SocketTimeoutException
        }

        /**
         * How long the provider asks us to wait: the Retry-After header, or Groq's
         * "Please try again in 12.5s" / "in 1m3.2s" / "in 7h12m" wording.
         */
        fun retryAfterSeconds(header: String?, body: String): Double? {
            header?.trim()?.toDoubleOrNull()?.let { return it }
            val m = Regex("""try again in\s+(?:(\d+)h)?(?:(\d+)m(?!s))?(?:([\d.]+)s)?(?:([\d.]+)ms)?""").find(body) ?: return null
            val (h, min, sec, ms) = m.destructured
            if (h.isEmpty() && min.isEmpty() && sec.isEmpty() && ms.isEmpty()) return null
            return (h.toDoubleOrNull() ?: 0.0) * 3600 + (min.toDoubleOrNull() ?: 0.0) * 60 +
                (sec.toDoubleOrNull() ?: 0.0) + (ms.toDoubleOrNull() ?: 0.0) / 1000
        }

        private fun formatWait(seconds: Double): String {
            val mins = (seconds / 60).toInt()
            return if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "${mins.coerceAtLeast(1)} min"
        }


        fun detect(key: String): Provider? = when {
            key.startsWith("gsk_") -> Provider.GROQ
            key.startsWith("sk-or-") -> Provider.OPENROUTER
            else -> null
        }

        /** Model families on Groq that never read images (speech, safety, embeddings, text-only). */
        private val NOT_VISION = listOf("whisper", "tts", "playai", "orpheus", "guard", "embed", "gpt-oss", "compound", "allam", "distil", "safeguard")

        /**
         * Groq's models, likeliest image readers first: names that say so (vision, -vl, Llama 4),
         * then Qwen (the family reported to read images on Groq after Llama 4 was retired), then
         * Gemma, then any other chat model. Known text-only families are left out.
         */
        fun rankGroqModels(body: String): List<String> {
            val ids = ((json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray).orEmpty()
                .mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    if ((o["active"] as? JsonPrimitive)?.contentOrNull == "false") return@mapNotNull null
                    (o["id"] as? JsonPrimitive)?.contentOrNull
                }
                .filter { id -> NOT_VISION.none { it in id.lowercase() } }
            fun rank(id: String): Int = id.lowercase().let { m ->
                when {
                    "vision" in m || "vl" in m || "llama-4" in m || "minicpm-v" in m || "llava" in m -> 0
                    "qwen" in m -> 1
                    "gemma" in m || "pixtral" in m || "llava" in m -> 2
                    else -> 3
                }
            }
            // Newer versions first within a rank (e.g. qwen3.8 before qwen3.6).
            return ids.sortedWith(compareBy<String> { rank(it) }.thenByDescending { it })
        }

        fun parseAnswer(body: String): ItemIdentification = IdentifyPrompt.fromJson(extractJsonObject(contentOf(body)))

        /** The reply text from a chat-completions response. */
        fun contentOf(body: String): String {
            val root = json.parseToJsonElement(body) as? JsonObject ?: throw IOException("Unreadable answer from the AI.")
            val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                ?: throw IOException("The AI didn't answer.")
            return (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }

        /** Free models sometimes wrap the JSON in ```json fences or a sentence; take the outermost {...}. */
        fun extractJsonObject(text: String): JsonObject {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) throw IOException("The AI didn't return a usable answer. Try again.")
            return runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as JsonObject }
                .getOrElse { throw IOException("The AI didn't return a usable answer. Try again.") }
        }

        private fun errorMessage(body: String): String = runCatching {
            val err = (json.parseToJsonElement(body) as JsonObject)["error"]
            ((err as? JsonObject)?.get("message") as? JsonPrimitive)?.content ?: (err as JsonPrimitive).content
        }.getOrNull() ?: body.take(200)
    }
}
