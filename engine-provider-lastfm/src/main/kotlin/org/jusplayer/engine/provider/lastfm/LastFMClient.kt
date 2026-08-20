package org.jusplayer.engine.provider.lastfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.JdkHttpTransport
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

/**
 * A Last.fm / Libre.fm API client, ported from the ArchiveTune/JusPlayer app.
 *
 * Supports the mobile/auth-token session flows, `updateNowPlaying` and
 * `track.scrobble` with the required MD5 `api_sig` signing, and both the Last.fm
 * and Libre.fm endpoints. All calls are suspend and run the blocking transport on
 * [Dispatchers.IO].
 */
class LastFMClient(
    private val transport: HttpTransport,
    apiKey: String = "",
    secret: String = "",
    endpoint: String = DEFAULT_API_ENDPOINT,
) {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor(apiKey: String, secret: String, endpoint: String = DEFAULT_API_ENDPOINT) :
        this(JdkHttpTransport(), apiKey, secret, endpoint)

    @Volatile
    var sessionKey: String? = null

    private val endpoint = normalizeEndpoint(endpoint)
    private val apiKey = apiKey
    private val secret = secret

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Fetches a new authentication token for the web auth flow. */
    suspend fun getToken(): LastFmTokenResponse = withContext(Dispatchers.IO) {
        postAndDecode<LastFmTokenResponse>("auth.getToken")
    }

    /** Exchanges an auth [token] for a session (username + session key). */
    suspend fun getSession(token: String): LastFmAuthentication = withContext(Dispatchers.IO) {
        postAndDecode<LastFmAuthentication>("auth.getSession", extra = mapOf("token" to token))
    }

    /**
     * Builds the browser URL a user visits to authorize this app with a [token].
     * Points at Libre.fm when the configured endpoint is the Libre.fm one.
     */
    fun getAuthUrl(token: String): String {
        return if (endpoint == LIBREFM_API_ENDPOINT) {
            "https://libre.fm/api/auth?api_key=$apiKey&token=$token"
        } else {
            "https://www.last.fm/api/auth/?api_key=$apiKey&token=$token"
        }
    }

    /** Authenticates directly with a username + password (mobile flow). */
    suspend fun getMobileSession(
        username: String,
        password: String,
    ): LastFmAuthentication = withContext(Dispatchers.IO) {
        postAndDecode<LastFmAuthentication>(
            "auth.getMobileSession",
            extra = mapOf("username" to username, "password" to password),
        )
    }

    /** Reports the currently playing track (does not count as a scrobble). */
    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        trackNumber: Int? = null,
        duration: Int? = null,
    ): Unit = withContext(Dispatchers.IO) {
        postAndRead(
            "track.updateNowPlaying",
            sessionKey = requireSessionKey(),
            extra =
                buildMap {
                    put("artist", artist)
                    put("track", track)
                    album?.let { put("album", it) }
                    trackNumber?.let { put("trackNumber", it.toString()) }
                    duration?.let { put("duration", it.toString()) }
                },
        )
    }

    /**
     * Scrobbles a track. [timestamp] is the Unix epoch second the song started.
     */
    suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        trackNumber: Int? = null,
        duration: Int? = null,
    ): Unit = withContext(Dispatchers.IO) {
        postAndRead(
            "track.scrobble",
            sessionKey = requireSessionKey(),
            extra =
                buildMap {
                    put("artist[0]", artist)
                    put("track[0]", track)
                    put("timestamp[0]", timestamp.toString())
                    album?.let { put("album[0]", it) }
                    trackNumber?.let { put("trackNumber[0]", it.toString()) }
                    duration?.let { put("duration[0]", it.toString()) }
                },
        )
    }

    fun isInitialized(): Boolean = apiKey.isNotEmpty() && secret.isNotEmpty()

    class LastFmException(
        val code: Int,
        override val message: String,
    ) : Exception(message) {
        override fun toString(): String = "LastFmException(code=$code, message=$message)"
    }

    private fun requireSessionKey(): String = sessionKey ?: throw LastFmException(9, "Session key missing")

    private fun apiSig(paramsForSig: Map<String, String>): String {
        val sorted = paramsForSig.toSortedMap()
        val toHash = sorted.entries.joinToString("") { it.key + it.value } + secret
        val digest = MessageDigest.getInstance("MD5").digest(toHash.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend inline fun <reified T> postAndDecode(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): T = json.decodeFromString(postAndRead(method, sessionKey, extra))

    private fun postAndRead(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val paramsForSig =
            mutableMapOf<String, String>("method" to method, "api_key" to apiKey).apply {
                sessionKey?.let { put("sk", it) }
                putAll(extra)
            }

        val response =
            try {
                transport.post(
                    endpoint,
                    form =
                        paramsForSig + mapOf(
                            "api_sig" to apiSig(paramsForSig),
                            "format" to "json",
                        ),
                    headers = mapOf(
                        "User-Agent" to "JusPlayer (https://github.com/shubh72010/JusPlayer-Engine)",
                    ),
                )
            } catch (e: IOException) {
                throw LastFmException(0, "Network error contacting Last.fm: ${e.message}")
            }

        val responseText = response.body
        if (response.status !in 200..299) {
            throw LastFmException(response.status, "HTTP ${response.status}")
        }
        if (responseText.contains("\"error\"")) {
            val error = json.decodeFromString<LastFmError>(responseText)
            throw LastFmException(error.error, error.message)
        }
        return responseText
    }

    fun normalizeEndpoint(endpoint: String): String {
        val uri = URI(endpoint.trim())
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https")
        require(!uri.host.isNullOrBlank())
        require(uri.query == null && uri.fragment == null)

        val path = uri.path?.takeIf { it.isNotBlank() && it != "/" }?.trimEnd('/') ?: "/2.0"
        return URI(scheme, uri.userInfo, uri.host, uri.port, "$path/", null, null).toString()
    }

    companion object {
        const val DEFAULT_API_ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
        const val LIBREFM_API_ENDPOINT = "https://libre.fm/2.0/"

        const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
        const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
        const val DEFAULT_SCROBBLE_DELAY_SECONDS = 180
    }
}