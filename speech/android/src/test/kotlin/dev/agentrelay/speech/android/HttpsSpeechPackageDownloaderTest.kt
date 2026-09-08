/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelDescriptor
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechModelLicense
import dev.agentrelay.speech.api.SpeechModelPackage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpsSpeechPackageDownloaderTest {
    @Test
    fun exactHttpsResponseStreamsExpectedBytesWithoutClosingDestination() = runTest {
        val payload = "model-package".encodeToByteArray()
        val connection = FakeConnection(
            responseCode = 200,
            contentLengthBytes = payload.size.toLong(),
            input = TrackingInputStream(payload),
        )
        val factory = RecordingConnectionFactory(connection)
        val destination = TrackingOutputStream()
        val descriptor = descriptor(payload)

        downloader(factory).download(descriptor, destination)

        assertContentEquals(payload, destination.toByteArray())
        assertFalse(destination.closed)
        assertTrue(connection.closed)
        assertTrue(connection.input.opened)
        assertTrue(connection.input.closed)
        assertEquals(
            listOf(Request(URI(descriptor.modelPackage.downloadUrl), 2_000, 3_000, null)),
            factory.requests,
        )
    }

    @Test
    fun validatedRangeResponseAppendsOnlyTheDeclaredRemainder() = runTest {
        val payload = "model-package".encodeToByteArray()
        val offsetBytes = 5L
        val connection = FakeConnection(
            responseCode = 206,
            contentLengthBytes = payload.size - offsetBytes,
            contentRange = "bytes $offsetBytes-${payload.lastIndex}/${payload.size}",
            input = TrackingInputStream(payload.copyOfRange(offsetBytes.toInt(), payload.size)),
        )
        val factory = RecordingConnectionFactory(connection)
        val destination = ByteArrayOutputStream().apply {
            write(payload, 0, offsetBytes.toInt())
        }
        val descriptor = descriptor(payload)

        assertEquals(
            SpeechPackageResumeResult.APPENDED,
            downloader(factory).resumeDownload(descriptor, offsetBytes, destination),
        )

        assertContentEquals(payload, destination.toByteArray())
        assertEquals(
            listOf(Request(URI(descriptor.modelPackage.downloadUrl), 2_000, 3_000, offsetBytes)),
            factory.requests,
        )
        assertTrue(connection.closed)
        assertTrue(connection.input.closed)
    }

    @Test
    fun resumeRejectsIgnoredOrMalformedRangesBeforeWriting() = runTest {
        val payload = "range-check".encodeToByteArray()
        val offsetBytes = 3L
        val responses = listOf(
            FakeConnection(
                responseCode = 200,
                contentLengthBytes = payload.size.toLong(),
                input = TrackingInputStream(payload),
            ),
            FakeConnection(responseCode = 416),
            FakeConnection(
                responseCode = 206,
                contentLengthBytes = payload.size - offsetBytes,
                contentRange = "bytes 0-${payload.lastIndex}/${payload.size}",
                input = TrackingInputStream(payload),
            ),
        )

        responses.forEach { connection ->
            val destination = ByteArrayOutputStream()
            assertEquals(
                SpeechPackageResumeResult.RESTART_REQUIRED,
                downloader(RecordingConnectionFactory(connection))
                    .resumeDownload(descriptor(payload), offsetBytes, destination),
            )
            assertEquals(0, destination.size())
            assertFalse(connection.input.opened)
            assertTrue(connection.closed)
        }
    }

    @Test
    fun explicitlyAllowedCrossHostRedirectIsFollowed() = runTest {
        val payload = "redirected".encodeToByteArray()
        val redirect = FakeConnection(
            responseCode = 302,
            redirectLocation = "https://cdn.example.com/releases/model.tar.bz2",
        )
        val response = FakeConnection(
            responseCode = 200,
            contentLengthBytes = payload.size.toLong(),
            input = TrackingInputStream(payload),
        )
        val factory = RecordingConnectionFactory(redirect, response)
        val destination = ByteArrayOutputStream()

        downloader(factory, redirectHosts = setOf("cdn.example.com"))
            .download(descriptor(payload), destination)

        assertContentEquals(payload, destination.toByteArray())
        assertEquals(
            listOf("models.example.com", "cdn.example.com"),
            factory.requests.map { request -> request.uri.host },
        )
        assertTrue(redirect.closed)
        assertTrue(response.closed)
    }

    @Test
    fun redirectsCannotDowngradeEscapeTheAllowlistLoopOrExceedTheLimit() = runTest {
        listOf(
            "http://models.example.com/model.tar.bz2",
            "https://unreviewed.example.com/model.tar.bz2",
            "https://localhost/model.tar.bz2",
            "https://127.0.0.1/model.tar.bz2",
        ).forEach { location ->
            val failure = assertFailsWith<SpeechPackageDeliveryException> {
                downloader(
                    RecordingConnectionFactory(
                        FakeConnection(responseCode = 302, redirectLocation = location),
                    ),
                ).download(descriptor(byteArrayOf(1)), ByteArrayOutputStream())
            }
            assertEquals("MODEL_DOWNLOAD_POLICY", failure.code)
        }

        val loopFactory = RecordingConnectionFactory(
            FakeConnection(responseCode = 302, redirectLocation = "/model.tar.bz2"),
        )
        val loopFailure = assertFailsWith<SpeechPackageDeliveryException> {
            downloader(loopFactory).download(descriptor(byteArrayOf(1)), ByteArrayOutputStream())
        }
        assertEquals("MODEL_DOWNLOAD_POLICY", loopFailure.code)
        assertEquals(1, loopFactory.requests.size)

        val limitFailure = assertFailsWith<SpeechPackageDeliveryException> {
            downloader(
                RecordingConnectionFactory(
                    FakeConnection(responseCode = 307, redirectLocation = "/other.tar.bz2"),
                ),
                maxRedirects = 0,
            ).download(descriptor(byteArrayOf(1)), ByteArrayOutputStream())
        }
        assertEquals("MODEL_DOWNLOAD_POLICY", limitFailure.code)
    }

    @Test
    fun localCatalogSourcesAreRejectedBeforeOpeningAConnection() = runTest {
        listOf(
            "device.local",
            "intranet",
            "service.internal",
            "router.home.arpa",
            "service.test",
        ).forEach { host ->
            val factory = RecordingConnectionFactory()
            val failure = assertFailsWith<SpeechPackageDeliveryException> {
                downloader(factory).download(
                    descriptor(byteArrayOf(1), url = "https://$host/model.tar.bz2"),
                    ByteArrayOutputStream(),
                )
            }

            assertEquals("MODEL_DOWNLOAD_POLICY", failure.code)
            assertTrue(factory.requests.isEmpty())
        }
    }

    @Test
    fun invalidMetadataAndHttpFailuresHaveStableRedactedCodes() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        val mismatchConnection = FakeConnection(
            responseCode = 200,
            contentLengthBytes = payload.size + 1L,
            input = TrackingInputStream(payload),
        )
        val mismatch = assertFailsWith<SpeechPackageDeliveryException> {
            downloader(RecordingConnectionFactory(mismatchConnection))
                .download(descriptor(payload), ByteArrayOutputStream())
        }
        assertEquals("MODEL_DOWNLOAD_INVALID", mismatch.code)
        assertFalse(mismatchConnection.input.opened)

        val failedConnection = FakeConnection(responseCode = 503)
        val failed = assertFailsWith<SpeechPackageDeliveryException> {
            downloader(RecordingConnectionFactory(failedConnection))
                .download(descriptor(payload), ByteArrayOutputStream())
        }
        assertEquals("MODEL_DOWNLOAD_FAILED", failed.code)
        assertFalse(failed.guidance.contains("models.example.com"))
        assertFalse(failedConnection.input.opened)
    }

    @Test
    fun ioFailureIsRedactedAndConnectionIsClosed() = runTest {
        val connection = FakeConnection(
            responseCode = 200,
            contentLengthBytes = 1L,
            input = TrackingInputStream(IOException("private upstream detail")),
        )

        val failure = assertFailsWith<SpeechPackageDeliveryException> {
            downloader(RecordingConnectionFactory(connection))
                .download(descriptor(byteArrayOf(1)), ByteArrayOutputStream())
        }

        assertEquals("MODEL_DOWNLOAD_FAILED", failure.code)
        assertFalse(failure.guidance.contains("private upstream detail"))
        assertTrue(connection.closed)
        assertTrue(connection.input.closed)
    }

    @Test
    fun cancellationAfterBlockingReadPreventsLateWritesAndClosesTheConnection() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val input = TrackingInputStream(entered, release, reads)
        val connection = FakeConnection(
            responseCode = 200,
            contentLengthBytes = 1L,
            input = input,
        )
        val destination = ByteArrayOutputStream()
        val download = launch(Dispatchers.IO) {
            downloader(RecordingConnectionFactory(connection))
                .download(descriptor(byteArrayOf(1)), destination)
        }
        entered.await()

        download.cancel()
        release.complete(Unit)
        download.join()

        assertTrue(download.isCancelled)
        assertEquals(1, reads.get())
        assertEquals(0, destination.size())
        assertTrue(input.closed)
        assertTrue(connection.closed)
    }

    private fun downloader(
        factory: SpeechHttpConnectionFactory,
        redirectHosts: Set<String> = emptySet(),
        maxRedirects: Int = 5,
    ) = HttpsSpeechPackageDownloader(
        redirectHostAllowlist = redirectHosts,
        connectTimeoutMillis = 2_000,
        readTimeoutMillis = 3_000,
        maxRedirects = maxRedirects,
        connectionFactory = factory,
    )

    private fun descriptor(
        payload: ByteArray,
        url: String = "https://models.example.com/model.tar.bz2",
    ) = SpeechModelDescriptor(
        id = SpeechModelId("delivery-test"),
        displayName = "Delivery test",
        version = "1",
        languageTags = setOf("en-US"),
        capabilities = setOf(SpeechModelCapability.TRANSCRIPTION),
        license = SpeechModelLicense(
            name = "Apache License 2.0",
            spdxIdentifier = "Apache-2.0",
            url = "https://licenses.example.com/Apache-2.0",
        ),
        modelPackage = SpeechModelPackage(
            downloadUrl = url,
            sha256 = "0".repeat(64),
            downloadSizeBytes = payload.size.toLong(),
            installedSizeBytes = payload.size.toLong(),
        ),
    )
}

private data class Request(
    val uri: URI,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val offsetBytes: Long?,
)

private class RecordingConnectionFactory(
    vararg connections: SpeechHttpConnection,
) : SpeechHttpConnectionFactory {
    private val remaining = connections.toMutableList()
    val requests = mutableListOf<Request>()

    override fun open(
        uri: URI,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        offsetBytes: Long?,
    ): SpeechHttpConnection {
        requests += Request(uri, connectTimeoutMillis, readTimeoutMillis, offsetBytes)
        return remaining.removeAt(0)
    }
}

private class FakeConnection(
    override val responseCode: Int,
    override val contentLengthBytes: Long? = null,
    override val redirectLocation: String? = null,
    override val contentRange: String? = null,
    val input: TrackingInputStream = TrackingInputStream(byteArrayOf()),
) : SpeechHttpConnection {
    var closed = false

    override fun openInputStream(): InputStream {
        input.opened = true
        return input
    }

    override fun close() {
        closed = true
    }
}

private class TrackingOutputStream : ByteArrayOutputStream() {
    var closed = false

    override fun close() {
        closed = true
        super.close()
    }
}

private class TrackingInputStream private constructor(
    private val delegate: InputStream?,
    private val entered: CompletableDeferred<Unit>?,
    private val release: CompletableDeferred<Unit>?,
    private val reads: AtomicInteger?,
) : InputStream() {
    var opened = false
    var closed = false

    constructor(payload: ByteArray) : this(ByteArrayInputStream(payload), null, null, null)

    constructor(failure: IOException) : this(
        object : InputStream() {
            override fun read(): Int = throw failure
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
        },
        null,
        null,
        null,
    )

    constructor(
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
        reads: AtomicInteger,
    ) : this(null, entered, release, reads)

    override fun read(): Int = error("Bulk reads are required")

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val source = delegate
        if (source != null) {
            return source.read(buffer, offset, length)
        }
        val call = checkNotNull(reads).incrementAndGet()
        if (call > 1) {
            return -1
        }
        checkNotNull(entered).complete(Unit)
        runBlocking(NonCancellable) {
            checkNotNull(release).await()
        }
        buffer[offset] = 1
        return 1
    }

    override fun close() {
        closed = true
        delegate?.close()
    }
}
