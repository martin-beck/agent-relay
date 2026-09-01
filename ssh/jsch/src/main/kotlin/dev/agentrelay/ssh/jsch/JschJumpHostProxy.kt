package dev.agentrelay.ssh.jsch

import com.jcraft.jsch.Channel
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

internal class JschJumpHostProxy(
    private val jumpHostSession: Session,
) : Proxy {
    private var channel: Channel? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun connect(
        socketFactory: SocketFactory?,
        host: String,
        port: Int,
        timeout: Int,
    ) {
        check(channel == null) { "SSH jump-host proxy is already connected" }
        check(jumpHostSession.isConnected) { "SSH jump host disconnected before forwarding" }
        val opened = jumpHostSession.getStreamForwarder(host, port)
        try {
            val openedInput = opened.inputStream
            val openedOutput = opened.outputStream
            opened.connect(timeout)
            channel = opened
            input = openedInput
            output = openedOutput
        } catch (failure: Throwable) {
            opened.disconnect()
            throw failure
        }
    }

    override fun getInputStream(): InputStream =
        checkNotNull(input) { "SSH jump-host proxy is not connected" }

    override fun getOutputStream(): OutputStream =
        checkNotNull(output) { "SSH jump-host proxy is not connected" }

    override fun getSocket(): Socket? = null

    override fun close() {
        output = null
        input = null
        channel?.disconnect()
        channel = null
    }
}
