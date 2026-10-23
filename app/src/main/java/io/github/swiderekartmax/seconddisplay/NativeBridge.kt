package io.github.swiderekartmax.seconddisplay

object NativeBridge {
    interface Listener {
        /** ingressNanos is monotonic; core NTP nanoseconds are diagnostic metadata only. */
        fun onVideoFrame(data: ByteArray, ingressNanos: Long, ntpLocalNanos: Long, ntpRemoteNanos: Long, hevc: Boolean)
        fun onAudioFrame(data: ByteArray, ntpTimestamp: Long, ct: Int) {}
        fun onAudioVolume(volume: Float) {}
        fun onAudioFlush() {}
        fun onClientConnected(name: String)

        /** Any control socket closed - diagnostic only, not a mirror-stop signal. */
        fun onClientDisconnected()

        /** Verified mirror-stream lifetime; owns renderer state. */
        fun onMirrorRunning(running: Boolean)

        /**
         * Verified audio-stream lifetime (the audio RTP thread), for both the audio of a
         * mirroring session and audio-only sessions (Music / iTunes, iOS audio-only).
         * [ct] is the negotiated AirPlay codec: [CT_ALAC] or [CT_AAC_ELD]. ALAC is decoded
         * natively; its frames reach [onAudioFrame] as 16-bit stereo PCM tagged [CT_PCM].
         */
        fun onAudioRunning(running: Boolean, ct: Int) {}

        /**
         * Now-playing metadata (DMAP) for the current audio session; empty strings and a
         * zero [year] mean absent. Like the other audio callbacks it runs on the audio RTP
         * thread, between [onAudioRunning] true and false.
         */
        fun onNowPlaying(title: String, artist: String, album: String, year: Int) {}

        /** Cover art for the current track, as sent: PNG if [isPng], otherwise JPEG. */
        fun onCoverArt(data: ByteArray, isPng: Boolean) {}

        /** Playback position and track duration in seconds (0 when unknown). */
        fun onProgress(positionSec: Double, durationSec: Double) {}

        /** Unexpected mirror failure; the controller resets the server outside native callbacks. */
        fun onStreamError()

        /** "Require a code": the sender now asks its user for this 4-digit [pin]. */
        fun onDisplayPin(pin: String) {}

        /** A sender paired with the code; store [publicKey] so it skips the code next time. */
        fun onClientRegistered(publicKey: String, name: String) {}

        /**
         * AirPlay video (the YouTube app): play [url], an HLS playlist served by the core on
         * localhost, from [startSec]. This and the calls below run on the core's HTTP thread
         * and must not block it: that thread also serves the playlists to the player.
         */
        fun onVideoPlay(url: String, startSec: Float) {}

        /** The sender moved the AirPlay video to [positionSec]. */
        fun onVideoSeek(positionSec: Float) {}

        /** 1 = play, 0 = pause. */
        fun onVideoRate(rate: Float) {}

        /** The AirPlay video ended, was stopped on the sender, or a new sender took over. */
        fun onVideoStop() {}
    }

    /** Audio codec tags shared with receiver_jni.c (AirPlay "ct" values; 0 = decoded PCM). */
    const val CT_PCM = 0
    const val CT_ALAC = 2
    const val CT_AAC_ELD = 8

    init { System.loadLibrary("seconddisplay") }

    external fun nativeSetListener(listener: Listener?)

    /** Starts the AirPlay server; returns the RTSP port (7000) or a negative error. */
    external fun nativeStart(
        keyFile: String,
        deviceId: String,
        serviceName: String,
        width: Int,
        height: Int,
        maxFps: Int,
        allowHevc: Boolean,
        requirePin: Boolean,
        videoPlayback: Boolean,
        languages: String
    ): Int

    /** Senders paired with the code earlier (base64 ed25519 keys); call before [nativeStart]. */
    external fun nativeSetTrustedSenders(keys: Array<String>)

    /** Hex ed25519 public key owned by the server; advertise it as pk=. */
    external fun nativePublicKey(): String?

    /**
     * Player state for the sender's progress bar, in seconds. [finished] reports that the
     * video ended or was closed here; the core then ends the AirPlay video session.
     */
    external fun nativeSetPlaybackInfo(
        durationSec: Double,
        positionSec: Double,
        rate: Float,
        buffering: Boolean,
        finished: Boolean
    )

    external fun nativeStop()
}
