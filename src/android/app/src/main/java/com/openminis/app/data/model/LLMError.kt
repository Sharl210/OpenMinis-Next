package com.openminis.app.data.model

/** Per-request provider diagnostics; never stored on a shared provider instance. */
data class LLMRequestDiagnostics(
    val statusCode: Int? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val errorResponse: String? = null,
    val requestBody: String? = null,
    val debugInfo: String? = null,
)

sealed class LLMError(message: String, cause: Throwable? = null, val diagnostics: LLMRequestDiagnostics? = null) : Exception(message, cause) {
    class InvalidApiKey(val detail: String = "", diagnostics: LLMRequestDiagnostics? = null) : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail", diagnostics = diagnostics)
    class NetworkError(cause: Throwable, diagnostics: LLMRequestDiagnostics? = null) : LLMError("Network error: ${cause.message}", cause, diagnostics)
    class ProviderError(val detail: String, diagnostics: LLMRequestDiagnostics? = null) : LLMError("Provider error: $detail", diagnostics = diagnostics)
    class DecodingError(cause: Throwable, diagnostics: LLMRequestDiagnostics? = null) : LLMError("Decoding error: ${cause.message}", cause, diagnostics)
    class RateLimited(diagnostics: LLMRequestDiagnostics? = null) : LLMError("Rate limited — please try again later", diagnostics = diagnostics)
    class TransientError(val detail: String, diagnostics: LLMRequestDiagnostics? = null) : LLMError("Transient error: $detail", diagnostics = diagnostics)
    class Cancelled : LLMError("Request was cancelled")
    class Unknown(cause: Throwable?, diagnostics: LLMRequestDiagnostics? = null) : LLMError("Unknown error: ${cause?.message}", cause, diagnostics)

    val isNetworkError: Boolean get() = this is NetworkError
    val isRetryable: Boolean get() = this is NetworkError || this is TransientError
    val isFallbackable: Boolean get() = this is RateLimited || this is InvalidApiKey || this is ProviderError
    val fallbackReason: String get() = when (this) {
        is RateLimited -> "Rate limited"
        is InvalidApiKey -> "Invalid API key"
        is ProviderError -> "Provider error"
        is TransientError -> "Transient error"
        is NetworkError -> "Network error"
        is DecodingError -> "Decoding error"
        is Cancelled -> "Cancelled"
        is Unknown -> "Unknown error"
    }
}
