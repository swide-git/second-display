package io.github.swiderekartmax.seconddisplay

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide manager that owns the native AirPlay server, audio/video renderers,
 * connection history, and reactive state. Shared between [ReceiverService] and [MainActivity].
 */
object ReceiverServer {
    private const val TAG = "ReceiverServer"
    private const val MAX_ART_PX = 1024

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    lateinit var identity: ReceiverIdentity
        private set
    lateinit var history: ConnectionHistory
        private set

    // State flows observed by UI and Service
    private val _isMirroring = MutableStateFlow(false)
    val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

    private val _clientName = MutableStateFlow<String?>(null)
    val clientName: StateFlow<String?> = _clientName.asStateFlow()

    /** An audio-only AirPlay session (Music / iTunes, iOS audio) is playing through the speakers. */
    private val _isAudioStreaming = MutableStateFlow(false)
    val isAudioStreaming: StateFlow<Boolean> = _isAudioStreaming.asStateFlow()

    /** Track metadata and position for the audio session; shown full screen for audio-only sessions. */
    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val _nowPlayingArt = MutableStateFlow<Bitmap?>(null)
    val nowPlayingArt: StateFlow<Bitmap?> = _nowPlayingArt.asStateFlow()

    private val _statusText = MutableStateFlow("Starting receiver…")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow<Float?>(null)
    val videoAspectRatio: StateFlow<Float?> = _videoAspectRatio.asStateFlow()

    private val _recentConnections = MutableStateFlow<List<ConnectionEntry>>(emptyList())
    val recentConnections: StateFlow<List<ConnectionEntry>> = _recentConnections.asStateFlow()

    private val _displayInfo = MutableStateFlow(DisplayInfo(1920, 1080, 60, "16:9", "1920 × 1080 (Default)"))
    val displayInfo: StateFlow<DisplayInfo> = _displayInfo.asStateFlow()

    private val _orientationSetting = MutableStateFlow(ScreenOrientation.LANDSCAPE)
    val orientationSetting: StateFlow<ScreenOrientation> = _orientationSetting.asStateFlow()

    private val _isPortrait = MutableStateFlow(false)
    val isPortrait: StateFlow<Boolean> = _isPortrait.asStateFlow()

    data class OrientationNotice(
        val isPortrait: Boolean,
        val width: Int,
        val height: Int,
        val resolutionChange: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _orientationNotice = MutableStateFlow<OrientationNotice?>(null)
    val orientationNotice: StateFlow<OrientationNotice?> = _orientationNotice.asStateFlow()

    fun dismissOrientationNotice() {
        _orientationNotice.value = null
    }

    private var lastRawWidth = 1920
    private var lastRawHeight = 1080
    private var lastFps = 60

    private val _resolutionPreset = MutableStateFlow(ResolutionPreset.NATIVE)
    val resolutionPreset: StateFlow<ResolutionPreset> = _resolutionPreset.asStateFlow()

    /** Advertised resolution for [preset], for labels in the settings UI. */
    fun resolutionLabel(preset: ResolutionPreset): String =
        preset.label(lastRawWidth, lastRawHeight, _isPortrait.value)

    fun availableResolutionPresets(): List<ResolutionPreset> =
        ResolutionPreset.available(lastRawWidth, lastRawHeight)

    private fun scaledDisplayInfo(portrait: Boolean): DisplayInfo {
        // A preset saved on a larger panel setting may not fit this one.
        val preset = _resolutionPreset.value.takeIf { it.fits(lastRawWidth, lastRawHeight) } ?: ResolutionPreset.NATIVE
        val (w, h) = preset.size(lastRawWidth, lastRawHeight)
        return computeDisplayInfo(w, h, lastFps, portrait)
    }

    var audioEnabled: Boolean = true
        private set
    var debugOverlayEnabled: Boolean = false
        private set
    var hevcEnabled: Boolean = false
        private set
    private val hevcFailedThisProcess = AtomicBoolean(false)
    var runInBackground: Boolean = true
        private set
    /** New senders must enter a 4-digit code shown on this device (AirPlay PIN pairing). */
    var requirePin: Boolean = false
        private set
    private val _pairingCode = MutableStateFlow<String?>(null)
    /** The code a sender is waiting for, while it waits. */
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()
    private val _pairedSenderCount = MutableStateFlow(0)
    val pairedSenderCount: StateFlow<Int> = _pairedSenderCount.asStateFlow()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearPairingCode = Runnable { endPairingPrompt() }

    /** Home while mirroring shrinks the stream into a picture-in-picture window. */
    var floatingWindowEnabled: Boolean = true
        private set
    var startOnBoot: Boolean = true
        private set

    private var sessionStartTime = 0L
    private var sessionClientName: String? = null
    private var sessionResolution: String = ""
    private var sessionCodec: String = ""
    private var audioSessionStartTime = 0L

    // Renderers
    val renderer = VideoRenderer(
        reconnectRequired = { requestReconnect() },
        onHevcFailure = {
            hevcFailedThisProcess.set(true)
            Log.w(TAG, "HEVC session failed; next connection will offer H.264 only until H.265 is toggled or app restarts")
        },
        onVideoSizeChanged = { w, h ->
            _videoAspectRatio.value = if (w > 0 && h > 0) w.toFloat() / h.toFloat() else null
            if (w > 0 && h > 0) {
                sessionResolution = "$w × $h"
            }
        }
    )
    val audioRenderer = AudioRenderer(renderer.telemetry)

    // Activity state
    @Volatile
    var isActivityResumed: Boolean = false

    var onIncomingStreamBackgroundCallback: (() -> Unit)? = null

    // Server lifecycle internals
    private val serverExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "Receiver server") }
    private val serverLifecycle = ServerLifecycle(serverExecutor)
    private var lease: ServerLifecycle.Lease? = null

    private val destroyed = AtomicBoolean(false)
    private val callbacksEnabled = AtomicBoolean(false)
    private val callbackGeneration = AtomicLong()
    private val mirrorGeneration = AtomicLong()

    // Cover art is decoded off the audio RTP thread; a late decode must not outlive its session.
    private val artExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "Receiver cover art") }
    private val artLock = Any()
    private var artGeneration = 0L  // guarded by artLock

    val idleStatus: String
        get() = "Visible in AirPlay as “${identity.serviceName}”"

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

        identity = ReceiverIdentity(appContext)
        history = ConnectionHistory(appContext)
        _recentConnections.value = history.getRecent()

        audioEnabled = prefs.getBoolean("audio_enabled", true)
        debugOverlayEnabled = prefs.getBoolean("debug_overlay_enabled", false)
        hevcEnabled = prefs.getBoolean("hevc_enabled", false)
        runInBackground = prefs.getBoolean("run_in_background", true)
        floatingWindowEnabled = prefs.getBoolean("floating_window_enabled", true)
        requirePin = prefs.getBoolean("require_pin", false)
        _pairedSenderCount.value = pairedSenders().size
        startOnBoot = prefs.getBoolean("start_on_boot", true)
        _resolutionPreset.value = ResolutionPreset.fromId(prefs.getString("resolution_preset", null))

        audioRenderer.audioEnabled = audioEnabled

        val autoSupported = ReceiverIdentity.hasOrientationSensor(appContext)
        val defaultOrientation = ScreenOrientation.LANDSCAPE
        val savedSetting = ScreenOrientation.fromId(
            prefs.getString("orientation_setting", defaultOrientation.id)
        )
        _orientationSetting.value = if (!autoSupported && savedSetting == ScreenOrientation.AUTO) {
            defaultOrientation
        } else {
            savedSetting
        }

        _displayInfo.value = queryDisplayInfo(appContext)
    }

    fun setSurface(surface: Surface?) {
        renderer.setSurface(surface)
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
        audioRenderer.audioEnabled = enabled
        prefs.edit().putBoolean("audio_enabled", enabled).apply()
    }

    fun setDebugOverlayEnabled(enabled: Boolean) {
        debugOverlayEnabled = enabled
        prefs.edit().putBoolean("debug_overlay_enabled", enabled).apply()
    }

    fun setRequirePin(enabled: Boolean) {
        if (requirePin == enabled) return
        requirePin = enabled
        prefs.edit().putBoolean("require_pin", enabled).apply()
        // The pw= flag lives in the mDNS record and the PIN in the server: re-announce.
        restartServer()
    }

    /** Stored as "publicKey<TAB>name"; base64 keys contain no tab. */
    private fun pairedSenders(): Set<String> = prefs.getStringSet("paired_senders", emptySet()) ?: emptySet()

    fun forgetPairedSenders() {
        prefs.edit().remove("paired_senders").apply()
        NativeBridge.nativeSetTrustedSenders(emptyArray())
        _pairedSenderCount.value = 0
    }

    private fun endPairingPrompt() {
        mainHandler.removeCallbacks(clearPairingCode)
        if (_pairingCode.value != null) {
            _pairingCode.value = null
            if (_statusText.value.startsWith("Code ")) _statusText.value = idleStatus
        }
    }

    fun setFloatingWindowEnabled(enabled: Boolean) {
        floatingWindowEnabled = enabled
        prefs.edit().putBoolean("floating_window_enabled", enabled).apply()
    }

    fun setHevcEnabled(enabled: Boolean) {
        if (hevcEnabled == enabled) return
        hevcEnabled = enabled
        hevcFailedThisProcess.set(false)
        prefs.edit().putBoolean("hevc_enabled", enabled).apply()
        restartServer()
    }

    /** Senders only read the resolution at session start, so a live session is ended. */
    fun setResolutionPreset(preset: ResolutionPreset) {
        if (_resolutionPreset.value == preset) return
        _resolutionPreset.value = preset
        prefs.edit().putString("resolution_preset", preset.id).apply()
        applyOrientation(_isPortrait.value, resolutionChange = true)
    }

    fun setRunInBackground(enabled: Boolean) {
        runInBackground = enabled
        prefs.edit().putBoolean("run_in_background", enabled).apply()
    }

    fun setStartOnBoot(enabled: Boolean) {
        startOnBoot = enabled
        prefs.edit().putBoolean("start_on_boot", enabled).apply()
    }

    fun updateServiceName(newName: String) {
        identity.serviceName = newName
        restartServer()
    }

    fun setOrientationSetting(setting: ScreenOrientation) {
        val autoSupported = ReceiverIdentity.hasOrientationSensor(appContext)
        val targetSetting = if (!autoSupported && setting == ScreenOrientation.AUTO) {
            ScreenOrientation.LANDSCAPE
        } else {
            setting
        }
        if (_orientationSetting.value == targetSetting) return
        _orientationSetting.value = targetSetting
        prefs.edit().putString("orientation_setting", targetSetting.id).apply()

        val isSysPortrait = appContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
                lastRawWidth < lastRawHeight
        val targetPortrait = when (targetSetting) {
            ScreenOrientation.PORTRAIT -> true
            ScreenOrientation.LANDSCAPE -> false
            ScreenOrientation.AUTO -> isSysPortrait
        }
        applyOrientation(targetPortrait)
    }

    fun onConfigurationChanged(width: Int, height: Int, fps: Int, isPortrait: Boolean) {
        lastRawWidth = width
        lastRawHeight = height
        lastFps = fps
        val autoSupported = ReceiverIdentity.hasOrientationSensor(appContext)
        val targetPortrait = when (_orientationSetting.value) {
            ScreenOrientation.PORTRAIT -> true
            ScreenOrientation.LANDSCAPE -> false
            ScreenOrientation.AUTO -> if (!autoSupported) false else isPortrait
        }
        applyOrientation(targetPortrait)
    }

    private fun applyOrientation(targetPortrait: Boolean, resolutionChange: Boolean = false) {
        val orientationChanged = _isPortrait.value != targetPortrait
        _isPortrait.value = targetPortrait
        val newDisplay = scaledDisplayInfo(targetPortrait)
        val displayChanged = newDisplay != _displayInfo.value
        if (displayChanged || orientationChanged) {
            _displayInfo.value = newDisplay
            if (_isMirroring.value) {
                val cause = if (resolutionChange) "resolution_changed" else "orientation_changed"
                Log.i(TAG, "Disconnecting client: $cause (${newDisplay.width}x${newDisplay.height}, ${if (targetPortrait) "portrait" else "landscape"})")
                _orientationNotice.value = OrientationNotice(targetPortrait, newDisplay.width, newDisplay.height, resolutionChange)
            }
            if (isServerRunning()) {
                restartServer()
            }
        }
    }

    fun isServerRunning(): Boolean = callbacksEnabled.get()

    fun startServer() {
        check(::appContext.isInitialized) { "ReceiverServer must be initialized with context" }
        if (callbacksEnabled.get()) return

        val keyFile = File(appContext.filesDir, "server-key.pem").absolutePath

        lease = ServerLifecycle.Lease(
            start = {
                val currentDisplay = _displayInfo.value
                val requestHevc = hevcEnabled
                callbackGeneration.incrementAndGet()
                val startEvent = mirrorGeneration.incrementAndGet()
                if (mirrorGeneration.get() == startEvent) {
                    _isMirroring.value = false
                    _clientName.value = null
                    _statusText.value = "Starting receiver…"
                }
                identity.holdMulticastLock()
                identity.holdWifiLock()
                callbacksEnabled.set(true)
                NativeBridge.nativeSetListener(listener)
                renderer.hevcDecoder = if (requestHevc && !hevcFailedThisProcess.get()) VideoDecoderSupport.findHevc(
                    currentDisplay.width, currentDisplay.height, currentDisplay.refreshRate
                ) else null
                Log.i(TAG, "HEVC requested=$requestHevc hardware=${renderer.hevcDecoder}")
                Log.i(TAG, "Starting receiver advertising ${currentDisplay.width}x${currentDisplay.height}@${currentDisplay.refreshRate}Hz (portrait=${_isPortrait.value}, setting=${_orientationSetting.value})")
                NativeBridge.nativeSetTrustedSenders(
                    pairedSenders().map { it.substringBefore('\t') }.toTypedArray()
                )
                val port = NativeBridge.nativeStart(
                    keyFile, identity.deviceIdHex, identity.serviceName,
                    currentDisplay.width, currentDisplay.height, currentDisplay.refreshRate,
                    renderer.hevcDecoder != null, requirePin
                )
                check(port > 0) { "Server failed to start ($port)" }
                if (mirrorGeneration.get() == startEvent) {
                    _isMirroring.value = false
                    _clientName.value = null
                    _statusText.value = "$idleStatus. Select it to connect"
                }
            },
            stop = {
                callbacksEnabled.set(false)
                callbackGeneration.incrementAndGet()
                renderer.endSession()
                audioRenderer.endSession()
                NativeBridge.nativeSetListener(null)
                try {
                    NativeBridge.nativeStop()
                } finally {
                    identity.releaseMulticastLock()
                    identity.releaseWifiLock()
                }
            },
            failed = { error ->
                _isMirroring.value = false
                _statusText.value = error.message ?: "Server failed"
            }
        )
        lease?.let { serverLifecycle.start(it) }
    }

    fun stopServer() {
        mainHandler.post { endPairingPrompt() }
        callbacksEnabled.set(false)
        callbackGeneration.incrementAndGet()
        lease?.let { serverLifecycle.stop(it) }
        lease = null
        _isMirroring.value = false
        _isAudioStreaming.value = false
        clearNowPlaying()
        _clientName.value = null
        _statusText.value = "Receiver stopped"
    }

    fun restartServer() {
        val currentLease = lease
        if (currentLease != null && callbacksEnabled.get()) {
            recordSessionEnd()
            renderer.endSession()
            audioRenderer.endSession()
            mirrorGeneration.incrementAndGet()
            _isMirroring.value = false
            _isAudioStreaming.value = false
            clearNowPlaying()
            _videoAspectRatio.value = null
            _clientName.value = null
            _statusText.value = "Restarting receiver…"
            serverLifecycle.restart(currentLease)
        } else {
            startServer()
        }
    }

    /** The user left the stream on the device (Back/Home): drop the sender by restarting the receiver. */
    fun disconnectClient(reason: String) {
        if (!_isMirroring.value && !_isAudioStreaming.value) return
        Log.i("SecondDisplay", "Disconnecting client: $reason")
        restartServer()
    }

    private fun requestReconnect() {
        if (destroyed.get() || !callbacksEnabled.compareAndSet(true, false)) return
        recordSessionEnd()
        renderer.endSession()
        audioRenderer.endSession()
        mirrorGeneration.incrementAndGet()
        _isMirroring.value = false
        _isAudioStreaming.value = false
        clearNowPlaying()
        _videoAspectRatio.value = null
        _clientName.value = null
        _statusText.value = "Stream interrupted. Reconnect from Screen Mirroring"
        lease?.let { serverLifecycle.restart(it) }
    }

    private fun clearNowPlaying() {
        synchronized(artLock) {
            artGeneration++
            _nowPlayingArt.value = null
        }
        _nowPlaying.value = NowPlaying()
    }

    private fun showCoverArt(data: ByteArray) {
        val generation = synchronized(artLock) { artGeneration }
        artExecutor.execute {
            val bitmap = decodeCoverArt(data)
            synchronized(artLock) {
                if (artGeneration == generation) _nowPlayingArt.value = bitmap
            }
        }
    }

    /** Decodes JPEG or PNG art, downsampled so neither side exceeds [MAX_ART_PX]. */
    private fun decodeCoverArt(data: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_ART_PX || bounds.outHeight / sample > MAX_ART_PX) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (t: Throwable) {
        Log.w(TAG, "Cover art decode failed (${data.size} bytes)", t)
        null
    }

    private fun recordSessionEnd() {
        if (sessionStartTime > 0L) {
            val duration = (System.currentTimeMillis() - sessionStartTime) / 1000
            val name = sessionClientName ?: _clientName.value ?: "Unknown device"
            val res = sessionResolution.ifBlank { "${_displayInfo.value.width} × ${_displayInfo.value.height}" }
            val codec = sessionCodec.ifBlank { "H.264" }
            history.recordConnection(name, sessionStartTime, duration, res, codec)
            _recentConnections.value = history.getRecent()
            sessionStartTime = 0L
            sessionClientName = null
            sessionResolution = ""
            sessionCodec = ""
        }
    }

    private val listener = object : NativeBridge.Listener {
        override fun onVideoFrame(data: ByteArray, ingressNanos: Long, ntpLocalNanos: Long, ntpRemoteNanos: Long, hevc: Boolean) {
            sessionCodec = if (hevc) "H.265" else "H.264"
            if (callbacksEnabled.get()) renderer.onFrame(data, ingressNanos, ntpLocalNanos, ntpRemoteNanos, hevc)
        }

        override fun onAudioFrame(data: ByteArray, ntpTimestamp: Long, ct: Int) {
            if (!callbacksEnabled.get()) return
            audioRenderer.onFrame(data, ntpTimestamp, ct)
            // Audio flowing again after a flush (pause or seek): resume the progress clock.
            if (!_nowPlaying.value.playing) {
                _nowPlaying.update { it.resumed(SystemClock.elapsedRealtime()) }
            }
        }

        override fun onAudioVolume(volume: Float) {
            if (callbacksEnabled.get()) audioRenderer.setVolume(volume)
        }

        override fun onAudioFlush() {
            if (!callbacksEnabled.get()) return
            audioRenderer.flush()
            _nowPlaying.update { it.paused(SystemClock.elapsedRealtime()) }
        }

        override fun onNowPlaying(title: String, artist: String, album: String, year: Int) {
            if (!callbacksEnabled.get()) return
            _nowPlaying.update { it.withMetadata(title, artist, album, year) }
        }

        override fun onCoverArt(data: ByteArray, isPng: Boolean) {
            if (callbacksEnabled.get()) showCoverArt(data)
        }

        override fun onProgress(positionSec: Double, durationSec: Double) {
            if (!callbacksEnabled.get()) return
            _nowPlaying.update { it.withProgress(positionSec, durationSec, SystemClock.elapsedRealtime()) }
        }

        override fun onClientConnected(name: String) {
            sessionClientName = name
            if (callbacksEnabled.get()) {
                _clientName.value = name
                _statusText.value = "Connected: $name"
            }
        }

        override fun onClientDisconnected() {
            // Socket close is diagnostic only
        }

        override fun onAudioRunning(running: Boolean, ct: Int) {
            if (!callbacksEnabled.get()) return
            val codec = when (ct) {
                NativeBridge.CT_ALAC -> "ALAC"
                NativeBridge.CT_AAC_ELD -> "AAC-ELD"
                else -> "ct=$ct"
            }
            // Metadata, art and progress arrive only between these two callbacks.
            clearNowPlaying()
            if (running) {
                mainHandler.post { endPairingPrompt() }
                audioRenderer.beginSession(codec)
                // Audio arriving outside a mirroring session is an AirPlay speaker session
                // (Music / iTunes on a Mac, or iOS audio-only). Mirroring owns the UI otherwise.
                if (!_isMirroring.value) {
                    audioSessionStartTime = System.currentTimeMillis()
                    _isAudioStreaming.value = true
                    _statusText.value = "Playing audio from ${_clientName.value ?: "AirPlay"}"
                    Log.i(TAG, "Audio-only session started ($codec)")
                    if (!isActivityResumed) {
                        onIncomingStreamBackgroundCallback?.invoke()
                    }
                }
            } else {
                if (_isAudioStreaming.value) {
                    if (audioSessionStartTime > 0L) {
                        val duration = (System.currentTimeMillis() - audioSessionStartTime) / 1000
                        history.recordConnection(
                            sessionClientName ?: _clientName.value ?: "AirPlay audio",
                            audioSessionStartTime, duration, "Audio only", codec
                        )
                        _recentConnections.value = history.getRecent()
                    }
                    audioSessionStartTime = 0L
                    _isAudioStreaming.value = false
                    sessionClientName = null
                    _clientName.value = null
                    _statusText.value = idleStatus
                    Log.i(TAG, "Audio-only session ended")
                }
                if (!_isMirroring.value) {
                    audioRenderer.endSession()
                }
            }
        }

        override fun onMirrorRunning(running: Boolean) {
            if (!callbacksEnabled.get()) return
            if (running) {
                mainHandler.post { endPairingPrompt() }
                sessionStartTime = System.currentTimeMillis()
                sessionResolution = ""
                sessionCodec = ""
                dismissOrientationNotice()
                renderer.beginSession()
                audioRenderer.beginSession()
            } else {
                recordSessionEnd()
                renderer.endSession()
                audioRenderer.endSession()
                _videoAspectRatio.value = null
            }
            val event = mirrorGeneration.incrementAndGet()
            if (event == mirrorGeneration.get()) {
                _isMirroring.value = running
                if (running) {
                    _isAudioStreaming.value = false
                    _statusText.value = "Mirroring ${_clientName.value ?: "client"}"
                    if (!isActivityResumed) {
                        onIncomingStreamBackgroundCallback?.invoke()
                    }
                } else {
                    _clientName.value = null
                    _statusText.value = idleStatus
                }
            }
        }

        override fun onStreamError() = requestReconnect()

        override fun onDisplayPin(pin: String) {
            if (!callbacksEnabled.get()) return
            mainHandler.post {
                _pairingCode.value = pin
                _statusText.value = "Code for ${_clientName.value ?: "the sender"}: $pin"
                // The sender gives up after about a minute; drop the code with it.
                mainHandler.removeCallbacks(clearPairingCode)
                mainHandler.postDelayed(clearPairingCode, 90_000)
                if (!isActivityResumed) onIncomingStreamBackgroundCallback?.invoke()
            }
        }

        override fun onClientRegistered(publicKey: String, name: String) {
            val entry = "$publicKey\t$name"
            val updated = pairedSenders().filterNot { it.substringBefore('\t') == publicKey }.toSet() + entry
            prefs.edit().putStringSet("paired_senders", updated).apply()
            _pairedSenderCount.value = updated.size
            mainHandler.post { endPairingPrompt() }
        }
    }

    private fun queryDisplayInfo(context: Context): DisplayInfo {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        // init() receives the application context, which has no associated display: on API 30+
        // Context.display throws UnsupportedOperationException for it. DisplayManager works with
        // any context and returns the same default display.
        val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            wm?.defaultDisplay
        }
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val fps = display?.mode?.refreshRate?.toInt()
            ?: display?.refreshRate?.toInt()
            ?: 60
        lastRawWidth = if (metrics.widthPixels > 0) metrics.widthPixels else 1920
        lastRawHeight = if (metrics.heightPixels > 0) metrics.heightPixels else 1080
        lastFps = fps

        val autoSupported = ReceiverIdentity.hasOrientationSensor(context)
        val isSysPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
                lastRawWidth < lastRawHeight
        val targetPortrait = when (_orientationSetting.value) {
            ScreenOrientation.PORTRAIT -> true
            ScreenOrientation.LANDSCAPE -> false
            ScreenOrientation.AUTO -> if (!autoSupported) false else isSysPortrait
        }
        _isPortrait.value = targetPortrait
        return scaledDisplayInfo(targetPortrait)
    }
}
