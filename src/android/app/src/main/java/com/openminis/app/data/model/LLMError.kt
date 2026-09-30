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

    // Retry / fallback predicates deliberately do NOT live here. They look like
    // one question but are two, with different answers, and both are decided at
    // the call site where the full context exists (status code, provider detail,
    // and the user's strategy):
    //   - "would repeating the SAME request plausibly succeed?" →
    //     ChatViewModel.runtimeFailureFor
    //   - "should this request fall back to another model?" →
    //     the fallback loop's shouldFallback, which is also gated by the user's
    //     FallbackStrategy (where `none` is a hard no-fallback switch).
}
