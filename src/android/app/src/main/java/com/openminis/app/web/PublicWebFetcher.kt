package com.openminis.app.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Bounded HTTP text fetcher. It intentionally does not emulate authentication,
 * CAPTCHA, paywalls, access control, or certificate failures.
 */
class PublicWebFetcher(
    private val clientFactory: (Dns) -> OkHttpClient = { dns ->
        OkHttpClient.Builder()
            .dns(dns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    },
) {
    suspend fun fetch(url: String, options: WebFetchOptions = WebFetchOptions()): WebFetchResult =
        withContext(Dispatchers.IO) {
            val bounded = options.normalized()
            var current = WebAccessPolicy.validateUrl(url).getOrElse {
                return@withContext WebFetchResult.Failure(url, WebFetchFailure.INVALID_URL, it.message.orEmpty())
            }
            val client = clientFactory(BoundedDns).newBuilder()
                .callTimeout(bounded.timeoutMs, TimeUnit.MILLISECONDS)
                .connectTimeout(bounded.timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(bounded.timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            repeat(bounded.maxRedirects + 1) { hop ->
                val request = Request.Builder().url(current.toString()).get().build()
                val response = runCatching { client.newCall(request).execute() }.getOrElse {
                    return@withContext WebFetchResult.Failure(current.toString(), WebFetchFailure.NETWORK, it.message.orEmpty())
                }
                response.use {
                    if (it.isRedirect) {
                        if (hop >= bounded.maxRedirects) {
                            return@withContext WebFetchResult.Failure(current.toString(), WebFetchFailure.REDIRECT_LIMIT, "Too many redirects")
                        }
                        val next = it.header("Location")?.let { location -> current.resolve(location) }
                            ?: return@withContext WebFetchResult.Failure(current.toString(), WebFetchFailure.INVALID_REDIRECT, "Redirect location missing")
                        current = WebAccessPolicy.validateUrl(next.toString()).getOrElse { error ->
                            return@withContext WebFetchResult.Failure(next.toString(), WebFetchFailure.INVALID_REDIRECT, error.message.orEmpty())
                        }
                    } else {
                        val contentLength = it.header("Content-Length")?.toLongOrNull()
                        if (contentLength != null && contentLength > bounded.maxBytes) {
                            return@withContext WebFetchResult.Failure(current.toString(), WebFetchFailure.RESPONSE_TOO_LARGE, "Content-Length exceeds limit")
                        }
                        if (!it.isSuccessful) {
                            return@withContext WebFetchResult.Failure(current.toString(), WebFetchFailure.HTTP, "HTTP ${it.code}")
                        }
                        val body = it.body ?: return@withContext WebFetchResult.Failure(current.toString(), WebFetchFailure.EMPTY, "Response body missing")
                        val bytes = body.source().readByteArray(bounded.maxBytes + 1L)
                        if (bytes.size > bounded.maxBytes) {
                            return@withContext WebFetchResult.Failure(current.toString(), WebFetchFailure.RESPONSE_TOO_LARGE, "Response body exceeds limit")
                        }
                        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                        return@withContext WebFetchResult.Success(current.toString(), bytes.toString(charset))
                    }
                }
            }
            WebFetchResult.Failure(current.toString(), WebFetchFailure.REDIRECT_LIMIT, "Redirect limit reached")
        }

    private object BoundedDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = InetAddress.getAllByName(hostname).toList()
            WebAccessPolicy.validateResolvedAddresses(hostname, addresses).getOrThrow()
            return addresses
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 1_000_000
    }
}

data class WebFetchOptions(
    val timeoutMs: Long = 20_000L,
    val maxBytes: Int = 1_000_000,
    val maxRedirects: Int = 3,
) {
    fun normalized() = copy(
        timeoutMs = timeoutMs.coerceIn(1_000L, 120_000L),
        maxBytes = maxBytes.coerceIn(4_096, 5_000_000),
        maxRedirects = maxRedirects.coerceIn(0, 5),
    )
}

sealed interface WebFetchResult {
    data class Success(val url: String, val body: String) : WebFetchResult
    data class Failure(val url: String, val reason: WebFetchFailure, val detail: String) : WebFetchResult
}

enum class WebFetchFailure { INVALID_URL, NETWORK, HTTP, EMPTY, RESPONSE_TOO_LARGE, REDIRECT_LIMIT, INVALID_REDIRECT }

private fun okhttp3.ResponseBody.sourceReadByteArray(maxBytes: Long): ByteArray = source().readByteArray(maxBytes)
