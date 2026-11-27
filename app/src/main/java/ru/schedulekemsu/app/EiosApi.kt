package ru.schedulekemsu.app

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class EiosReply(val status: Int, val body: JsonObject?)

internal class EiosApi(
    androidVersion: String,
    version: String,
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient = clientBuilder().build(),
    private val trace: ((String) -> Unit)? = null
) {
    // This is the legacy protocol header used by the reference build.
    private val userAgent = "Android $androidVersion / Dart 3.10.8 / Build version $version / kemsuMobile"

    suspend fun send(
        path: String,
        query: Map<String, Int> = emptyMap(),
        body: JsonObject? = null,
        token: String? = null
    ): EiosReply {
        val url = checkNotNull(baseUrl.resolve(path)).newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value.toString()) }
        }.build()
        var request = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            token?.let { header("x-access-token", it) }
            body?.let { post(it.toString().encodeToByteArray().toRequestBody(JSON)) }
        }.build()

        repeat(MAX_REDIRECTS + 1) { redirects ->
            trace?.invoke(
                "send ${request.method} ${request.url.encodedPath} ${request.url.encodedQuery.orEmpty()} " +
                    "ua=$userAgent token=${request.header("x-access-token") != null} " +
                    "keys=${if (request.body == null) "-" else body?.keys?.joinToString(",")} " +
                    "contentType=${request.body?.contentType() ?: "-"}"
            )
            val response = execute(request)
            trace?.invoke("done ${request.url.encodedPath} HTTP ${response.reply.status} redirects=$redirects")
            if (response.location == null ||
                response.reply.status !in if (request.method == "POST") POST_REDIRECTS else GET_REDIRECTS
            ) {
                return response.reply
            }

            val nextUrl = request.url.resolve(response.location) ?: throw IOException()
            if (redirects == MAX_REDIRECTS || !nextUrl.isHttps ||
                (token != null && nextUrl.host != url.host)
            ) throw IOException()
            request = request.newBuilder().url(nextUrl).get().build()
        }
        error("Unreachable")
    }

    private suspend fun execute(request: Request): Received = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val received = response.use {
                        val body = it.body
                        val type = body?.contentType()
                        val isJson = type != null && (
                            (type.type == "application" && type.subtype == "json") ||
                                (type.type == "text" && type.subtype == "json") ||
                                type.subtype.endsWith("+json")
                            )
                        val json = if (isJson) {
                            val raw = body?.string().orEmpty()
                            if (raw.isEmpty()) null else Json.parseToJsonElement(raw) as? JsonObject
                        } else null
                        Received(EiosReply(it.code, json), it.header("Location"))
                    }
                    continuation.resume(received)
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                } catch (e: SerializationException) {
                    continuation.resumeWithException(IOException(e))
                }
            }
        })
    }

    private data class Received(val reply: EiosReply, val location: String?)

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val MAX_REDIRECTS = 5
        private val GET_REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val POST_REDIRECTS = setOf(303)

        fun clientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
    }
}
