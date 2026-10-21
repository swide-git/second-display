package io.github.swiderekartmax.seconddisplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class MiracastReceiverTest {

    @Test
    fun `parses MS-MICE SOURCE_READY packet with UTF-16LE BOM and RTSP port`() {
        // Construct a packet matching GNOME Network Displays / Windows 10/11 MS-MICE format
        val nameWithBomAndNull = "\uFEFF ThinkPad-Linux\u0000"
        val nameBytes = nameWithBomAndNull.toByteArray(StandardCharsets.UTF_16LE)
        val portBytes = byteArrayOf(0x1C, 0x44) // 7236

        val body = ByteArrayOutputStream()
        body.write(MiracastReceiver.MICE_VERSION)
        body.write(MiracastReceiver.MICE_CMD_SOURCE_READY)
        // TLV 0x00: FRIENDLY_NAME
        body.write(MiracastReceiver.TLV_FRIENDLY_NAME)
        body.write((nameBytes.size ushr 8) and 0xFF)
        body.write(nameBytes.size and 0xFF)
        body.write(nameBytes)
        // TLV 0x02: RTSP_PORT
        body.write(MiracastReceiver.TLV_RTSP_PORT)
        body.write(0x00)
        body.write(0x02)
        body.write(portBytes)

        val bodyArr = body.toByteArray()
        val totalLen = 2 + bodyArr.size
        val fullPacket = ByteArrayOutputStream()
        fullPacket.write((totalLen ushr 8) and 0xFF)
        fullPacket.write(totalLen and 0xFF)
        fullPacket.write(bodyArr)

        val parsed = MiracastReceiver.parseMicePacket(fullPacket.toByteArray())
        assertEquals(MiracastReceiver.MICE_VERSION, parsed.version)
        assertEquals(MiracastReceiver.MICE_CMD_SOURCE_READY, parsed.command)
        assertEquals("ThinkPad-Linux", parsed.friendlyName)
        assertEquals(7236, parsed.rtspPort)
    }

    @Test
    fun `builds WFD M3 GET_PARAMETER response for Windows and Linux`() {
        val display = DisplayInfo(1920, 1200, 60, "16:10", "1920 × 1200")
        val m3Req = """
            wfd_client_rtp_ports
            wfd_audio_codecs
            wfd_video_formats
            wfd_3d_video_formats
            wfd_coupled_sink
            wfd_display_edid
            wfd_connector_type
            wfd_uibc_capability
            wfd_standby_resume_capability
            wfd_content_protection
        """.trimIndent()

        val resp = MiracastReceiver.buildM3ParametersResponse(m3Req, display, 19000)
        assertTrue(resp.contains("wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n"))
        assertTrue(resp.contains("wfd_audio_codecs: LPCM 00000003 00, AAC 00000001 00\r\n"))
        assertTrue(resp.contains("wfd_video_formats: e2 00 01 1f 0001ffff 1fffffff 00000fff"))
        assertTrue(resp.contains("wfd_content_protection: none\r\n"))
        assertTrue(resp.contains("wfd_uibc_capability: none\r\n"))
    }

    @Test
    fun `reads RTSP request and response messages with Content-Length`() {
        val body = "wfd_trigger_method: SETUP\r\n"
        val raw = "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
            "CSeq: 4\r\n" +
            "Content-Type: text/parameters\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n" +
            "\r\n" +
            body

        val msg = MiracastReceiver.readRtspMessage(ByteArrayInputStream(raw.toByteArray()))!!
        assertFalse(msg.isResponse)
        assertEquals("SET_PARAMETER", msg.method)
        assertEquals("4", msg.headers["cseq"])
        assertEquals(body, msg.body)
    }

    @Test
    fun `demuxes RTP MPEG-2 TS with PAT PMT H264 video and WFD LPCM audio`() {
        val videoFrames = mutableListOf<Pair<ByteArray, Long>>()
        val audioFrames = mutableListOf<Triple<ByteArray, Long, Int>>()

        val demuxer = MpegTsDemuxer(
            onVideoFrame = { data, ptsNs, hevc ->
                assertFalse(hevc)
                videoFrames.add(data to ptsNs)
            },
            onAudioFrame = { data, ptsNs, ct ->
                audioFrames.add(Triple(data, ptsNs, ct))
            }
        )

        // 1. PAT packet (PID 0x0000 -> PMT PID 0x0100)
        val patTs = buildPsiTsPacket(
            pid = 0x0000,
            section = byteArrayOf(
                0x00, // table_id = PAT
                0xB0.toByte(), 0x0D, // section_length = 13
                0x00, 0x01, // transport_stream_id
                0xC1.toByte(), 0x00, 0x00, // version / section numbers
                0x00, 0x01, // program_number = 1
                0xE1.toByte(), 0x00, // PMT PID = 0x0100
                0x00, 0x00, 0x00, 0x00 // CRC32 placeholder
            )
        )

        // 2. PMT packet (PID 0x0100 -> Video PID 0x1011 [0x1B H.264], Audio PID 0x1100 [0x83 WFD LPCM])
        val pmtTs = buildPsiTsPacket(
            pid = 0x0100,
            section = byteArrayOf(
                0x02, // table_id = PMT
                0xB0.toByte(), 0x17, // section_length = 23
                0x00, 0x01, // program_number = 1
                0xC1.toByte(), 0x00, 0x00,
                0xF0.toByte(), 0x11, // PCR_PID = 0x1011
                0xF0.toByte(), 0x00, // program_info_length = 0
                // Stream 1: H.264 (0x1B) on PID 0x1011
                0x1B, 0xF0.toByte(), 0x11, 0xF0.toByte(), 0x00,
                // Stream 2: WFD LPCM (0x83) on PID 0x1100
                0x83.toByte(), 0xF1.toByte(), 0x00, 0xF0.toByte(), 0x00,
                0x00, 0x00, 0x00, 0x00 // CRC32 placeholder
            )
        )

        // 3. Video PES packet on PID 0x1011 with PTS = 90000 (1.0 second = 1_000_000_000 ns)
        val annexB = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F, 0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte())
        val videoPes = buildPesWithPts(streamId = 0xE0, pts90k = 90_000L, payload = annexB)
        val videoTs = buildPesTsPacket(pid = 0x1011, pes = videoPes)

        // 4. Audio PES packet on PID 0x1100 with 4-byte WFD LPCM subheader + big-endian stereo samples
        val lpcmPayload = byteArrayOf(
            0x01, 0x00, 0x00, 0x10, // 4-byte WFD LPCM subheader
            0x12, 0x34, 0x56, 0x78  // 1 stereo sample in big-endian: L=0x1234, R=0x5678
        )
        val audioPes = buildPesWithPts(streamId = 0xBD, pts90k = 90_000L, payload = lpcmPayload)
        val audioTs = buildPesTsPacket(pid = 0x1100, pes = audioPes)

        // Wrap all 4 TS packets into a single RTP packet (12-byte RTP header + 4 * 188 bytes)
        val rtp = ByteArrayOutputStream()
        rtp.write(byteArrayOf(0x80.toByte(), 0x21, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x12, 0x34, 0x56, 0x78))
        rtp.write(patTs)
        rtp.write(pmtTs)
        rtp.write(videoTs)
        rtp.write(audioTs)
        val rtpBytes = rtp.toByteArray()

        demuxer.onUdpPacket(rtpBytes, rtpBytes.size)

        assertEquals(1, videoFrames.size)
        assertArrayEquals(annexB, videoFrames[0].first)
        assertEquals(1_000_000_000L, videoFrames[0].second)

        assertEquals(1, audioFrames.size)
        // Little-endian byte-swapped stereo sample: 0x34, 0x12, 0x78, 0x56
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x78, 0x56), audioFrames[0].first)
        assertEquals(NativeBridge.CT_WFD_LPCM_48K, audioFrames[0].third)
    }

    private fun buildPsiTsPacket(pid: Int, section: ByteArray): ByteArray {
        val ts = ByteArray(188) { 0xFF.toByte() }
        ts[0] = 0x47
        ts[1] = (0x40 or ((pid ushr 8) and 0x1F)).toByte()
        ts[2] = (pid and 0xFF).toByte()
        ts[3] = 0x10 // payload only
        ts[4] = 0x00 // pointer_field = 0
        System.arraycopy(section, 0, ts, 5, section.size)
        return ts
    }

    private fun buildPesWithPts(streamId: Int, pts90k: Long, payload: ByteArray): ByteArray {
        val pesHeaderLen = 5
        val pesPacketLength = 3 + pesHeaderLen + payload.size
        val out = ByteArrayOutputStream()
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(streamId)
        out.write((pesPacketLength ushr 8) and 0xFF)
        out.write(pesPacketLength and 0xFF)
        out.write(0x80)
        out.write(0x80) // PTS present
        out.write(pesHeaderLen)
        // Encode 33-bit PTS
        val p0 = (0x20L or (((pts90k ushr 30) and 0x07L) shl 1) or 1L).toInt()
        val p1 = ((pts90k ushr 22) and 0xFFL).toInt()
        val p2 = ((((pts90k ushr 15) and 0x7FL) shl 1) or 1L).toInt()
        val p3 = ((pts90k ushr 7) and 0xFFL).toInt()
        val p4 = (((pts90k and 0x7FL) shl 1) or 1L).toInt()
        out.write(p0)
        out.write(p1)
        out.write(p2)
        out.write(p3)
        out.write(p4)
        out.write(payload)
        return out.toByteArray()
    }

    private fun buildPesTsPacket(pid: Int, pes: ByteArray): ByteArray {
        val ts = ByteArray(188)
        ts[0] = 0x47
        ts[1] = (0x40 or ((pid ushr 8) and 0x1F)).toByte()
        ts[2] = (pid and 0xFF).toByte()
        val adaptationBytes = 188 - 4 - pes.size
        if (adaptationBytes > 0) {
            ts[3] = 0x30 // adaptation + payload
            ts[4] = (adaptationBytes - 1).toByte()
            for (i in 5 until 4 + adaptationBytes) {
                ts[i] = 0xFF.toByte()
            }
            System.arraycopy(pes, 0, ts, 4 + adaptationBytes, pes.size)
        } else {
            ts[3] = 0x10
            System.arraycopy(pes, 0, ts, 4, pes.size)
        }
        return ts
    }
}
