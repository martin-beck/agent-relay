/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechModelDescriptor
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.URI
import javax.net.ssl.HttpsURLConnection
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
) : ResumableSpeechPackageDownloader {
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
    ) = download(descriptor, destination, offsetBytes = null)

    override suspend fun resumeDownload(
        descriptor: SpeechModelDescriptor,
        offsetBytes: Long,
        destination: OutputStream,
    ): SpeechPackageResumeResult {
        require(offsetBytes in 1 until descriptor.modelPackage.downloadSizeBytes) {
            "Speech package resume offset is invalid"
        }
        return try {
            download(descriptor, destination, offsetBytes)
            SpeechPackageResumeResult.APPENDED
        } catch (_: SpeechPackageRestartRequiredException) {
            SpeechPackageResumeResult.RESTART_REQUIRED
        }
    }

    private suspend fun download(
        descriptor: SpeechModelDescriptor,
        destination: OutputStream,
        offsetBytes: Long?,
    ) {
        try {
            downloadVerifiedSource(descriptor, destination, offsetBytes)
        } catch (failure: IOException) {
            when (failure) {
                is SpeechPackageRestartRequiredException,
                is SpeechPackageDeliveryException,
                -> throw failure
                else -> throw downloadFailure(failure)
            }
        }
    }

    private suspend fun downloadVerifiedSource(
        descriptor: SpeechModelDescriptor,
        destination: OutputStream,
        offsetBytes: Long?,
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
                offsetBytes,
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
                validateResponse(
                    connection = connection,
                    responseCode = responseCode,
                    expectedBytes = descriptor.modelPackage.downloadSizeBytes,
                    offsetBytes = offsetBytes,
                )
                connection.openInputStream().buffered().use { input ->
                    copyCancellable(input, destination, job)
                }
                return
            }
        }
        rejectDownloadPolicy()
    }

    private fun validateResponse(
        connection: SpeechHttpConnection,
        responseCode: Int,
        expectedBytes: Long,
        offsetBytes: Long?,
    ) {
        if (offsetBytes == null) {
            validateFullResponse(responseCode, connection.contentLengthBytes, expectedBytes)
        } else {
            validateRangeResponse(connection, responseCode, offsetBytes, expectedBytes)
        }
    }

    private fun validateFullResponse(
        responseCode: Int,
        contentLength: Long?,
        expectedBytes: Long,
    ) {
        if (responseCode != HttpsURLConnection.HTTP_OK) {
            rejectDownloadResponse()
        }
        if (contentLength != null && contentLength != expectedBytes) {
            rejectDownloadMetadata()
        }
    }

    private fun validateRangeResponse(
        connection: SpeechHttpConnection,
        responseCode: Int,
        offsetBytes: Long,
        expectedBytes: Long,
    ) {
        if (responseCode == HttpsURLConnection.HTTP_OK ||
            responseCode == HTTP_RANGE_NOT_SATISFIABLE
        ) {
            restartDownload()
        }
        if (responseCode != HTTP_PARTIAL_CONTENT) {
            rejectDownloadResponse()
        }
        val remainingBytes = expectedBytes - offsetBytes
        if (connection.contentLengthBytes != null &&
            connection.contentLengthBytes != remainingBytes
        ) {
            restartDownload()
        }
        if (!matchesContentRange(connection.contentRange, offsetBytes, expectedBytes)) {
            restartDownload()
        }
    }

    private fun restartDownload(): Nothing = throw SpeechPackageRestartRequiredException()

    private fun rejectDownloadResponse(): Nothing = throw downloadFailure()

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

    private fun matchesContentRange(
        value: String?,
        offsetBytes: Long,
        expectedBytes: Long,
    ): Boolean {
        if (value == null || value.length > MAX_CONTENT_RANGE_CHARACTERS) {
            return false
        }
        val match = CONTENT_RANGE.matchEntire(value) ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return start == offsetBytes && end == expectedBytes - 1L && total == expectedBytes
    }

    private fun rejectDownloadMetadata(): Nothing {
        throw SpeechPackageDeliveryException(
            code = "MODEL_DOWNLOAD_INVALID",
            guidance = "The model host returned unexpected package metadata. Retry later.",
        )
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
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val MAX_CONTENT_RANGE_CHARACTERS = 128
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val CONTENT_RANGE = """bytes ([0-9]+)-([0-9]+)/([0-9]+)""".toRegex()
    }
}

internal fun interface SpeechHttpConnectionFactory {
    fun open(
        uri: URI,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        offsetBytes: Long?,
    ): SpeechHttpConnection
}

internal interface SpeechHttpConnection : Closeable {
    val responseCode: Int
    val contentLengthBytes: Long?
    val contentRange: String?
    val redirectLocation: String?

    fun openInputStream(): InputStream
}

private object JavaNetSpeechHttpConnectionFactory : SpeechHttpConnectionFactory {
    override fun open(
        uri: URI,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        offsetBytes: Long?,
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
        if (offsetBytes != null) {
            connection.setRequestProperty("Range", "bytes=$offsetBytes-")
        }
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

    override val contentRange: String?
        get() = connection.getHeaderField("Content-Range")

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

internal class SpeechPackageRestartRequiredException : IOException()

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
