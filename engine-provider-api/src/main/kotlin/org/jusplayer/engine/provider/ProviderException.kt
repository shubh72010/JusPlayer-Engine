package org.jusplayer.engine.provider

/**
 * The engine's own error model for provider failures.
 *
 * Provider implementations must translate their internal exceptions into one of
 * these sealed subtypes, so applications never see extractor-specific exceptions.
 */
sealed class ProviderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The requested resource does not exist (e.g. 404, removed video). */
    class NotFound(message: String, cause: Throwable? = null) : ProviderException(message, cause)

    /** A network failure occurred while contacting the provider. */
    class Network(message: String, cause: Throwable? = null) : ProviderException(message, cause)

    /** The provider rate-limited the request (e.g. 429, reCAPTCHA). */
    class RateLimited(message: String, cause: Throwable? = null) : ProviderException(message, cause)

    /** The extractor failed to parse the provider response. */
    class ExtractionFailed(message: String, cause: Throwable? = null) : ProviderException(message, cause)

    /** The provider does not support the requested operation. */
    class Unsupported(message: String) : ProviderException(message)
}
