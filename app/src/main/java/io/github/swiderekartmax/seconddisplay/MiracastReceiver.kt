package io.github.swiderekartmax.seconddisplay

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Miracast over Infrastructure (MS-MICE) & Wi-Fi Display (WFD) receiver for Windows 10/11 and
 * Linux (GNOME Network Displays).
 *
 * Protocol flow:
 *  1. Native `mdnsd` advertises `<ServiceName>._display._tcp.local` on TCP port [MICE_PORT] (7250)
 *     with `container_id` and `p2pMAC` TXT records.
 *  2. Sender connects to TCP port 7250 and sends a binary MS-MICE `SOURCE_READY` (0x01) packet
 *     containing the sender's friendly name and RTSP server port (default 7236).
 *  3. Receiver connects as a TCP client to `<sender_ip>:<rtsp_port>`, completes the Wi-Fi Display
 *     RTSP M1–M7 handshake (`OPTIONS`, `GET_PARAMETER`, `SET_PARAMETER`, `SETUP`, `PLAY`), and
 *     receives the MPEG-2 Transport Stream over UDP RTP.
 *  4. [MpegTsDemuxer] extracts Annex-B H.264/HEVC access units and 48 kHz LPCM/AAC-LC audio frames
 *     and forwards them to [Listener] (backed by [WormholeServer]).
 */
class MiracastReceiver(
    private val listener: Listener,
    private val displayInfoProvider: () -> DisplayInfo
) {
    interface Listener {
        fun onClientConnected(name: String)
        fun onClientDisconnected()
        fun onMirrorRunning(running: Boolean)
        fun onAudioRunning(running: Boolean, ct: Int)
        fun onVideoFrame(data: ByteArray, ingressNanos: Long, ptsNs: Long, hevc: Boolean)
        fun onAudioFrame(data: ByteArray, ptsNs: Long, ct: Int)
        fun onStreamError()
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "Wormhole-Miracast").apply { isDaemon = true }
    }

    @Volatile private var miceServerSocket: ServerSocket? = null
    @Volatile private var activeSession: RtspSession? = null
    private val sessionLock = Any()

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(MICE_PORT))
            miceServerSocket = server
            executor.execute { acceptMiceLoop(server) }
            Log.i(TAG, "MS-MICE receiver listening on TCP port $MICE_PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind MS-MICE TCP port $MICE_PORT: ${e.message}", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { miceServerSocket?.close() }
        miceServerSocket = null
        disconnectActiveSession(notifyStop = true)
    }

    fun disconnectClient() {
        disconnectActiveSession(notifyStop = true)
    }

    fun isSessionActive(): Boolean = synchronized(sessionLock) { activeSession != null }

    private fun disconnectActiveSession(notifyStop: Boolean) {
        val session = synchronized(sessionLock) {
            val current = activeSession
            activeSession = null
            current
        }
        session?.close(notifyStop = notifyStop)
    }

    private fun acceptMiceLoop(server: ServerSocket) {
        while (running.get() && !server.isClosed) {
            try {
                val client = server.accept()
                executor.execute { handleMiceConnection(client) }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "MS-MICE accept error: ${e.message}")
                }
            }
        }
    }

    private fun handleMiceConnection(socket: Socket) {
        val sourceIp = socket.inetAddress?.hostAddress ?: run {
            runCatching { socket.close() }
            return
        }
        Log.i(TAG, "MS-MICE control connection from $sourceIp")
        try {
            socket.soTimeout = 0
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (running.get() && !socket.isClosed) {
                val packet = readMiceMessage(input) ?: break
                Log.i(
                    TAG,
                    "MS-MICE packet from $sourceIp: cmd=${packet.command}, " +
                        "name='${packet.friendlyName}', rtspPort=${packet.rtspPort}"
                )
                when (packet.command) {
                    MICE_CMD_SOURCE_READY -> {
                        val name = packet.friendlyName.ifBlank { sourceIp }
                        val rtspPort = if (packet.rtspPort in 1..65535) packet.rtspPort else DEFAULT_WFD_RTSP_PORT
                        startRtspSession(sourceIp, rtspPort, name)
                    }
                    MICE_CMD_STOP_PROJECTION -> {
                        Log.i(TAG, "MS-MICE STOP_PROJECTION from $sourceIp")
                        disconnectActiveSession(notifyStop = true)
                    }
                }
            }
        } catch (_: EOFException) {
            // Normal close of MICE control socket after handshake or projection stop
        } catch (e: Exception) {
            if (running.get()) {
                Log.w(TAG, "MS-MICE connection from $sourceIp closed: ${e.message}")
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun startRtspSession(sourceIp: String, rtspPort: Int, friendlyName: String) {
        disconnectActiveSession(notifyStop = true)
        val session = RtspSession(sourceIp, rtspPort, friendlyName)
        synchronized(sessionLock) {
            activeSession = session
        }
        executor.execute { session.run() }
    }

    private inner class RtspSession(
        val sourceIp: String,
        val rtspPort: Int,
        val friendlyName: String
    ) {
        private val closed = AtomicBoolean(false)
        private val mirrorStarted = AtomicBoolean(false)
        private val audioStarted = AtomicBoolean(false)
        private val cseqCounter = AtomicInteger(1)
        private val pendingClientRequests = ConcurrentHashMap<Int, String>()

        @Volatile private var rtspSocket: Socket? = null
        @Volatile private var rtspOut: OutputStream? = null
        @Volatile private var rtpSocket: DatagramSocket? = null
        @Volatile private var sessionId: String? = null
        @Volatile private var presentationUrl: String = "rtsp://$sourceIp:$rtspPort/wfd1.0/streamid=0"
        @Volatile private var negotiatedAudioCt: Int = NativeBridge.CT_WFD_LPCM_48K

        fun run() {
            try {
                val udp = bindRtpSocket()
                rtpSocket = udp
                val localRtpPort = udp.localPort
                Log.i(TAG, "Starting WFD RTSP session to $sourceIp:$rtspPort (local RTP UDP port $localRtpPort)")

                val tcp = Socket()
                tcp.tcpNoDelay = true
                tcp.connect(InetSocketAddress(sourceIp, rtspPort), RTSP_CONNECT_TIMEOUT_MS)
                tcp.soTimeout = RTSP_READ_TIMEOUT_MS
                rtspSocket = tcp
                rtspOut = tcp.getOutputStream()

                listener.onClientConnected(friendlyName)
                executor.execute { rtpReceiveLoop(udp) }

                val input = BufferedInputStream(tcp.getInputStream())
                while (!closed.get() && running.get() && !tcp.isClosed) {
                    val msg = readRtspMessage(input) ?: break
                    handleRtspMessage(msg, localRtpPort)
                }
            } catch (e: Exception) {
                if (!closed.get() && running.get()) {
                    Log.w(TAG, "WFD RTSP session error ($sourceIp:$rtspPort): ${e.message}")
                }
            } finally {
                close(notifyStop = true)
                synchronized(sessionLock) {
                    if (activeSession === this) {
                        activeSession = null
                    }
                }
            }
        }

        private fun rtpReceiveLoop(udp: DatagramSocket) {
            val demuxer = MpegTsDemuxer(
                onVideoFrame = { data, ptsNs, hevc ->
                    if (!closed.get()) {
                        if (mirrorStarted.compareAndSet(false, true)) {
                            listener.onMirrorRunning(true)
                        }
                        val ingress = System.nanoTime()
                        listener.onVideoFrame(data, ingress, ptsNs, hevc)
                    }
                },
                onAudioFrame = { data, ptsNs, ct ->
                    if (!closed.get()) {
                        if (audioStarted.compareAndSet(false, true)) {
                            listener.onAudioRunning(true, ct)
                        }
                        listener.onAudioFrame(data, ptsNs, ct)
                    }
                }
            )

            val buf = ByteArray(65536)
            val packet = DatagramPacket(buf, buf.size)
            udp.soTimeout = RTP_POLL_TIMEOUT_MS

            while (!closed.get() && running.get() && !udp.isClosed) {
                try {
                    udp.receive(packet)
                    if (packet.length > 0) {
                        demuxer.onUdpPacket(packet.data, packet.length)
                    }
                } catch (_: SocketTimeoutException) {
                    // Flush any unbounded PES frame after a brief inter-frame pause
                    demuxer.flush()
                } catch (e: Exception) {
                    if (!closed.get() && running.get()) {
                        Log.w(TAG, "WFD RTP receive error: ${e.message}")
                    }
                    break
                }
            }
            demuxer.flush()
        }

        private fun handleRtspMessage(msg: RtspMessage, localRtpPort: Int) {
            if (msg.isResponse) {
                val cseq = msg.headers["cseq"]?.toIntOrNull() ?: return
                val reqMethod = pendingClientRequests.remove(cseq) ?: return
                msg.headers["session"]?.let { rawSession ->
                    val clean = rawSession.substringBefore(';').trim()
                    if (clean.isNotEmpty()) {
                        sessionId = clean
                    }
                }
                Log.i(TAG, "RTSP response for $reqMethod (CSeq=$cseq): ${msg.startLine} (session=$sessionId)")
                when (reqMethod) {
                    "SETUP" -> {
                        if (msg.statusCode == 200) {
                            sendClientRequest(
                                method = "PLAY",
                                uri = presentationUrl,
                                extraHeaders = buildMap {
                                    sessionId?.let { put("Session", it) }
                                }
                            )
                        }
                    }
                    "PLAY" -> {
                        if (msg.statusCode == 200) {
                            if (mirrorStarted.compareAndSet(false, true)) {
                                listener.onMirrorRunning(true)
                            }
                            if (audioStarted.compareAndSet(false, true)) {
                                listener.onAudioRunning(true, negotiatedAudioCt)
                            }
                            // Request an immediate IDR keyframe (M13) so decoding starts without delay
                            sendIdrRequest()
                        }
                    }
                }
                return
            }

            // Incoming Request from the Source (M1, M3, M4, M5, M16)
            val method = msg.method
            val cseq = msg.headers["cseq"] ?: "0"
            Log.i(TAG, "RTSP request from source: $method (CSeq=$cseq)")

            when (method) {
                "OPTIONS" -> {
                    // M1: Reply with supported WFD methods, then immediately send M2 OPTIONS
                    sendResponse(
                        cseq = cseq,
                        extraHeaders = mapOf("Public" to "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER")
                    )
                    sendClientRequest(
                        method = "OPTIONS",
                        uri = "*",
                        extraHeaders = mapOf("Require" to "org.wfa.wfd1.0")
                    )
                }
                "GET_PARAMETER" -> {
                    // M3 (capability query) or M16 (keep-alive)
                    if (msg.body.isBlank()) {
                        sendResponse(cseq = cseq)
                    } else {
                        val responseBody = buildM3ParametersResponse(
                            requestedBody = msg.body,
                            display = displayInfoProvider(),
                            localRtpPort = localRtpPort
                        )
                        sendResponse(
                            cseq = cseq,
                            body = responseBody
                        )
                    }
                }
                "SET_PARAMETER" -> {
                    // M4 (parameter negotiation) or M5 (wfd_trigger_method)
                    val trigger = parseM4AndM5Parameters(msg.body)
                    sendResponse(cseq = cseq)
                    when (trigger) {
                        "SETUP" -> {
                            sendClientRequest(
                                method = "SETUP",
                                uri = presentationUrl,
                                extraHeaders = mapOf(
                                    "Transport" to "RTP/AVP/UDP;unicast;client_port=$localRtpPort-${localRtpPort + 1}"
                                )
                            )
                        }
                        "PLAY" -> {
                            sendClientRequest(
                                method = "PLAY",
                                uri = presentationUrl,
                                extraHeaders = buildMap {
                                    sessionId?.let { put("Session", it) }
                                }
                            )
                        }
                        "PAUSE" -> {
                            sendClientRequest(
                                method = "PAUSE",
                                uri = presentationUrl,
                                extraHeaders = buildMap {
                                    sessionId?.let { put("Session", it) }
                                }
                            )
                        }
                        "TEARDOWN" -> {
                            // close() sends the TEARDOWN request when a session is established
                            close(notifyStop = true)
                        }
                    }
                }
                else -> {
                    sendResponse(cseq = cseq)
                }
            }
        }

        private fun parseM4AndM5Parameters(body: String): String? {
            var triggerMethod: String? = null
            for (rawLine in body.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "wfd_presentation_url" -> {
                        val firstUrl = value.split(' ').firstOrNull { it.startsWith("rtsp://", ignoreCase = true) }
                        if (!firstUrl.isNullOrBlank()) {
                            // Replace "localhost" if sender wrote rtsp://localhost/...
                            presentationUrl = firstUrl.replace(
                                Regex("^rtsp://localhost(:\\d+)?", RegexOption.IGNORE_CASE),
                                "rtsp://$sourceIp:$rtspPort"
                            )
                        }
                    }
                    "wfd_audio_codecs" -> {
                        negotiatedAudioCt = if (value.startsWith("AAC", ignoreCase = true)) {
                            NativeBridge.CT_WFD_AAC_48K
                        } else {
                            NativeBridge.CT_WFD_LPCM_48K
                        }
                    }
                    "wfd_trigger_method" -> {
                        triggerMethod = value.uppercase()
                    }
                }
            }
            return triggerMethod
        }

        private fun sendIdrRequest() {
            val sid = sessionId ?: return
            sendClientRequest(
                method = "SET_PARAMETER",
                uri = "rtsp://localhost/wfd1.0",
                extraHeaders = mapOf("Session" to sid),
                body = "wfd_idr_request\r\n"
            )
        }

        @Synchronized
        private fun sendResponse(
            cseq: String,
            status: String = "200 OK",
            extraHeaders: Map<String, String> = emptyMap(),
            body: String? = null
        ) {
            val out = rtspOut ?: return
            val sb = StringBuilder()
            sb.append("RTSP/1.0 ").append(status).append("\r\n")
            sb.append("CSeq: ").append(cseq).append("\r\n")
            sessionId?.let { sb.append("Session: ").append(it).append("\r\n") }
            for ((k, v) in extraHeaders) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                sb.append("Content-Type: text/parameters\r\n")
                sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            }
            sb.append("\r\n")
            runCatching {
                out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                    out.write(bodyBytes)
                }
                out.flush()
            }
        }

        @Synchronized
        private fun sendClientRequest(
            method: String,
            uri: String,
            extraHeaders: Map<String, String> = emptyMap(),
            body: String? = null
        ) {
            val out = rtspOut ?: return
            val cseq = cseqCounter.getAndIncrement()
            pendingClientRequests[cseq] = method
            val sb = StringBuilder()
            sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
            sb.append("CSeq: ").append(cseq).append("\r\n")
            sb.append("User-Agent: SecondDisplay/1.0\r\n")
            for ((k, v) in extraHeaders) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                sb.append("Content-Type: text/parameters\r\n")
                sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            }
            sb.append("\r\n")
            runCatching {
                out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                    out.write(bodyBytes)
                }
                out.flush()
            }
        }

        fun close(notifyStop: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            sessionId?.let { sid ->
                runCatching {
                    sendClientRequest(
                        method = "TEARDOWN",
                        uri = presentationUrl,
                        extraHeaders = mapOf("Session" to sid)
                    )
                }
            }
            runCatching { rtspSocket?.close() }
            runCatching { rtpSocket?.close() }
            rtspSocket = null
            rtpSocket = null
            if (notifyStop) {
                if (audioStarted.compareAndSet(true, false)) {
                    listener.onAudioRunning(false, negotiatedAudioCt)
                }
                if (mirrorStarted.compareAndSet(true, false)) {
                    listener.onMirrorRunning(false)
                }
                listener.onClientDisconnected()
            }
        }
    }

    private fun bindRtpSocket(): DatagramSocket {
        for (candidate in intArrayOf(PREFERRED_RTP_PORT, 19002, 19004, 0)) {
            try {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.receiveBufferSize = 2 * 1024 * 1024
                sock.bind(InetSocketAddress(candidate))
                return sock
            } catch (_: Exception) {
                // try next port
            }
        }
        return DatagramSocket()
    }

    companion object {
        private const val TAG = "WormholeMiracast"
        const val MICE_PORT = 7250
        const val DEFAULT_WFD_RTSP_PORT = 7236
        const val PREFERRED_RTP_PORT = 19000

        private const val RTSP_CONNECT_TIMEOUT_MS = 5000
        private const val RTSP_READ_TIMEOUT_MS = 60000
        private const val RTP_POLL_TIMEOUT_MS = 12

        const val MICE_VERSION: Int = 0x01
        const val MICE_CMD_SOURCE_READY: Int = 0x01
        const val MICE_CMD_STOP_PROJECTION: Int = 0x02

        const val TLV_FRIENDLY_NAME: Int = 0x00
        const val TLV_RTSP_PORT: Int = 0x02
        const val TLV_SOURCE_ID: Int = 0x03

        data class MiceMessage(
            val version: Int,
            val command: Int,
            val friendlyName: String,
            val rtspPort: Int,
            val sourceId: ByteArray?
        )

        data class RtspMessage(
            val startLine: String,
            val isResponse: Boolean,
            val method: String,
            val statusCode: Int,
            val headers: Map<String, String>,
            val body: String
        )

        /**
         * Reads a single MS-MICE binary message from [input], or returns null on clean EOF.
         */
        fun readMiceMessage(input: DataInputStream): MiceMessage? {
            val hi = input.read()
            if (hi < 0) return null
            val lo = input.read()
            if (lo < 0) throw EOFException("Truncated MS-MICE length")
            val totalLength = ((hi and 0xFF) shl 8) or (lo and 0xFF)
            if (totalLength < 4 || totalLength > 4096) {
                throw IllegalArgumentException("Invalid MS-MICE packet length: $totalLength")
            }
            val payload = ByteArray(totalLength - 2)
            input.readFully(payload)
            return parseMicePayload(totalLength, payload)
        }

        /**
         * Parses an MS-MICE message from its complete byte array (including the 2-byte length prefix).
         */
        fun parseMicePacket(bytes: ByteArray): MiceMessage {
            require(bytes.size >= 4) { "MS-MICE packet too short (${bytes.size} bytes)" }
            val totalLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            require(totalLength in 4..bytes.size) { "Invalid MS-MICE length header: $totalLength" }
            return parseMicePayload(totalLength, bytes.copyOfRange(2, totalLength))
        }

        private fun parseMicePayload(totalLength: Int, rest: ByteArray): MiceMessage {
            val version = rest[0].toInt() and 0xFF
            val command = rest[1].toInt() and 0xFF
            var friendlyName = ""
            var rtspPort = DEFAULT_WFD_RTSP_PORT
            var sourceId: ByteArray? = null

            var pos = 2
            val limit = totalLength - 2
            while (pos + 3 <= limit) {
                val tlvType = rest[pos].toInt() and 0xFF
                val tlvLen = ((rest[pos + 1].toInt() and 0xFF) shl 8) or (rest[pos + 2].toInt() and 0xFF)
                pos += 3
                if (pos + tlvLen > limit) break
                when (tlvType) {
                    TLV_FRIENDLY_NAME -> {
                        val raw = String(rest, pos, tlvLen, StandardCharsets.UTF_16LE)
                        friendlyName = raw.trim { it == '\uFEFF' || it == '\u0000' || it.isWhitespace() }
                    }
                    TLV_RTSP_PORT -> {
                        if (tlvLen == 2) {
                            rtspPort = ((rest[pos].toInt() and 0xFF) shl 8) or (rest[pos + 1].toInt() and 0xFF)
                        }
                    }
                    TLV_SOURCE_ID -> {
                        sourceId = rest.copyOfRange(pos, pos + tlvLen)
                    }
                }
                pos += tlvLen
            }
            return MiceMessage(version, command, friendlyName, rtspPort, sourceId)
        }

        /**
         * Reads a single RTSP request or response from [input], or returns null on EOF.
         */
        fun readRtspMessage(input: InputStream): RtspMessage? {
            val startLine = readCrLfLine(input) ?: return null
            // Skip any leading blank lines
            var firstLine = startLine
            while (firstLine.isEmpty()) {
                firstLine = readCrLfLine(input) ?: return null
            }

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readCrLfLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, StandardCharsets.UTF_8)
            } else {
                ""
            }

            val isResponse = firstLine.startsWith("RTSP/", ignoreCase = true)
            val parts = firstLine.split(' ')
            val method = if (!isResponse && parts.isNotEmpty()) parts[0].uppercase() else ""
            val statusCode = if (isResponse && parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0

            return RtspMessage(firstLine, isResponse, method, statusCode, headers, body)
        }

        private fun readCrLfLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream(128)
            while (true) {
                val b = input.read()
                if (b < 0) {
                    return if (buf.size() == 0) null else buf.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
                }
                if (b == '\n'.code) {
                    return buf.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
                }
                buf.write(b)
            }
        }

        /**
         * Builds the M3 `GET_PARAMETER` response body for the requested Wi-Fi Display parameters.
         */
        fun buildM3ParametersResponse(
            requestedBody: String,
            display: DisplayInfo,
            localRtpPort: Int
        ): String {
            val requested = requestedBody
                .lineSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()

            val videoFormats = buildWfdVideoFormats(display)
            // LPCM mode bit 1 only (48 kHz stereo): the demuxer and AudioRenderer assume 48 kHz,
            // so 44.1 kHz LPCM (bit 0) must not be offered.
            val audioCodecs = "LPCM 00000002 00, AAC 00000001 00"
            val rtpPorts = "RTP/AVP/UDP;unicast $localRtpPort 0 mode=play"

            val knownReplies = linkedMapOf(
                "wfd_video_formats" to videoFormats,
                "wfd_audio_codecs" to audioCodecs,
                "wfd_client_rtp_ports" to rtpPorts,
                "wfd_3d_video_formats" to "none",
                "wfd_content_protection" to "none",
                "wfd_display_edid" to "none",
                "wfd_coupled_sink" to "none",
                "wfd_uibc_capability" to "none",
                "wfd_standby_resume_capability" to "none",
                "wfd_connector_type" to "05",
                "wfd_idr_request_capability" to "1"
            )

            val sb = StringBuilder()
            if (requested.isEmpty()) {
                for ((k, v) in knownReplies) {
                    sb.append(k).append(": ").append(v).append("\r\n")
                }
            } else {
                for ((k, v) in knownReplies) {
                    if (k in requested) {
                        sb.append(k).append(": ").append(v).append("\r\n")
                    }
                }
            }
            return sb.toString()
        }

        /**
         * Builds the `wfd_video_formats` capability string matching [display].
         * Advertises both H.264 Constrained Baseline Profile (01) and High Profile (02) up to Level 4.2 (1f).
         */
        fun buildWfdVideoFormats(display: DisplayInfo): String {
            // WFD 1.0 defines CEA bits 0..16 (up to 1920x1080p24) and VESA bits 0..29 (up to
            // 1920x1200p60); higher bits are reserved, so nothing above 1920x1200 is offered.
            val ceaMask = 0x0001ffff
            val vesaMask = 0x3fffffff
            val hhMask = 0x00000fff

            // Native resolution byte: (index << 3) | table (0 = CEA, 1 = VESA, 2 = HH)
            // CEA index 6 = 1280x720p60 -> (6 << 3) | 0 = 0x30
            // CEA index 8 = 1920x1080p60 -> (8 << 3) | 0 = 0x40
            // VESA index 29 = 1920x1200p60 -> (29 << 3) | 1 = 0xE9
            val nativeByte = when {
                display.width == 1920 && display.height == 1200 -> 0xE9
                display.width <= 1280 && display.height <= 720 -> 0x30
                else -> 0x40
            }

            val ceaHex = String.format("%08x", ceaMask)
            val vesaHex = String.format("%08x", vesaMask)
            val hhHex = String.format("%08x", hhMask)
            val nativeHex = String.format("%02x", nativeByte)

            val cbpDesc = "01 1f $ceaHex $vesaHex $hhHex 00 0000 0000 00 none none"
            val chpDesc = "02 1f $ceaHex $vesaHex $hhHex 00 0000 0000 00 none none"
            return "$nativeHex 00 $cbpDesc, $chpDesc"
        }
    }
}
