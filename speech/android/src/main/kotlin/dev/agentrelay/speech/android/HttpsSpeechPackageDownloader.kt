package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechModelDescriptor
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Streams a pinned catalog asset over HTTPS without following an unreviewed redirect.
 *
 * The original catalog host is always admitted. Cross-host redirects require an exact host in
 * [redirectHostAllowlist], which lets a catalog explicitly admit release-asset CDNs without
 * permitting arbitrary redirect targets.
 */
class HttpsSpeechPackageDownloader internal constructor(
    redirectHostAllowlist: Set<String>,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val maxRedirects: Int,
    private val connectionFactory: SpeechHttpConnectionFactory,
) : SpeechPackageDownloader {
    private val allowedRedirectHosts = redirectHostAllowlist.map { host ->
        requireNotNull(canonicalPublicHost(host)) {
            "Speech redirect hosts must be public DNS names"
        }
    }.toSet()

    constructor(
        redirectHostAllowlist: Set<String> = emptySet(),
        connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
        maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    ) : this(
        redirectHostAllowlist = redirectHostAllowlist,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        maxRedirects = maxRedirects,
        connectionFactory = JavaNetSpeechHttpConnectionFactory,
    )

    init {
        require(connectTimeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            "Speech download connect timeout is invalid"
        }
        require(readTimeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            "Speech download read timeout is invalid"
        }
        require(maxRedirects in 0..MAX_REDIRECTS) {
            "Speech download redirect limit is invalid"
        }
    }

    override suspend fun download(
        descriptor: SpeechModelDescriptor,
        destination: OutputStream,
    ) {
        try {
            downloadVerifiedSource(descriptor, destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SpeechPackageDeliveryException) {
            throw failure
        } catch (failure: IOException) {
            throw downloadFailure(failure)
        }
    }

    private suspend fun downloadVerifiedSource(
        descriptor: SpeechModelDescriptor,
        destination: OutputStream,
    ) {
        val job = checkNotNull(currentCoroutineContext()[Job]) {
            "Speech package download requires a coroutine job"
        }
        var source = requireSafeHttpsUri(descriptor.modelPackage.downloadUrl)
        val originalHost = checkNotNull(canonicalPublicHost(checkNotNull(source.host)))
        val admittedHosts = allowedRedirectHosts + originalHost
        val visited = mutableSetOf<String>()

        repeat(maxRedirects + 1) { redirectCount ->
            job.ensureActive()
            if (!visited.add(source.toASCIIString())) {
                rejectDownloadPolicy()
            }
            connectionFactory.open(
                source,
                connectTimeoutMillis,
                readTimeoutMillis,
            ).use { connection ->
                val responseCode = connection.responseCode
                if (responseCode in REDIRECT_CODES) {
                    if (redirectCount >= maxRedirects) {
                        rejectDownloadPolicy()
                    }
                    val location = connection.redirectLocation ?: rejectDownloadPolicy()
                    source = requireSafeRedirect(source, location, admittedHosts)
                    return@use
                }
                if (responseCode != HttpsURLConnection.HTTP_OK) {
                    throw downloadFailure()
                }
                val expectedBytes = descriptor.modelPackage.downloadSizeBytes
                val contentLength = connection.contentLengthBytes
                if (contentLength != null && contentLength != expectedBytes) {
                    throw SpeechPackageDeliveryException(
                        code = "MODEL_DOWNLOAD_INVALID",
                        guidance = "The model host returned unexpected package metadata. Retry later.",
                    )
                }
                connection.openInputStream().buffered().use { input ->
                    copyCancellable(input, destination, job)
                }
                return
            }
        }
        rejectDownloadPolicy()
    }

    private fun requireSafeRedirect(
        base: URI,
        location: String,
        admittedHosts: Set<String>,
    ): URI {
        val resolved = try {
            base.resolve(URI(location))
        } catch (_: IllegalArgumentException) {
            rejectDownloadPolicy()
        }
        val safe = requireSafeHttpsUri(resolved.toASCIIString())
        val host = canonicalPublicHost(checkNotNull(safe.host)) ?: rejectDownloadPolicy()
        if (host !in admittedHosts) {
            rejectDownloadPolicy()
        }
        return safe
    }

    private fun requireSafeHttpsUri(value: String): URI {
        val uri = try {
            URI(value)
        } catch (_: IllegalArgumentException) {
            rejectDownloadPolicy()
        }
        val host = uri.host?.let(::canonicalPublicHost)
        if (uri.isOpaque || !uri.scheme.equals("https", ignoreCase = true)) {
            rejectDownloadPolicy()
        }
        if (host == null || uri.userInfo != null || uri.fragment != null) {
            rejectDownloadPolicy()
        }
        if (uri.port != -1 && uri.port != HTTPS_PORT) {
            rejectDownloadPolicy()
        }
        return uri
    }

    private suspend fun copyCancellable(
        input: InputStream,
        destination: OutputStream,
        job: Job,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            while (true) {
                job.ensureActive()
                val read = input.read(buffer)
                if (read < 0) {
                    return
                }
                job.ensureActive()
                destination.write(buffer, 0, read)
            }
        } finally {
            buffer.fill(0)
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 60_000
        const val DEFAULT_MAX_REDIRECTS = 5
        const val MIN_TIMEOUT_MILLIS = 1_000
        const val MAX_TIMEOUT_MILLIS = 120_000
        const val MAX_REDIRECTS = 10
        const val HTTPS_PORT = 443
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal fun interface SpeechHttpConnectionFactory {
    fun open(
        uri: URI,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): SpeechHttpConnection
}

internal interface SpeechHttpConnection : Closeable {
    val responseCode: Int
    val contentLengthBytes: Long?
    val redirectLocation: String?

    fun openInputStream(): InputStream
}

private object JavaNetSpeechHttpConnectionFactory : SpeechHttpConnectionFactory {
    override fun open(
        uri: URI,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): SpeechHttpConnection {
        val connection = uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw downloadFailure()
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.useCaches = false
        connection.doInput = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("Accept-Encoding", "identity")
        return JavaNetSpeechHttpConnection(connection)
    }
}

private class JavaNetSpeechHttpConnection(
    private val connection: HttpsURLConnection,
) : SpeechHttpConnection {
    override val responseCode: Int
        get() = connection.responseCode

    override val contentLengthBytes: Long?
        get() = connection.contentLengthLong.takeIf { it >= 0L }

    override val redirectLocation: String?
        get() = connection.getHeaderField("Location")

    override fun openInputStream(): InputStream = connection.inputStream

    override fun close() {
        connection.disconnect()
    }
}

internal class SpeechPackageDeliveryException(
    val code: String,
    val guidance: String,
    cause: Throwable? = null,
) : IOException(guidance, cause)

private fun canonicalPublicHost(value: String): String? {
    if (':' in value) {
        return null
    }
    val host = runCatching {
        IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase()
    }.getOrNull() ?: return null
    val labels = host.split('.')
    if (host.isBlank() || host.length > MAX_HOST_CHARACTERS) {
        return null
    }
    if (labels.size < MIN_PUBLIC_HOST_LABELS ||
        labels.any { label -> !PUBLIC_HOST_LABEL.matches(label) }
    ) {
        return null
    }
    if (labels.last().none { character -> character in 'a'..'z' }) {
        return null
    }
    if (PRIVATE_HOST_SUFFIXES.any { suffix -> host == suffix || host.endsWith(".$suffix") }) {
        return null
    }
    return host
}

private fun rejectDownloadPolicy(): Nothing {
    throw SpeechPackageDeliveryException(
        code = "MODEL_DOWNLOAD_POLICY",
        guidance = "The model download source was rejected. Update the app catalog.",
    )
}

private fun downloadFailure(cause: Throwable? = null) = SpeechPackageDeliveryException(
    code = "MODEL_DOWNLOAD_FAILED",
    guidance = "The speech model could not be downloaded. Check the network and retry.",
    cause = cause,
)

private val PUBLIC_HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val PRIVATE_HOST_SUFFIXES = setOf(
    "home.arpa",
    "internal",
    "invalid",
    "lan",
    "local",
    "localhost",
    "onion",
    "test",
)
private const val MAX_HOST_CHARACTERS = 253
private const val MIN_PUBLIC_HOST_LABELS = 2
