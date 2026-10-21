package io.github.swiderekartmax.seconddisplay

import java.io.ByteArrayOutputStream

/**
 * Demultiplexer for Wi-Fi Display (Miracast / MS-MICE) RTP + MPEG-2 Transport Stream packets.
 *
 * Unwraps RTP (Payload Type 33 = MP2T, or raw 188-byte TS datagrams), parses PAT/PMT tables to
 * discover Elementary Stream PIDs, reassembles Video and Audio PES packets, and delivers:
 *  - Annex-B H.264 / HEVC video access units to [onVideoFrame]
 *  - 48 kHz 16-bit little-endian stereo LPCM or raw 48 kHz AAC-LC frames to [onAudioFrame]
 */
class MpegTsDemuxer(
    private val onVideoFrame: (data: ByteArray, ptsNs: Long, hevc: Boolean) -> Unit,
    private val onAudioFrame: (data: ByteArray, ptsNs: Long, ct: Int) -> Unit
) {
    private var pmtPid: Int = -1
    private var videoPid: Int = DEFAULT_WFD_VIDEO_PID
    private var videoStreamType: Int = STREAM_TYPE_H264
    private var audioPid: Int = DEFAULT_WFD_AUDIO_PID
    private var audioStreamType: Int = 0 // 0 = auto-detect from payload until PMT arrives

    private val videoPesBuffer = ByteArrayOutputStream(65536)
    private var videoPtsNs: Long = 0L
    private var videoExpectedPayloadBytes: Int = 0
    private var videoPesStarted: Boolean = false

    private val audioPesBuffer = ByteArrayOutputStream(8192)
    private var audioPtsNs: Long = 0L
    private var audioExpectedPayloadBytes: Int = 0
    private var audioPesStarted: Boolean = false

    /**
     * Feeds a single UDP datagram (either an RTP packet carrying N * 188-byte MPEG-2 TS packets,
     * or raw N * 188-byte MPEG-2 TS packets).
     */
    fun onUdpPacket(buffer: ByteArray, length: Int) {
        if (length < TS_PACKET_SIZE) return

        // Check if this is raw MPEG-2 TS without an RTP header
        val tsOffset = if (buffer[0] == TS_SYNC_BYTE && length % TS_PACKET_SIZE == 0) {
            0
        } else {
            parseRtpPayloadOffset(buffer, length)
        }
        if (tsOffset < 0 || tsOffset >= length) return

        var pos = tsOffset
        while (pos + TS_PACKET_SIZE <= length) {
            if (buffer[pos] != TS_SYNC_BYTE) {
                // Resync to next 0x47
                var nextSync = pos + 1
                while (nextSync + TS_PACKET_SIZE <= length && buffer[nextSync] != TS_SYNC_BYTE) {
                    nextSync++
                }
                pos = nextSync
                continue
            }
            parseTsPacket(buffer, pos)
            pos += TS_PACKET_SIZE
        }
    }

    /** Flushes any in-progress PES payloads (used in tests or on stream teardown). */
    fun flush() {
        flushVideoPes()
        flushAudioPes()
    }

    private fun parseRtpPayloadOffset(buf: ByteArray, length: Int): Int {
        if (length < RTP_MIN_HEADER_BYTES) return -1
        val b0 = buf[0].toInt() and 0xFF
        val version = (b0 ushr 6) and 0x03
        if (version != 2) return -1

        val hasPadding = (b0 and 0x20) != 0
        val hasExtension = (b0 and 0x10) != 0
        val csrcCount = b0 and 0x0F

        var offset = RTP_MIN_HEADER_BYTES + csrcCount * 4
        if (offset > length) return -1

        if (hasExtension) {
            if (offset + 4 > length) return -1
            val extWords = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
            offset += 4 + extWords * 4
        }

        val effectiveLength = if (hasPadding && length > offset) {
            val padBytes = buf[length - 1].toInt() and 0xFF
            if (padBytes in 1..(length - offset)) length - padBytes else length
        } else {
            length
        }

        return if (offset < effectiveLength) offset else -1
    }

    private fun parseTsPacket(buf: ByteArray, offset: Int) {
        val b1 = buf[offset + 1].toInt() and 0xFF
        val b2 = buf[offset + 2].toInt() and 0xFF
        val b3 = buf[offset + 3].toInt() and 0xFF

        val transportError = (b1 and 0x80) != 0
        if (transportError) return

        val pusi = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1F) shl 8) or b2
        val adaptationFieldControl = (b3 ushr 4) and 0x03

        // 0 or 2 means no payload
        if (adaptationFieldControl == 0 || adaptationFieldControl == 2) return

        var payloadOffset = offset + 4
        if (adaptationFieldControl == 3) {
            if (payloadOffset >= offset + TS_PACKET_SIZE) return
            val adaptationLength = buf[payloadOffset].toInt() and 0xFF
            payloadOffset += 1 + adaptationLength
        }
        val packetEnd = offset + TS_PACKET_SIZE
        if (payloadOffset >= packetEnd) return
        val payloadLen = packetEnd - payloadOffset

        when (pid) {
            PAT_PID -> parsePat(buf, payloadOffset, payloadLen, pusi)
            pmtPid -> parsePmt(buf, payloadOffset, payloadLen, pusi)
            videoPid -> handleVideoTsPayload(buf, payloadOffset, payloadLen, pusi)
            audioPid -> handleAudioTsPayload(buf, payloadOffset, payloadLen, pusi)
        }
    }

    private fun parsePat(buf: ByteArray, offset: Int, len: Int, pusi: Boolean) {
        var pos = offset
        var rem = len
        if (pusi) {
            if (rem < 1) return
            val pointerField = buf[pos].toInt() and 0xFF
            pos += 1 + pointerField
            rem -= 1 + pointerField
        }
        if (rem < 8) return
        val tableId = buf[pos].toInt() and 0xFF
        if (tableId != 0x00) return

        val sectionLength = ((buf[pos + 1].toInt() and 0x0F) shl 8) or (buf[pos + 2].toInt() and 0xFF)
        val totalBytes = minOf(rem, 3 + sectionLength)
        if (totalBytes < 12) return

        var entryPos = pos + 8
        val entriesEnd = pos + totalBytes - 4 // exclude 4-byte CRC32
        while (entryPos + 4 <= entriesEnd) {
            val programNumber = ((buf[entryPos].toInt() and 0xFF) shl 8) or (buf[entryPos + 1].toInt() and 0xFF)
            val pid = ((buf[entryPos + 2].toInt() and 0x1F) shl 8) or (buf[entryPos + 3].toInt() and 0xFF)
            if (programNumber != 0) {
                pmtPid = pid
                break
            }
            entryPos += 4
        }
    }

    private fun parsePmt(buf: ByteArray, offset: Int, len: Int, pusi: Boolean) {
        var pos = offset
        var rem = len
        if (pusi) {
            if (rem < 1) return
            val pointerField = buf[pos].toInt() and 0xFF
            pos += 1 + pointerField
            rem -= 1 + pointerField
        }
        if (rem < 12) return
        val tableId = buf[pos].toInt() and 0xFF
        if (tableId != 0x02) return

        val sectionLength = ((buf[pos + 1].toInt() and 0x0F) shl 8) or (buf[pos + 2].toInt() and 0xFF)
        val totalBytes = minOf(rem, 3 + sectionLength)
        if (totalBytes < 16) return

        val programInfoLength = ((buf[pos + 10].toInt() and 0x0F) shl 8) or (buf[pos + 11].toInt() and 0xFF)
        var esPos = pos + 12 + programInfoLength
        val esEnd = pos + totalBytes - 4 // exclude CRC32

        while (esPos + 5 <= esEnd) {
            val streamType = buf[esPos].toInt() and 0xFF
            val elementaryPid = ((buf[esPos + 1].toInt() and 0x1F) shl 8) or (buf[esPos + 2].toInt() and 0xFF)
            val esInfoLength = ((buf[esPos + 3].toInt() and 0x0F) shl 8) or (buf[esPos + 4].toInt() and 0xFF)

            when (streamType) {
                STREAM_TYPE_H264, STREAM_TYPE_HEVC -> {
                    videoPid = elementaryPid
                    videoStreamType = streamType
                }
                STREAM_TYPE_AAC_ADTS, STREAM_TYPE_AAC_LATM, STREAM_TYPE_WFD_LPCM -> {
                    audioPid = elementaryPid
                    audioStreamType = streamType
                }
            }
            esPos += 5 + esInfoLength
        }
    }

    private fun handleVideoTsPayload(buf: ByteArray, offset: Int, len: Int, pusi: Boolean) {
        if (pusi) {
            flushVideoPes()
            val header = parsePesHeader(buf, offset, len) ?: return
            videoPesStarted = true
            videoPtsNs = header.ptsNs
            videoExpectedPayloadBytes = header.expectedPayloadBytes
            val esOffset = offset + header.headerBytes
            val esLen = len - header.headerBytes
            if (esLen > 0) {
                videoPesBuffer.write(buf, esOffset, esLen)
            }
        } else if (videoPesStarted) {
            videoPesBuffer.write(buf, offset, len)
        }

        if (videoPesStarted && videoExpectedPayloadBytes > 0 && videoPesBuffer.size() >= videoExpectedPayloadBytes) {
            flushVideoPes()
        }
    }

    private fun handleAudioTsPayload(buf: ByteArray, offset: Int, len: Int, pusi: Boolean) {
        if (pusi) {
            flushAudioPes()
            val header = parsePesHeader(buf, offset, len) ?: return
            audioPesStarted = true
            audioPtsNs = header.ptsNs
            audioExpectedPayloadBytes = header.expectedPayloadBytes
            val esOffset = offset + header.headerBytes
            val esLen = len - header.headerBytes
            if (esLen > 0) {
                audioPesBuffer.write(buf, esOffset, esLen)
            }
        } else if (audioPesStarted) {
            audioPesBuffer.write(buf, offset, len)
        }

        if (audioPesStarted && audioExpectedPayloadBytes > 0 && audioPesBuffer.size() >= audioExpectedPayloadBytes) {
            flushAudioPes()
        }
    }

    private fun flushVideoPes() {
        if (!videoPesStarted || videoPesBuffer.size() == 0) {
            videoPesBuffer.reset()
            videoPesStarted = false
            videoExpectedPayloadBytes = 0
            return
        }
        val data = videoPesBuffer.toByteArray()
        val pts = videoPtsNs
        val isHevc = videoStreamType == STREAM_TYPE_HEVC
        videoPesBuffer.reset()
        videoPesStarted = false
        videoExpectedPayloadBytes = 0
        onVideoFrame(data, pts, isHevc)
    }

    private fun flushAudioPes() {
        if (!audioPesStarted || audioPesBuffer.size() == 0) {
            audioPesBuffer.reset()
            audioPesStarted = false
            audioExpectedPayloadBytes = 0
            return
        }
        val raw = audioPesBuffer.toByteArray()
        val pts = audioPtsNs
        audioPesBuffer.reset()
        audioPesStarted = false
        audioExpectedPayloadBytes = 0

        val isAdts = (audioStreamType == STREAM_TYPE_AAC_ADTS) ||
            (audioStreamType == 0 && raw.size >= 7 && (raw[0].toInt() and 0xFF) == 0xFF && (raw[1].toInt() and 0xF0) == 0xF0)

        if (isAdts) {
            emitAdtsFrames(raw, pts)
        } else {
            emitWfdLpcmFrame(raw, pts)
        }
    }

    private fun emitAdtsFrames(raw: ByteArray, basePtsNs: Long) {
        var pos = 0
        var frameIdx = 0
        while (pos + 7 <= raw.size) {
            if ((raw[pos].toInt() and 0xFF) != 0xFF || (raw[pos + 1].toInt() and 0xF0) != 0xF0) {
                pos++
                continue
            }
            val protectionAbsent = raw[pos + 1].toInt() and 0x01
            val headerLen = if (protectionAbsent != 0) 7 else 9
            val frameLen = ((raw[pos + 3].toInt() and 0x03) shl 11) or
                ((raw[pos + 4].toInt() and 0xFF) shl 3) or
                ((raw[pos + 5].toInt() and 0xE0) ushr 5)
            if (frameLen <= headerLen || pos + frameLen > raw.size) break

            val aacPayload = raw.copyOfRange(pos + headerLen, pos + frameLen)
            val framePts = if (basePtsNs > 0L) {
                basePtsNs + frameIdx * 1024L * 1_000_000_000L / 48000L
            } else {
                0L
            }
            onAudioFrame(aacPayload, framePts, NativeBridge.CT_WFD_AAC_48K)
            pos += frameLen
            frameIdx++
        }
    }

    private fun emitWfdLpcmFrame(raw: ByteArray, ptsNs: Long) {
        // Wi-Fi Display LPCM PES packets start with a 4-byte WFD LPCM subheader followed by
        // 16-bit big-endian stereo samples at 48 kHz.
        val pcmStart = if (raw.size > 4 && (raw.size - 4) % 4 == 0) 4 else 0
        val pcmLen = (raw.size - pcmStart) and 0x7FFFFFFC // align to 4-byte stereo frame
        if (pcmLen <= 0) return

        val pcmLe = ByteArray(pcmLen)
        var i = 0
        while (i < pcmLen) {
            pcmLe[i] = raw[pcmStart + i + 1]
            pcmLe[i + 1] = raw[pcmStart + i]
            i += 2
        }
        onAudioFrame(pcmLe, ptsNs, NativeBridge.CT_WFD_LPCM_48K)
    }

    private data class PesHeader(
        val headerBytes: Int,
        val ptsNs: Long,
        val expectedPayloadBytes: Int
    )

    private fun parsePesHeader(buf: ByteArray, offset: Int, len: Int): PesHeader? {
        if (len < 9) return null
        if (buf[offset].toInt() != 0 || buf[offset + 1].toInt() != 0 || buf[offset + 2].toInt() != 1) {
            return null
        }
        val pesPacketLength = ((buf[offset + 4].toInt() and 0xFF) shl 8) or (buf[offset + 5].toInt() and 0xFF)
        val flags2 = buf[offset + 7].toInt() and 0xFF
        val pesHeaderDataLength = buf[offset + 8].toInt() and 0xFF
        val totalHeaderBytes = 9 + pesHeaderDataLength
        if (totalHeaderBytes > len) return null

        var ptsNs = 0L
        if ((flags2 and 0x80) != 0 && pesHeaderDataLength >= 5 && offset + 14 <= buf.size) {
            val p0 = buf[offset + 9].toLong() and 0xFF
            val p1 = buf[offset + 10].toLong() and 0xFF
            val p2 = buf[offset + 11].toLong() and 0xFF
            val p3 = buf[offset + 12].toLong() and 0xFF
            val p4 = buf[offset + 13].toLong() and 0xFF
            val pts90k = ((p0 and 0x0E) shl 29) or
                (p1 shl 22) or
                ((p2 and 0xFE) shl 14) or
                (p3 shl 7) or
                (p4 ushr 1)
            ptsNs = pts90k * 100_000L / 9L
        }

        val expectedPayload = if (pesPacketLength > 3 + pesHeaderDataLength) {
            pesPacketLength - 3 - pesHeaderDataLength
        } else {
            0
        }

        return PesHeader(totalHeaderBytes, ptsNs, expectedPayload)
    }

    companion object {
        const val TS_PACKET_SIZE = 188
        const val TS_SYNC_BYTE: Byte = 0x47
        const val RTP_MIN_HEADER_BYTES = 12

        const val PAT_PID = 0x0000
        const val DEFAULT_WFD_VIDEO_PID = 0x1011
        const val DEFAULT_WFD_AUDIO_PID = 0x1100

        const val STREAM_TYPE_AAC_ADTS = 0x0F
        const val STREAM_TYPE_AAC_LATM = 0x11
        const val STREAM_TYPE_H264 = 0x1B
        const val STREAM_TYPE_HEVC = 0x24
        const val STREAM_TYPE_WFD_LPCM = 0x83
    }
}
