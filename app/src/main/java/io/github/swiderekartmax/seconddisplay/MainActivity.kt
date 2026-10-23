@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.swiderekartmax.seconddisplay

import android.Manifest
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class DisplayInfo(
    val width: Int,
    val height: Int,
    val refreshRate: Int,
    val aspectRatioLabel: String,
    val recommendedResolutions: String
)

fun computeDisplayInfo(rawWidth: Int, rawHeight: Int, refreshRate: Int, isPortrait: Boolean = false): DisplayInfo {
    val longSide = maxOf(rawWidth, rawHeight)
    val shortSide = minOf(rawWidth, rawHeight)
    val w = if (isPortrait) shortSide else longSide
    val h = if (isPortrait) longSide else shortSide
    val fps = if (refreshRate > 0) refreshRate else 60
    val ratio = w.toDouble() / h.toDouble()

    val (label, resolutions) = if (!isPortrait) {
        when {
            // 16:10 (1.60) - e.g. 1280x800
            kotlin.math.abs(ratio - 1.6) < 0.05 -> {
                "16:10" to "$w × $h (Default) • 1440 × 900 • 1680 × 1050 • 1920 × 1200"
            }
            // 3:2 (1.50) - e.g. 2160x1440
            kotlin.math.abs(ratio - 1.5) < 0.05 -> {
                "3:2" to "$w × $h (Default) • 1620 × 1080 • 1344 × 896 • 1080 × 720"
            }
            // 16:9 (1.777...) - e.g. 1920x1080
            kotlin.math.abs(ratio - 16.0 / 9.0) < 0.05 -> {
                "16:9" to if (w >= 3840) "$w × $h (Default) • 2560 × 1440 • 1920 × 1080 • 1280 × 720"
                         else if (w >= 2560) "$w × $h (Default) • 1920 × 1080 • 1600 × 900 • 1280 × 720"
                         else "$w × $h (Default) • 1600 × 900 • 1366 × 768 • 1280 × 720"
            }
            // 4:3 (1.333...) - e.g. 1024x768
            kotlin.math.abs(ratio - 4.0 / 3.0) < 0.05 -> {
                "4:3" to "$w × $h (Default) • 1600 × 1200 • 1400 × 1050 • 1024 × 768"
            }
            else -> {
                fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
                val d = gcd(w, h)
                val rw = w / d
                val rh = h / d
                val aspect = if (rw <= 20 && rh <= 20) "$rw:$rh" else String.format(java.util.Locale.US, "%.2f:1", ratio)
                aspect to "$w × $h (Default)"
            }
        }
    } else {
        when {
            // 10:16 (0.625) - e.g. 800x1280
            kotlin.math.abs(ratio - 10.0 / 16.0) < 0.05 -> {
                "10:16" to "$w × $h (Default) • 900 × 1440 • 1050 × 1680 • 1200 × 1920"
            }
            // 2:3 (0.666...) - e.g. 1440x2160
            kotlin.math.abs(ratio - 2.0 / 3.0) < 0.05 -> {
                "2:3" to "$w × $h (Default) • 1080 × 1620 • 896 × 1344 • 720 × 1080"
            }
            // 9:16 (0.5625) - e.g. 1080x1920
            kotlin.math.abs(ratio - 9.0 / 16.0) < 0.05 -> {
                "9:16" to if (h >= 3840) "$w × $h (Default) • 1440 × 2560 • 1080 × 1920 • 720 × 1280"
                         else if (h >= 2560) "$w × $h (Default) • 1080 × 1920 • 900 × 1600 • 720 × 1280"
                         else "$w × $h (Default) • 900 × 1600 • 768 × 1366 • 720 × 1280"
            }
            // 3:4 (0.75) - e.g. 768x1024
            kotlin.math.abs(ratio - 3.0 / 4.0) < 0.05 -> {
                "3:4" to "$w × $h (Default) • 1200 × 1600 • 1050 × 1400 • 768 × 1024"
            }
            else -> {
                fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
                val d = gcd(w, h)
                val rw = w / d
                val rh = h / d
                val aspect = if (rw <= 20 && rh <= 20) "$rw:$rh" else String.format(java.util.Locale.US, "1:%.2f", 1.0 / ratio)
                aspect to "$w × $h (Default)"
            }
        }
    }

    return DisplayInfo(
        width = w,
        height = h,
        refreshRate = fps,
        aspectRatioLabel = label,
        recommendedResolutions = resolutions
    )
}

class MainActivity : ComponentActivity() {
    private var networkDetails by mutableStateOf(NetworkInfoHelper.NetworkDetails("...", "...", false))
    // Android 10+ only lets the service bring this activity forward with "Display over other apps".
    private var canAutoOpen by mutableStateOf(true)

    // In-session settings: long press on touch screens, OK or Menu on a remote (Android TV).
    private var showMirrorSettings by mutableStateOf(false)
    // Standby screen: last touch or key press, and whether the dimmed clock is up.
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var inStandby by mutableStateOf(false)

    // Picture-in-picture: leaving the app while mirroring shrinks the stream into a floating window.
    private var inPictureInPicture by mutableStateOf(false)
    // Where the picture sits on screen, so the floating window animates out of it.
    private var mirrorBounds by mutableStateOf<android.graphics.Rect?>(null)
    private val pipSupported by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
 
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Android 15+ draws edge to edge (targetSdk 35+); keep the bars hidden (swipe shows them
        // transiently) and let the mirror use the cutout area too.
        enableEdgeToEdge()
        // Android 8 has no cutout API (and no phones with cutouts).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        requestNotificationPermission()

        ReceiverServer.init(applicationContext)

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val fps = windowManager.defaultDisplay.mode?.refreshRate?.toInt()
            ?: windowManager.defaultDisplay.refreshRate.toInt()
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        ReceiverServer.onConfigurationChanged(metrics.widthPixels, metrics.heightPixels, fps, isPortrait)

        updateOrientationMode(ReceiverServer.orientationSetting.value)

        setContent {
            val mirroring by ReceiverServer.isMirroring.collectAsState()
            val videoPlayer by VideoPlayback.player.collectAsState()
            val audioStreaming by ReceiverServer.isAudioStreaming.collectAsState()
            val nowPlaying by ReceiverServer.nowPlaying.collectAsState()
            val nowPlayingArt by ReceiverServer.nowPlayingArt.collectAsState()
            val client by ReceiverServer.clientName.collectAsState()
            val status by ReceiverServer.statusText.collectAsState()
            val videoAspectRatio by ReceiverServer.videoAspectRatio.collectAsState()
            val recentConnections by ReceiverServer.recentConnections.collectAsState()
            val displayInfo by ReceiverServer.displayInfo.collectAsState()
            val orientationSetting by ReceiverServer.orientationSetting.collectAsState()
            val orientationNotice by ReceiverServer.orientationNotice.collectAsState()

            LaunchedEffect(orientationSetting) {
                updateOrientationMode(orientationSetting)
            }
            // The hotspot or Wi-Fi can change while the app is open; keep the status card current.
            LaunchedEffect(Unit) {
                while (true) {
                    delay(5_000)
                    networkDetails = NetworkInfoHelper.getNetworkDetails(this@MainActivity)
                }
            }
            var floatingWindowEnabled by remember { mutableStateOf(ReceiverServer.floatingWindowEnabled) }
            var requirePin by remember { mutableStateOf(ReceiverServer.requirePin) }
            var videoPlaybackEnabled by remember { mutableStateOf(ReceiverServer.videoPlaybackEnabled) }
            var standbyEnabled by remember { mutableStateOf(ReceiverServer.standbyEnabled) }
            var pictureDelayMs by remember { mutableIntStateOf(ReceiverServer.pictureDelayMs) }
            val pairingCode by ReceiverServer.pairingCode.collectAsState()
            val pairedSenderCount by ReceiverServer.pairedSenderCount.collectAsState()
            // Standby: dim to a clock after a minute without touch, keys or streams.
            LaunchedEffect(standbyEnabled, mirroring, audioStreaming, videoPlayer, pairingCode) {
                inStandby = false
                if (!standbyEnabled || mirroring || audioStreaming || videoPlayer != null || pairingCode != null) return@LaunchedEffect
                while (true) {
                    delay(1_000)
                    if (!inStandby && SystemClock.elapsedRealtime() - lastInteraction >= STANDBY_AFTER_MS) inStandby = true
                }
            }
            LaunchedEffect(inStandby) {
                window.attributes = window.attributes.apply {
                    screenBrightness = if (inStandby) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
            // Battery: a long mirroring session on battery should not end in a surprise shutdown.
            var lowBattery by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(mirroring) {
                lowBattery = null
                while (mirroring) {
                    lowBattery = lowBatteryLevel()
                    delay(60_000)
                }
            }
            // Keep the floating-window shape in step with the picture; auto-enter only while mirroring.
            LaunchedEffect(mirroring, videoPlayer, videoAspectRatio, mirrorBounds, floatingWindowEnabled) {
                if (pipSupported) setPictureInPictureParams(pictureInPictureParams())
            }

            var audioEnabled by remember { mutableStateOf(ReceiverServer.audioEnabled) }
            var debugOverlayEnabled by remember { mutableStateOf(ReceiverServer.debugOverlayEnabled) }
            var hevcEnabled by remember { mutableStateOf(ReceiverServer.hevcEnabled) }
            var runInBackground by remember { mutableStateOf(ReceiverServer.runInBackground) }
            var startOnBoot by remember { mutableStateOf(ReceiverServer.startOnBoot) }
            var serviceName by remember { mutableStateOf(ReceiverServer.identity.serviceName) }
            val resolutionPreset by ReceiverServer.resolutionPreset.collectAsState()

            SecondDisplayTheme {
                Surface(modifier = Modifier.fillMaxSize().onPreviewKeyEvent { handleKey(it.nativeKeyEvent) }) {
                    if (inStandby) {
                        StandbyScreen(serviceName = serviceName)
                    } else if (inPictureInPicture && !mirroring && videoPlayer == null) {
                        // The sender left while the floating window was up.
                        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                            Text("Stream ended", color = Palette.OverlayText, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (videoPlayer != null) {
                        // AirPlay video: the sender is the remote; Back closes the video here.
                        BackHandler { ReceiverServer.disconnectClient("back") }
                        VideoScreen(player = videoPlayer!!)
                    } else if (mirroring) {
                        BackHandler { ReceiverServer.disconnectClient("back") }
                        // Pinch zoom is local only; a new picture geometry starts unzoomed.
                        var zoom by remember(videoAspectRatio) { mutableStateOf(PinchZoom()) }
                        // The floating window always shows the whole picture.
                        val shownZoom = if (inPictureInPicture) PinchZoom() else zoom
                        var containerSize by remember { mutableStateOf(IntSize.Zero) }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .clipToBounds()
                                .onSizeChanged { containerSize = it },
                            contentAlignment = Alignment.Center
                        ) {
                            // The SurfaceView is laid out at the zoomed size rather than scaled with
                            // graphicsLayer: a SurfaceView's surface does not follow Compose layer transforms.
                            val surfaceModifier = Modifier.layout { measurable, constraints ->
                                val (baseW, baseH) = PinchZoom.fit(videoAspectRatio, constraints.maxWidth, constraints.maxHeight)
                                val w = (baseW * shownZoom.scale).toInt()
                                val h = (baseH * shownZoom.scale).toInt()
                                val placeable = measurable.measure(Constraints.fixed(w, h))
                                layout(baseW, baseH) {
                                    placeable.place(
                                        (baseW - w) / 2 + shownZoom.offsetX.toInt(),
                                        (baseH - h) / 2 + shownZoom.offsetY.toInt()
                                    )
                                }
                            }
                            AndroidView(
                                factory = { context ->
                                    SurfaceView(context).apply {
                                        holder.addCallback(object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: SurfaceHolder) {
                                                ReceiverServer.setSurface(holder.surface)
                                            }
                                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                                ReceiverServer.setSurface(null)
                                            }
                                        })
                                    }
                                },
                                modifier = surfaceModifier
                                    .background(Color.Black)
                                    .onGloballyPositioned { coordinates ->
                                        if (!inPictureInPicture) {
                                            val b = coordinates.boundsInWindow()
                                            mirrorBounds = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                                        }
                                    }
                            )
                            lowBattery?.takeIf { !inPictureInPicture }?.let { level ->
                                LowBatteryBadge(level, Modifier.align(Alignment.TopEnd).padding(16.dp))
                            }
                            if (debugOverlayEnabled && !inPictureInPicture) {
                                TelemetryOverlay(
                                    telemetry = ReceiverServer.renderer.telemetry,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(16.dp)
                                )
                            }
                            // Long press opens settings without leaving the mirror; Back still disconnects.
                            // Two fingers zoom, one finger pans a zoomed picture, double tap resets.
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onLongPress = { showMirrorSettings = true },
                                            onDoubleTap = { zoom = PinchZoom() }
                                        )
                                    }
                                    .pointerInput(videoAspectRatio) {
                                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                            val (baseW, baseH) = PinchZoom.fit(videoAspectRatio, containerSize.width, containerSize.height)
                                            zoom = zoom.transform(
                                                centroidX = centroid.x - containerSize.width / 2f,
                                                centroidY = centroid.y - containerSize.height / 2f,
                                                panX = pan.x,
                                                panY = pan.y,
                                                zoom = gestureZoom,
                                                contentWidth = baseW.toFloat(),
                                                contentHeight = baseH.toFloat(),
                                                containerWidth = containerSize.width.toFloat(),
                                                containerHeight = containerSize.height.toFloat()
                                            )
                                        }
                                    }
                            )
                        }
                        DisposableEffect(Unit) { onDispose { showMirrorSettings = false } }
                        if (showMirrorSettings && !inPictureInPicture) {
                            MirrorSettingsDialog(
                                debugOverlayEnabled = debugOverlayEnabled,
                                onDebugOverlayEnabledChanged = { enabled ->
                                    debugOverlayEnabled = enabled
                                    ReceiverServer.setDebugOverlayEnabled(enabled)
                                },
                                audioEnabled = audioEnabled,
                                onAudioEnabledChanged = { enabled ->
                                    audioEnabled = enabled
                                    ReceiverServer.setAudioEnabled(enabled)
                                },
                                resolutionPreset = resolutionPreset,
                                onResolutionPresetChanged = { ReceiverServer.setResolutionPreset(it) },
                                hevcEnabled = hevcEnabled,
                                onHevcEnabledChanged = { enabled ->
                                    hevcEnabled = enabled
                                    ReceiverServer.setHevcEnabled(enabled)
                                },
                                onDisconnect = {
                                    showMirrorSettings = false
                                    ReceiverServer.disconnectClient("settings")
                                },
                                onDismiss = { showMirrorSettings = false }
                            )
                        }
                    } else if (audioStreaming) {
                        // Audio-only AirPlay (speaker) session: full-screen now playing, left with Back/Home like mirroring.
                        BackHandler { ReceiverServer.disconnectClient("back") }
                        NowPlayingScreen(
                            nowPlaying = nowPlaying,
                            artwork = nowPlayingArt,
                            clientName = client,
                            debugOverlayEnabled = debugOverlayEnabled
                        )
                    } else {
                        DashboardScreen(
                            serviceName = serviceName,
                            onServiceNameChanged = { newName ->
                                ReceiverServer.updateServiceName(newName)
                                serviceName = ReceiverServer.identity.serviceName
                                networkDetails = NetworkInfoHelper.getNetworkDetails(this@MainActivity)
                            },
                            status = status,
                            networkDetails = networkDetails,
                            displayInfo = displayInfo,
                            audioEnabled = audioEnabled,
                            onAudioEnabledChanged = { enabled ->
                                audioEnabled = enabled
                                ReceiverServer.setAudioEnabled(enabled)
                            },
                            debugOverlayEnabled = debugOverlayEnabled,
                            hevcEnabled = hevcEnabled,
                            onHevcEnabledChanged = { enabled ->
                                hevcEnabled = enabled
                                ReceiverServer.setHevcEnabled(enabled)
                            },
                            onDebugOverlayEnabledChanged = { enabled ->
                                debugOverlayEnabled = enabled
                                ReceiverServer.setDebugOverlayEnabled(enabled)
                            },
                            pictureDelayMs = pictureDelayMs,
                            onPictureDelayChanged = { ms ->
                                pictureDelayMs = ms
                                ReceiverServer.setPictureDelayMs(ms)
                            },
                            standbyEnabled = standbyEnabled,
                            onStandbyEnabledChanged = { enabled ->
                                standbyEnabled = enabled
                                ReceiverServer.setStandbyEnabled(enabled)
                                lastInteraction = SystemClock.elapsedRealtime()
                            },
                            pipSupported = pipSupported,
                            requirePin = requirePin,
                            onRequirePinChanged = { enabled ->
                                requirePin = enabled
                                ReceiverServer.setRequirePin(enabled)
                            },
                            videoPlaybackEnabled = videoPlaybackEnabled,
                            onVideoPlaybackEnabledChanged = { enabled ->
                                videoPlaybackEnabled = enabled
                                ReceiverServer.setVideoPlaybackEnabled(enabled)
                            },
                            pairedSenderCount = pairedSenderCount,
                            onForgetPairedSenders = { ReceiverServer.forgetPairedSenders() },
                            floatingWindowEnabled = floatingWindowEnabled,
                            onFloatingWindowEnabledChanged = { enabled ->
                                floatingWindowEnabled = enabled
                                ReceiverServer.setFloatingWindowEnabled(enabled)
                            },
                            runInBackground = runInBackground,
                            onRunInBackgroundChanged = { enabled ->
                                runInBackground = enabled
                                ReceiverServer.setRunInBackground(enabled)
                                if (enabled) {
                                    ReceiverService.start(this@MainActivity)
                                }
                            },
                            startOnBoot = startOnBoot,
                            onStartOnBootChanged = { enabled ->
                                startOnBoot = enabled
                                ReceiverServer.setStartOnBoot(enabled)
                            },
                            canAutoOpen = canAutoOpen,
                            onRequestAutoOpen = { openOverlaySettings() },
                            onRestartServer = {
                                ReceiverServer.restartServer()
                                networkDetails = NetworkInfoHelper.getNetworkDetails(this@MainActivity)
                            },
                            recentConnections = recentConnections,
                            orientationSetting = orientationSetting,
                            onOrientationSettingChanged = { ReceiverServer.setOrientationSetting(it) },
                            resolutionPreset = resolutionPreset,
                            onResolutionPresetChanged = { ReceiverServer.setResolutionPreset(it) },
                            onQuit = { quit() }
                        )

                        orientationNotice?.let { notice ->
                            OrientationNoticeDialog(
                                notice = notice,
                                onDismiss = { ReceiverServer.dismissOrientationNotice() }
                            )
                        }
                        pairingCode?.let { code -> PairingCodeDialog(code = code, clientName = client) }
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (wakeFromStandby()) return true
        return super.dispatchTouchEvent(ev)
    }

    /** Every key reaches Compose first: wake from standby; on a remote, OK or Menu opens the in-session settings. */
    private fun handleKey(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && wakeFromStandby()) return true
        // AirPlay video on a TV remote: OK / play-pause toggles, left and right skip 10 seconds.
        // The sender follows through its progress polls.
        VideoPlayback.player.value?.let { player ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return event.keyCode in VIDEO_KEYS
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                    player.playWhenReady = !player.playWhenReady
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> player.playWhenReady = true
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> player.playWhenReady = false
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                    player.seekTo(player.currentPosition + SEEK_STEP_MS)
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_REWIND ->
                    player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
                else -> return false
            }
            return true
        }
        if (ReceiverServer.isMirroring.value && !showMirrorSettings && event.action == android.view.KeyEvent.ACTION_UP &&
            event.keyCode in MIRROR_SETTINGS_KEYS
        ) {
            showMirrorSettings = true
            return true
        }
        return false
    }

    /** Any touch or key counts as activity; the first one only wakes the screen from standby. */
    private fun wakeFromStandby(): Boolean {
        lastInteraction = SystemClock.elapsedRealtime()
        if (!inStandby) return false
        inStandby = false
        return true
    }

    /** Battery percent when it is low and not charging, otherwise null. */
    private fun lowBatteryLevel(): Int? {
        val status = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        val plugged = status.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0 || plugged) return null
        val percent = level * 100 / scale
        return percent.takeIf { it <= LOW_BATTERY_PERCENT }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-applied on focus: dialogs and the transient swipe can bring the bars back.
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        // The compat controller maps to system UI flags on Android 9-10.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun requestNotificationPermission() {
        // Android 13+: asked once; if declined the receiver still runs, only its notification is hidden.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    // The receiver runs while the app is visible. With "Run in background" off it stops as soon
    // as the app leaves the screen (Home, recents, screen off), not only when the activity is
    // destroyed, which on a tablet can take hours.
    override fun onStart() {
        super.onStart()
        startedActivities++
        ReceiverService.start(this)
    }

    override fun onStop() {
        super.onStop()
        startedActivities--
        // Left the mirror without the floating window (recents, another app, PiP turned off in
        // settings). Screen off keeps the session, as before.
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (!isChangingConfigurations && !isInPictureInPictureMode && screenOn) {
            ReceiverServer.disconnectClient("left the app")
        }
        if (!isChangingConfigurations && startedActivities == 0 && !ReceiverServer.runInBackground) {
            stopReceiver()
        }
    }

    private fun stopReceiver() {
        ReceiverService.stop(this)
        ReceiverServer.stopServer()
    }

    /** Quit from the top app bar: stop the receiver whatever "Run in background" says and close the task. */
    private fun quit() {
        stopReceiver()
        finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        ReceiverServer.isActivityResumed = true
        networkDetails = NetworkInfoHelper.getNetworkDetails(this)
        canAutoOpen = Settings.canDrawOverlays(this)
    }

    private fun openOverlaySettings() {
        val perApp = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(perApp) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            .onFailure { android.util.Log.w("SecondDisplay", "No overlay permission settings screen", it) }
    }

    override fun onPause() {
        super.onPause()
        ReceiverServer.isActivityResumed = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The floating window has its own shape; the advertised display must not follow it.
        if (!isInPictureInPictureMode) reportDisplay(newConfig)
    }

    private fun reportDisplay(newConfig: Configuration) {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val fps = windowManager.defaultDisplay.mode?.refreshRate?.toInt()
            ?: windowManager.defaultDisplay.refreshRate.toInt()
        val isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        ReceiverServer.onConfigurationChanged(metrics.widthPixels, metrics.heightPixels, fps, isPortrait)
    }

    private fun updateOrientationMode(setting: ScreenOrientation) {
        val effectiveSetting = if (!ReceiverIdentity.hasOrientationSensor(this) && setting == ScreenOrientation.AUTO) {
            ScreenOrientation.LANDSCAPE
        } else {
            setting
        }
        requestedOrientation = when (effectiveSetting) {
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    private fun pictureInPictureParams(): PictureInPictureParams {
        // Android accepts aspect ratios between 1:2.39 and 2.39:1.
        val video = VideoPlayback.player.value
        val videoRatio = video?.videoSize?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width * it.pixelWidthHeightRatio / it.height }
        val ratio = (if (video != null) videoRatio else ReceiverServer.videoAspectRatio.value ?: 16f / 9f)
            ?.coerceIn(1f / 2.39f, 2.39f) ?: (16f / 9f)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        if (video == null) mirrorBounds?.takeIf { !it.isEmpty }?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(streamOnScreen() && ReceiverServer.floatingWindowEnabled)
                .setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    /** A mirror or an AirPlay video fills the screen: Home turns it into the floating window. */
    private fun streamOnScreen(): Boolean = ReceiverServer.isMirroring.value || VideoPlayback.player.value != null

    /** False when the user switched picture-in-picture off for this app in system settings. */
    private fun pictureInPictureAllowed(): Boolean {
        if (!pipSupported || !ReceiverServer.floatingWindowEnabled) return false
        val appOps = getSystemService(AppOpsManager::class.java) ?: return true
        // Deprecated in SDK 37 without a replacement available on Android 9-11; still the way to
        // read the per-app picture-in-picture switch there.
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, applicationInfo.uid, packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, applicationInfo.uid, packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // Home is a deliberate exit; not called when the service brings this activity forward.
    // While mirroring it opens the floating window instead (Android 12+ enters it on its own).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (streamOnScreen() && pictureInPictureAllowed()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
            if (runCatching { enterPictureInPictureMode(pictureInPictureParams()) }.getOrDefault(false)) return
        }
        ReceiverServer.disconnectClient("home")
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        if (isInPictureInPictureMode) return
        if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // The floating window was closed (swiped away or ×), not expanded.
            ReceiverServer.disconnectClient("floating window closed")
        } else {
            reportDisplay(newConfig)
        }
    }

    override fun onDestroy() {
        ReceiverServer.setSurface(null)
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1

        /** Started (visible) MainActivity instances; the service may bring a second one forward. */
        var startedActivities = 0

        const val STANDBY_AFTER_MS = 60_000L
        const val LOW_BATTERY_PERCENT = 15
        const val SEEK_STEP_MS = 10_000L
        val VIDEO_KEYS = setOf(
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_REWIND
        )
        val MIRROR_SETTINGS_KEYS = setOf(
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_MENU
        )
    }
}

/** Dim clock with the receiver name. It drifts a little every minute so a panel does not burn in. */
/** AirPlay video, full screen; controls stay on the sender (and the TV remote, see handleKey). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoScreen(player: androidx.media3.exoplayer.ExoPlayer) {
    AndroidView(
        factory = { context ->
            androidx.media3.ui.PlayerView(context).apply {
                useController = false
                setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize().background(Color.Black)
    )
}

@Composable
private fun StandbyScreen(serviceName: String) {
    var now by remember { mutableStateOf(java.util.Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.util.Date()
            delay(1_000)
        }
    }
    val minute = (now.time / 60_000L).toInt()
    val drift = Modifier.offset(x = ((minute * 37) % 61 - 30).dp, y = ((minute * 53) % 41 - 20).dp)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = drift) {
            Text(
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(now),
                style = MaterialTheme.typography.displayLarge,
                color = Palette.OverlayMuted
            )
            Spacer(Modifier.height(8.dp))
            Text("$serviceName · ready for AirPlay", style = MaterialTheme.typography.bodyLarge, color = Palette.OverlayMuted)
        }
    }
}

/** Small corner badge over the mirrored picture when the battery runs low. */
@Composable
private fun LowBatteryBadge(percent: Int, modifier: Modifier = Modifier) {
    Text(
        "Battery $percent%",
        modifier = modifier
            .background(Palette.OverlayScrim, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = Color(0xFFFFB4AB),
        style = MaterialTheme.typography.labelLarge
    )
}

/** The code a sender asks for while pairing; stays up until it pairs, starts streaming or gives up. */
@Composable
private fun PairingCodeDialog(code: String, clientName: String?) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = { Text("AirPlay code") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Enter this code on ${clientName ?: "your Mac, iPhone or iPad"}",
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    code.toCharArray().joinToString(" "),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {}
    )
}

/** D-pad / keyboard focus ring for clickable rows and tiles (TVs and tablets with keyboards). */
@Composable
private fun Modifier.focusRing(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 0.dp, if (focused) color else Color.Transparent, shape)
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

/** A settings row: title, supporting text, and a switch or a trailing action. */
@Composable
private fun SettingRow(
    title: String,
    supporting: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val shape = RoundedCornerShape(16.dp)
    ListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(shape)
            .then(if (onClick != null) Modifier.focusRing(shape).clickable(onClick = onClick) else Modifier)
    )
}

@Composable
private fun SwitchRow(title: String, supporting: String, icon: ImageVector, checked: Boolean, onToggle: () -> Unit) {
    SettingRow(
        title = title,
        supporting = supporting,
        icon = icon,
        onClick = onToggle,
        trailing = { Switch(checked = checked, onCheckedChange = null) }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Pulsing dot: the receiver is waiting for a sender. */
@Composable
private fun PulseDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val ring by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "ring"
    )
    Box(modifier = modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(12.dp + 16.dp * ring)
                .background(color.copy(alpha = 0.35f * (1f - ring)), CircleShape)
        )
        Box(Modifier.size(12.dp).background(color, CircleShape))
    }
}

@Composable
private fun StatusCard(
    ready: Boolean,
    status: String,
    serviceName: String,
    networkDetails: NetworkInfoHelper.NetworkDetails,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ready) {
                    PulseDot(color = colors.primary)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (ready) "Ready to connect" else status,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "On your Mac, iPhone or iPad open Screen Mirroring and choose",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = serviceName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(networkDetails.wifiSsid, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(AppIcons.Wifi, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text(networkDetails.ipAddress, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
            networkDetails.hotspotAddress?.let { address ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Hotspot is on ($address): a Mac joined to this device's hotspot can connect too, no router needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/** Quick picker tile: label, current value, opens a menu. */
@Composable
private fun PickerTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    menu: @Composable (expanded: Boolean, dismiss: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Box(modifier = modifier) {
        Card(
            onClick = { expanded = true },
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth().focusRing(shape)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(modifier = Modifier.height(12.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        menu(expanded) { expanded = false }
    }
}

@Composable
private fun ResolutionMenu(selected: ResolutionPreset, expanded: Boolean, onDismiss: () -> Unit, onSelected: (ResolutionPreset) -> Unit) {
    val available = ReceiverServer.availableResolutionPresets()
    // A preset that does not fit this panel is not applied; show what is actually advertised.
    val effective = if (selected in available) selected else ResolutionPreset.NATIVE
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        available.forEach { preset ->
            DropdownMenuItem(
                text = { Text(ReceiverServer.resolutionLabel(preset)) },
                leadingIcon = if (preset == effective) {
                    { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                } else null,
                onClick = {
                    onDismiss()
                    onSelected(preset)
                }
            )
        }
    }
}

private fun ScreenOrientation.description(autoSupported: Boolean): String = when (this) {
    ScreenOrientation.LANDSCAPE -> if (autoSupported) "Always landscape" else "Landscape"
    ScreenOrientation.PORTRAIT -> if (autoSupported) "Always portrait" else "Portrait"
    ScreenOrientation.AUTO -> "Follows the tilt sensor"
}

/** Narrower than this the dashboard is not laid out (see the guard in DashboardScreen). */
private val MIN_DASHBOARD_WIDTH = 280.dp

@Composable
private fun DashboardScreen(
    serviceName: String,
    onServiceNameChanged: (String) -> Unit,
    status: String,
    networkDetails: NetworkInfoHelper.NetworkDetails,
    displayInfo: DisplayInfo,
    audioEnabled: Boolean,
    onAudioEnabledChanged: (Boolean) -> Unit,
    debugOverlayEnabled: Boolean,
    hevcEnabled: Boolean,
    onHevcEnabledChanged: (Boolean) -> Unit,
    onDebugOverlayEnabledChanged: (Boolean) -> Unit,
    pictureDelayMs: Int,
    onPictureDelayChanged: (Int) -> Unit,
    standbyEnabled: Boolean,
    onStandbyEnabledChanged: (Boolean) -> Unit,
    pipSupported: Boolean,
    requirePin: Boolean,
    onRequirePinChanged: (Boolean) -> Unit,
    videoPlaybackEnabled: Boolean,
    onVideoPlaybackEnabledChanged: (Boolean) -> Unit,
    pairedSenderCount: Int,
    onForgetPairedSenders: () -> Unit,
    floatingWindowEnabled: Boolean,
    onFloatingWindowEnabledChanged: (Boolean) -> Unit,
    runInBackground: Boolean,
    onRunInBackgroundChanged: (Boolean) -> Unit,
    startOnBoot: Boolean,
    onStartOnBootChanged: (Boolean) -> Unit,
    canAutoOpen: Boolean,
    onRequestAutoOpen: () -> Unit,
    onRestartServer: () -> Unit,
    recentConnections: List<ConnectionEntry>,
    orientationSetting: ScreenOrientation,
    onOrientationSettingChanged: (ScreenOrientation) -> Unit,
    resolutionPreset: ResolutionPreset,
    onResolutionPresetChanged: (ResolutionPreset) -> Unit,
    onQuit: () -> Unit
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showDelayDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    var lastRunCrashed by remember { mutableStateOf(ProblemReport.hasLastCrash(context)) }
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0-dev"
        }.getOrDefault("0.1.0-dev")
    }
    var latestReleaseVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val url = java.net.URL("https://api.github.com/repos/swide-git/second-display/releases/latest")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "SecondDisplay/$appVersion")
                }
                try {
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val match = Regex(""""tag_name"\s*:\s*"v?([^"]+)"""").find(body)
                        match?.groupValues?.getOrNull(1)?.let { tag ->
                            latestReleaseVersion = tag
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
    }
    val baseAppVersion = remember(appVersion) { appVersion.substringBefore("-") }
    val hasNewerRelease = latestReleaseVersion != null && latestReleaseVersion != baseAppVersion && latestReleaseVersion != appVersion
    val isAutoSupported = remember(context) { ReceiverIdentity.hasOrientationSensor(context) }
    val availableOrientations = remember(isAutoSupported) {
        if (isAutoSupported) {
            ScreenOrientation.values().toList()
        } else {
            listOf(ScreenOrientation.LANDSCAPE, ScreenOrientation.PORTRAIT)
        }
    }
    val ready = status.startsWith("Visible")
    val wide = configuration.screenWidthDp >= 840 ||
        (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && configuration.screenWidthDp >= 600)

    val statusCard = @Composable {
        StatusCard(ready = ready, status = status, serviceName = serviceName, networkDetails = networkDetails)
    }

    val pickers = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            val available = ReceiverServer.availableResolutionPresets()
            val effective = if (resolutionPreset in available) resolutionPreset else ResolutionPreset.NATIVE
            PickerTile(
                label = "Resolution",
                value = ReceiverServer.resolutionLabel(effective),
                icon = AppIcons.AspectRatio,
                modifier = Modifier.weight(1f)
            ) { expanded, dismiss ->
                ResolutionMenu(resolutionPreset, expanded, dismiss, onResolutionPresetChanged)
            }
            PickerTile(
                label = "Orientation",
                value = orientationSetting.label,
                icon = Icons.Filled.Refresh,
                modifier = Modifier.weight(1f)
            ) { expanded, dismiss ->
                DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
                    availableOrientations.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.description(isAutoSupported)) },
                            leadingIcon = if (option == orientationSetting) {
                                { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                            } else null,
                            onClick = {
                                dismiss()
                                onOrientationSettingChanged(option)
                            }
                        )
                    }
                }
            }
        }
    }

    val settingsCard = @Composable {
        SectionCard(title = "Settings") {
            SwitchRow(
                title = "Speaker",
                supporting = if (audioEnabled) "Sound plays on this device" else "Muted on this device",
                icon = AppIcons.VolumeUp,
                checked = audioEnabled,
                onToggle = { onAudioEnabledChanged(!audioEnabled) }
            )
            if (audioEnabled) {
                val bluetooth = remember(showDelayDialog) { ReceiverServer.isBluetoothAudioOutput(context) }
                SettingRow(
                    title = "Picture delay",
                    supporting = when {
                        pictureDelayMs > 0 -> "$pictureDelayMs ms, to match the sound"
                        bluetooth -> "Bluetooth audio lags the picture: delay the picture to match"
                        else -> "Off. For Bluetooth headphones or speakers"
                    },
                    icon = AppIcons.Timer,
                    onClick = { showDelayDialog = true },
                    trailing = { TextButton(onClick = { showDelayDialog = true }) { Text("Adjust") } }
                )
            }
            SettingRow(
                title = "Open when a stream starts",
                supporting = if (canAutoOpen) "The receiver comes to the front on its own"
                             else "Needs \"Display over other apps\"",
                icon = AppIcons.OpenInNew,
                onClick = if (!canAutoOpen) onRequestAutoOpen else null,
                titleColor = if (canAutoOpen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                trailing = {
                    if (canAutoOpen) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Allowed", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        FilledTonalButton(onClick = onRequestAutoOpen) { Text("Allow") }
                    }
                }
            )
            if (pipSupported) {
                SwitchRow(
                    title = "Floating window",
                    supporting = if (floatingWindowEnabled) "Home while mirroring keeps the picture in a small window"
                                 else "Home while mirroring disconnects",
                    icon = AppIcons.PictureInPicture,
                    checked = floatingWindowEnabled,
                    onToggle = { onFloatingWindowEnabledChanged(!floatingWindowEnabled) }
                )
            }
            SwitchRow(
                title = "Standby screen",
                supporting = "After a minute idle: a dim clock until a stream starts or you touch the screen",
                icon = AppIcons.Clock,
                checked = standbyEnabled,
                onToggle = { onStandbyEnabledChanged(!standbyEnabled) }
            )
            SwitchRow(
                title = "Run in background",
                supporting = "Stay visible when the app is closed",
                icon = Icons.Filled.Notifications,
                checked = runInBackground,
                onToggle = { onRunInBackgroundChanged(!runInBackground) }
            )
            SwitchRow(
                title = "Start on boot",
                supporting = "Start the receiver when the device turns on",
                icon = AppIcons.Power,
                checked = startOnBoot,
                onToggle = { onStartOnBootChanged(!startOnBoot) }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            SwitchRow(
                title = "AirPlay video",
                supporting = if (videoPlaybackEnabled) "YouTube sends the video itself, played here in full quality"
                             else "Videos are mirrored like the rest of the screen",
                icon = Icons.Filled.PlayArrow,
                checked = videoPlaybackEnabled,
                onToggle = { onVideoPlaybackEnabledChanged(!videoPlaybackEnabled) }
            )
            SwitchRow(
                title = "H.265 video",
                supporting = "Experimental; lower bitrate if the sender agrees. Reconnect after changing",
                icon = Icons.Filled.Build,
                checked = hevcEnabled,
                onToggle = { onHevcEnabledChanged(!hevcEnabled) }
            )
            SwitchRow(
                title = "Stream stats",
                supporting = "FPS, bitrate, resolution and codec over the picture",
                icon = Icons.Filled.Info,
                checked = debugOverlayEnabled,
                onToggle = { onDebugOverlayEnabledChanged(!debugOverlayEnabled) }
            )
        }
    }

    val deviceCard = @Composable {
        SectionCard(title = "This receiver") {
            SettingRow(
                title = "Name",
                supporting = serviceName,
                icon = Icons.Filled.Edit,
                onClick = { showNameDialog = true },
                trailing = { TextButton(onClick = { showNameDialog = true }) { Text("Rename") } }
            )
            SwitchRow(
                title = "Require a code",
                supporting = if (requirePin) "A new Mac, iPhone or iPad enters a 4-digit code shown here, once"
                             else "Anyone on this network can connect",
                icon = Icons.Filled.Lock,
                checked = requirePin,
                onToggle = { onRequirePinChanged(!requirePin) }
            )
            if (requirePin && pairedSenderCount > 0) {
                SettingRow(
                    title = "Paired senders",
                    supporting = if (pairedSenderCount == 1) "1 device connects without the code"
                                 else "$pairedSenderCount devices connect without the code",
                    icon = Icons.Filled.Delete,
                    onClick = onForgetPairedSenders,
                    trailing = { TextButton(onClick = onForgetPairedSenders) { Text("Forget") } }
                )
            }
            SettingRow(
                title = "Restart receiver",
                supporting = "Try this if it does not show up on your Mac or iPhone",
                icon = Icons.Filled.Refresh,
                onClick = onRestartServer,
                trailing = { TextButton(onClick = onRestartServer) { Text("Restart") } }
            )
            SettingRow(
                title = "Report a problem",
                supporting = if (lastRunCrashed) "The app stopped unexpectedly last time. Send the details"
                             else "Diagnostics for a GitHub issue",
                icon = Icons.Filled.Warning,
                onClick = { showReportDialog = true },
                titleColor = if (lastRunCrashed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                trailing = { TextButton(onClick = { showReportDialog = true }) { Text("Report") } }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            InfoRow("Display", "${displayInfo.width} × ${displayInfo.height} @ ${displayInfo.refreshRate} Hz (${displayInfo.aspectRatioLabel})")
            InfoRow("AirPlay port", "7000")
            InfoRow("Version", if (latestReleaseVersion != null) "v$appVersion (latest v$latestReleaseVersion)" else "v$appVersion")
            Text(
                text = "On a Mac: System Settings → Displays → $serviceName → \"Show all resolutions\" " +
                    "offers ${displayInfo.recommendedResolutions}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
    }

    val connectionsCard = @Composable { modifier: Modifier ->
        SectionCard(
            title = "Recent connections",
            modifier = modifier,
            trailing = {
                Text(
                    "Last ${ConnectionHistory.MAX_ENTRIES}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        ) {
            if (recentConnections.isEmpty()) {
                Text(
                    text = "Nothing yet. Sessions from your Mac, iPhone or iPad will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            } else {
                recentConnections.forEach { entry ->
                    val details = buildList {
                        add(ConnectionHistory.formatTimestamp(entry.timestamp))
                        if (entry.resolution.isNotBlank()) add(entry.resolution)
                        if (entry.codec.isNotBlank()) add(entry.codec)
                    }.joinToString(" · ")
                    ListItem(
                        headlineContent = { Text(entry.clientName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    entry.clientName.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        },
                        trailingContent = {
                            Text(
                                ConnectionHistory.formatDuration(entry.durationSeconds),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }

    // Launchers can lay the window out at a few dp wide while animating a relaunch.
    // Material 3 ListItem then measures its text with negative constraints and throws
    // (IllegalArgumentException in ListItemMeasurePolicy), which killed the app on reopen.
    // TVs crop the picture edges (overscan): keep the dashboard inside the title-safe area.
    val tvSafeArea = if (remember(context) { ReceiverIdentity.isTvDevice(context) })
        Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier
    BoxWithConstraints(Modifier.fillMaxSize().then(tvSafeArea)) {
        if (maxWidth < MIN_DASHBOARD_WIDTH) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            return@BoxWithConstraints
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            // The window extends into the cutout (edge to edge); keep content clear of the camera.
            contentWindowInsets = WindowInsets.displayCutout,
            topBar = {
                TopAppBar(
                    title = {
                        Text("Second Display", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    },
                    actions = {
                        val releasesUrl = "https://github.com/swide-git/second-display/releases/latest"
                        val openReleases = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl))) }
                            Unit
                        }
                        if (hasNewerRelease) {
                            FilledTonalButton(onClick = openReleases) { Text("Update to v$latestReleaseVersion") }
                        } else {
                            TextButton(onClick = openReleases) { Text("v$appVersion") }
                        }
                        IconButton(onClick = onQuit, modifier = Modifier.focusRing(CircleShape)) {
                            Icon(Icons.Filled.Close, contentDescription = "Quit Second Display")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    },
                    windowInsets = WindowInsets.displayCutout,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        ) { padding ->
            val contentPadding = Modifier
                .padding(padding)
                .padding(horizontal = if (wide) 24.dp else 16.dp)
            if (wide) {
                Row(
                    modifier = contentPadding.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        statusCard()
                        pickers()
                        connectionsCard(Modifier)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        settingsCard()
                        deviceCard()
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            } else {
                Column(
                    modifier = contentPadding.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    statusCard()
                    pickers()
                    settingsCard()
                    connectionsCard(Modifier)
                    deviceCard()
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    if (showDelayDialog) {
        var draft by remember { mutableFloatStateOf(pictureDelayMs.toFloat()) }
        AlertDialog(
            onDismissRequest = { showDelayDialog = false },
            icon = { Icon(AppIcons.Timer, contentDescription = null) },
            title = { Text("Picture delay") },
            text = {
                Column {
                    Text(
                        "Bluetooth headphones and speakers play sound 100-300 ms late. Delaying the " +
                            "picture by the same amount brings them back in sync. It also delays " +
                            "the mouse on an extended display, so keep it at 0 with the built-in speaker."
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("${draft.toInt()} ms", style = MaterialTheme.typography.titleLarge)
                    Slider(
                        value = draft,
                        onValueChange = { draft = (it / 10f).roundToInt() * 10f },
                        valueRange = 0f..ReceiverServer.MAX_PICTURE_DELAY_MS.toFloat(),
                        // Applied on release, so a stream can be checked while adjusting.
                        onValueChangeFinished = { onPictureDelayChanged(draft.toInt()) }
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onPictureDelayChanged(draft.toInt()); showDelayDialog = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { draft = 0f; onPictureDelayChanged(0) }) { Text("Reset") } }
        )
    }

    if (showReportDialog) {
        // Builds the report off the main thread, then copies it and opens GitHub or the share sheet.
        fun deliver(share: Boolean) {
            showReportDialog = false
            scope.launch {
                val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ProblemReport.build(context)
                }
                val crashed = lastRunCrashed
                if (share) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Second Display problem report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, "Share the report")) }
                } else {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("Second Display report", report))
                    android.widget.Toast.makeText(context, "Diagnostics copied. Paste them into the issue", android.widget.Toast.LENGTH_LONG).show()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, ProblemReport.issueUri(context, crashed))) }
                }
                ProblemReport.clearLastCrash(context)
                lastRunCrashed = false
            }
        }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Report a problem") },
            text = {
                Text(
                    "The report holds the app version, device model, Android version, the last crash and " +
                        "this app's recent log, which includes the receiver name, sender names and network " +
                        "addresses. Nothing is sent automatically: you choose where it goes."
                )
            },
            confirmButton = { TextButton(onClick = { deliver(share = false) }) { Text("GitHub issue") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
                    TextButton(onClick = { deliver(share = true) }) { Text("Share…") }
                }
            }
        )
    }

    if (showNameDialog) {
        var tempName by remember { mutableStateOf(serviceName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            title = { Text("Receiver name") },
            text = {
                Column {
                    Text(
                        text = "The name shown in Screen Mirroring on your Mac, iPhone and iPad. " +
                            "Useful with several receivers on one network.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { if (it.length <= 60) tempName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = tempName.trim()
                        if (trimmed.isNotBlank() && trimmed != serviceName) {
                            onServiceNameChanged(trimmed)
                        }
                        showNameDialog = false
                    },
                    enabled = tempName.trim().isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (serviceName != ReceiverIdentity.DEFAULT_SERVICE_NAME) {
                        TextButton(onClick = {
                            onServiceNameChanged(ReceiverIdentity.DEFAULT_SERVICE_NAME)
                            showNameDialog = false
                        }) { Text("Reset") }
                    }
                    TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun NowPlayingScreen(
    nowPlaying: NowPlaying,
    artwork: Bitmap?,
    clientName: String?,
    debugOverlayEnabled: Boolean
) {
    val configuration = LocalConfiguration.current
    val colors = MaterialTheme.colorScheme
    val isPortraitLayout = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val isCompact = configuration.screenHeightDp <= 650 && !isPortraitLayout
    val outerPadding = if (isCompact) 28.dp else 48.dp

    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(nowPlaying.playing, nowPlaying.sampledAtMillis) {
        now = SystemClock.elapsedRealtime()
        if (nowPlaying.playing) {
            while (true) {
                delay(500L)
                now = SystemClock.elapsedRealtime()
            }
        }
    }

    val art = @Composable { modifier: Modifier ->
        val image = remember(artwork) { artwork?.asImageBitmap() }
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(32.dp))
                .background(colors.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "Album art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(text = "♪", fontSize = 96.sp, color = colors.onSecondaryContainer)
            }
        }
    }

    val details = @Composable { modifier: Modifier, centered: Boolean ->
        val textAlign = if (centered) TextAlign.Center else TextAlign.Start
        Column(
            modifier = modifier,
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
        ) {
            Text(
                text = nowPlaying.title.ifBlank { "AirPlay audio" },
                style = if (isCompact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign
            )
            if (nowPlaying.artist.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = nowPlaying.artist,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign
                )
            }
            val albumLine = listOfNotNull(
                nowPlaying.album.ifBlank { null },
                nowPlaying.year.takeIf { it > 0 }?.toString()
            ).joinToString(" · ")
            if (albumLine.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = albumLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign
                )
            }
            if (nowPlaying.durationSec > 0.0) {
                val position = nowPlaying.positionAt(now)
                Spacer(modifier = Modifier.height(if (isCompact) 16.dp else 28.dp))
                LinearProgressIndicator(
                    progress = { (position / nowPlaying.durationSec).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = NowPlaying.formatTime(position),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        text = "-" + NowPlaying.formatTime(nowPlaying.durationSec - position),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(if (isCompact) 14.dp else 24.dp))
            AssistChip(
                onClick = {},
                label = { Text("Playing from ${clientName ?: "AirPlay"}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(AppIcons.Cast, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface)))
    ) {
        if (isPortraitLayout) {
            Column(
                modifier = Modifier.fillMaxSize().padding(outerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                art(Modifier.fillMaxWidth(0.85f).aspectRatio(1f))
                Spacer(modifier = Modifier.height(36.dp))
                details(Modifier.fillMaxWidth(0.85f), true)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(outerPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                art(Modifier.fillMaxHeight(0.8f).aspectRatio(1f, matchHeightConstraintsFirst = true))
                Spacer(modifier = Modifier.width(if (isCompact) 32.dp else 56.dp))
                details(Modifier.weight(1f), false)
            }
        }
        if (debugOverlayEnabled) {
            TelemetryOverlay(
                telemetry = ReceiverServer.renderer.telemetry,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        }
    }
}

/** Stream stats over the picture; fixed dark colours because it sits on top of video. */
@Composable
private fun TelemetryOverlay(
    telemetry: StreamTelemetry,
    modifier: Modifier = Modifier
) {
    var snapshot by remember { mutableStateOf(telemetry.getSnapshot()) }

    LaunchedEffect(telemetry) {
        while (true) {
            kotlinx.coroutines.delay(500)
            snapshot = telemetry.getSnapshot()
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.wrapContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = Palette.OverlayScrim
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val codecShort = if (snapshot.codecName.isNotBlank()) {
                " (" + snapshot.codecName.removePrefix("OMX.qcom.video.decoder.").removePrefix("OMX.google.") + ")"
            } else ""
            TelemetryLine(
                tag = "VIDEO",
                active = true,
                accent = accent,
                text = String.format(
                    java.util.Locale.US,
                    "%.1f fps • %s • %s%s",
                    snapshot.fps,
                    snapshot.formattedThroughput,
                    snapshot.formattedResolution,
                    codecShort
                )
            )
            TelemetryLine(
                tag = "AUDIO",
                active = snapshot.audioActive && snapshot.audioEnabled,
                accent = accent,
                text = if (snapshot.audioEnabled) {
                    "${snapshot.audioCodec} • Vol ${snapshot.formattedAudioVolume} • ${snapshot.formattedAudioStatus}"
                } else {
                    "Disabled / Muted"
                }
            )
            TelemetryLine(
                tag = "SYNC ",
                active = false,
                accent = accent,
                text = "${snapshot.totalFramesRendered} video frames • ${snapshot.audioFramesPlayed} audio frames"
            )
        }
    }
}

@Composable
private fun TelemetryLine(tag: String, active: Boolean, accent: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(6.dp).background(if (active) accent else Palette.OverlayMuted, CircleShape))
        Text(
            text = tag,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            fontFamily = FontFamily.Monospace,
            color = if (active) accent else Palette.OverlayMuted
        )
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = Palette.OverlayText
        )
    }
}

@Composable
private fun OrientationNoticeDialog(
    notice: ReceiverServer.OrientationNotice,
    onDismiss: () -> Unit
) {
    var remainingSeconds by remember(notice.timestamp) { mutableStateOf(30) }

    LaunchedEffect(notice.timestamp) {
        remainingSeconds = 30
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        onDismiss()
    }

    val mode = if (notice.isPortrait) "portrait" else "landscape"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
        title = { Text(if (notice.resolutionChange) "Resolution changed" else "Display rotated") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (notice.resolutionChange) "The receiver now offers ${notice.width} × ${notice.height}."
                           else "The display is now $mode (${notice.width} × ${notice.height}).",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "The sender was disconnected because Apple devices do not follow geometry changes " +
                        "mid-stream. Choose this receiver in Screen Mirroring again to continue.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("OK (${remainingSeconds}s)") }
        }
    )
}

@Composable
private fun MirrorSettingRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(shape).focusRing(shape).clickable(onClick = onToggle)
    )
}

/** Opened by a long press on the mirror. Live toggles apply at once; the rest end the session. */
@Composable
private fun MirrorSettingsDialog(
    debugOverlayEnabled: Boolean,
    onDebugOverlayEnabledChanged: (Boolean) -> Unit,
    audioEnabled: Boolean,
    onAudioEnabledChanged: (Boolean) -> Unit,
    resolutionPreset: ResolutionPreset,
    onResolutionPresetChanged: (ResolutionPreset) -> Unit,
    hevcEnabled: Boolean,
    onHevcEnabledChanged: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    var resolutionMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Applies now",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                MirrorSettingRow(
                    title = "Stream stats",
                    subtitle = "FPS, bitrate, resolution and codec",
                    checked = debugOverlayEnabled,
                    onToggle = { onDebugOverlayEnabledChanged(!debugOverlayEnabled) }
                )
                MirrorSettingRow(
                    title = "Speaker",
                    subtitle = if (audioEnabled) "Sound plays on this device" else "Muted on this device",
                    checked = audioEnabled,
                    onToggle = { onAudioEnabledChanged(!audioEnabled) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Ends this session; reconnect from Screen Mirroring",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Box {
                    ListItem(
                        headlineContent = { Text("Resolution") },
                        supportingContent = { Text(ReceiverServer.resolutionLabel(resolutionPreset)) },
                        trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .focusRing(RoundedCornerShape(12.dp))
                            .clickable { resolutionMenu = true }
                    )
                    ResolutionMenu(resolutionPreset, resolutionMenu, { resolutionMenu = false }, onResolutionPresetChanged)
                }
                MirrorSettingRow(
                    title = "H.265 video",
                    subtitle = "Lower bitrate when the sender agrees to use it",
                    checked = hevcEnabled,
                    onToggle = { onHevcEnabledChanged(!hevcEnabled) }
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Disconnect") }
        }
    )
}
