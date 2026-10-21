package io.github.swiderekartmax.seconddisplay

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

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
 
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Android 15 draws edge to edge for targetSdk 35; keep the bars hidden (swipe shows them
        // transiently) and let the mirror use the cutout area too.
        enableEdgeToEdge()
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        requestNotificationPermission()

        WormholeServer.init(applicationContext)

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val fps = windowManager.defaultDisplay.mode?.refreshRate?.toInt()
            ?: windowManager.defaultDisplay.refreshRate.toInt()
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        WormholeServer.onConfigurationChanged(metrics.widthPixels, metrics.heightPixels, fps, isPortrait)

        updateOrientationMode(WormholeServer.orientationSetting.value)

        WormholeService.start(this)

        setContent {
            val mirroring by WormholeServer.isMirroring.collectAsState()
            val audioStreaming by WormholeServer.isAudioStreaming.collectAsState()
            val nowPlaying by WormholeServer.nowPlaying.collectAsState()
            val nowPlayingArt by WormholeServer.nowPlayingArt.collectAsState()
            val client by WormholeServer.clientName.collectAsState()
            val status by WormholeServer.statusText.collectAsState()
            val videoAspectRatio by WormholeServer.videoAspectRatio.collectAsState()
            val recentConnections by WormholeServer.recentConnections.collectAsState()
            val displayInfo by WormholeServer.displayInfo.collectAsState()
            val orientationSetting by WormholeServer.orientationSetting.collectAsState()
            val orientationNotice by WormholeServer.orientationNotice.collectAsState()

            LaunchedEffect(orientationSetting) {
                updateOrientationMode(orientationSetting)
            }

            var audioEnabled by remember { mutableStateOf(WormholeServer.audioEnabled) }
            var debugOverlayEnabled by remember { mutableStateOf(WormholeServer.debugOverlayEnabled) }
            var hevcEnabled by remember { mutableStateOf(WormholeServer.hevcEnabled) }
            var runInBackground by remember { mutableStateOf(WormholeServer.runInBackground) }
            var startOnBoot by remember { mutableStateOf(WormholeServer.startOnBoot) }
            var serviceName by remember { mutableStateOf(WormholeServer.identity.serviceName) }
            val resolutionPreset by WormholeServer.resolutionPreset.collectAsState()
            var showMirrorSettings by remember { mutableStateOf(false) }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Palette.Accent,
                    background = Palette.Background,
                    surface = Palette.Surface,
                    surfaceVariant = Palette.SurfaceVariant,
                    onSurface = Palette.Text,
                    onSurfaceVariant = Palette.TextMuted
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (mirroring) {
                        BackHandler { WormholeServer.disconnectClient("back") }
                        // Pinch zoom is local only; a new picture geometry starts unzoomed.
                        var zoom by remember(videoAspectRatio) { mutableStateOf(PinchZoom()) }
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
                                val w = (baseW * zoom.scale).toInt()
                                val h = (baseH * zoom.scale).toInt()
                                val placeable = measurable.measure(Constraints.fixed(w, h))
                                layout(baseW, baseH) {
                                    placeable.place(
                                        (baseW - w) / 2 + zoom.offsetX.toInt(),
                                        (baseH - h) / 2 + zoom.offsetY.toInt()
                                    )
                                }
                            }
                            AndroidView(
                                factory = { context ->
                                    SurfaceView(context).apply {
                                        holder.addCallback(object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: SurfaceHolder) {
                                                WormholeServer.setSurface(holder.surface)
                                            }
                                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                                WormholeServer.setSurface(null)
                                            }
                                        })
                                    }
                                },
                                modifier = surfaceModifier.background(Color.Black)
                            )
                            if (debugOverlayEnabled) {
                                TelemetryOverlay(
                                    telemetry = WormholeServer.renderer.telemetry,
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
                        if (showMirrorSettings) {
                            MirrorSettingsDialog(
                                debugOverlayEnabled = debugOverlayEnabled,
                                onDebugOverlayEnabledChanged = { enabled ->
                                    debugOverlayEnabled = enabled
                                    WormholeServer.setDebugOverlayEnabled(enabled)
                                },
                                audioEnabled = audioEnabled,
                                onAudioEnabledChanged = { enabled ->
                                    audioEnabled = enabled
                                    WormholeServer.setAudioEnabled(enabled)
                                },
                                resolutionPreset = resolutionPreset,
                                onResolutionPresetChanged = { WormholeServer.setResolutionPreset(it) },
                                hevcEnabled = hevcEnabled,
                                onHevcEnabledChanged = { enabled ->
                                    hevcEnabled = enabled
                                    WormholeServer.setHevcEnabled(enabled)
                                },
                                onDisconnect = {
                                    showMirrorSettings = false
                                    WormholeServer.disconnectClient("settings")
                                },
                                onDismiss = { showMirrorSettings = false }
                            )
                        }
                    } else if (audioStreaming) {
                        // Audio-only AirPlay (speaker) session: full-screen now playing, left with Back/Home like mirroring.
                        BackHandler { WormholeServer.disconnectClient("back") }
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
                                WormholeServer.updateServiceName(newName)
                                serviceName = WormholeServer.identity.serviceName
                                networkDetails = NetworkInfoHelper.getNetworkDetails(this@MainActivity)
                            },
                            status = status,
                            networkDetails = networkDetails,
                            displayInfo = displayInfo,
                            audioEnabled = audioEnabled,
                            onAudioEnabledChanged = { enabled ->
                                audioEnabled = enabled
                                WormholeServer.setAudioEnabled(enabled)
                            },
                            debugOverlayEnabled = debugOverlayEnabled,
                            hevcEnabled = hevcEnabled,
                            onHevcEnabledChanged = { enabled ->
                                hevcEnabled = enabled
                                WormholeServer.setHevcEnabled(enabled)
                            },
                            onDebugOverlayEnabledChanged = { enabled ->
                                debugOverlayEnabled = enabled
                                WormholeServer.setDebugOverlayEnabled(enabled)
                            },
                            runInBackground = runInBackground,
                            onRunInBackgroundChanged = { enabled ->
                                runInBackground = enabled
                                WormholeServer.setRunInBackground(enabled)
                                if (enabled) {
                                    WormholeService.start(this@MainActivity)
                                }
                            },
                            startOnBoot = startOnBoot,
                            onStartOnBootChanged = { enabled ->
                                startOnBoot = enabled
                                WormholeServer.setStartOnBoot(enabled)
                            },
                            canAutoOpen = canAutoOpen,
                            onRequestAutoOpen = { openOverlaySettings() },
                            onRestartServer = {
                                WormholeServer.restartServer()
                                networkDetails = NetworkInfoHelper.getNetworkDetails(this@MainActivity)
                            },
                            recentConnections = recentConnections,
                            orientationSetting = orientationSetting,
                            onOrientationSettingChanged = { WormholeServer.setOrientationSetting(it) },
                            resolutionPreset = resolutionPreset,
                            onResolutionPresetChanged = { WormholeServer.setResolutionPreset(it) }
                        )

                        orientationNotice?.let { notice ->
                            OrientationNoticeDialog(
                                notice = notice,
                                onDismiss = { WormholeServer.dismissOrientationNotice() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-applied on focus: dialogs and the transient swipe can bring the bars back.
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.apply {
            hide(android.view.WindowInsets.Type.systemBars())
            systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    override fun onResume() {
        super.onResume()
        WormholeServer.isActivityResumed = true
        networkDetails = NetworkInfoHelper.getNetworkDetails(this)
        canAutoOpen = Settings.canDrawOverlays(this)
    }

    private fun openOverlaySettings() {
        val perApp = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(perApp) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            .onFailure { android.util.Log.w("Wormhole", "No overlay permission settings screen", it) }
    }

    override fun onPause() {
        super.onPause()
        WormholeServer.isActivityResumed = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val fps = windowManager.defaultDisplay.mode?.refreshRate?.toInt()
            ?: windowManager.defaultDisplay.refreshRate.toInt()
        val isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        WormholeServer.onConfigurationChanged(metrics.widthPixels, metrics.heightPixels, fps, isPortrait)
    }

    private fun updateOrientationMode(setting: ScreenOrientation) {
        val effectiveSetting = if (!WormholeIdentity.hasOrientationSensor(this) && setting == ScreenOrientation.AUTO) {
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

    // Home is a deliberate exit; not called when the service brings this activity forward.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        WormholeServer.disconnectClient("home")
    }

    override fun onDestroy() {
        WormholeServer.setSurface(null)
        if (!WormholeServer.runInBackground) {
            WormholeService.stop(this)
            WormholeServer.stopServer()
        }
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}

@Composable
private fun Modifier.tvFocusable(
    shape: Shape = RoundedCornerShape(8.dp),
    focusedBorderColor: Color = Palette.Accent,
    focusedBackgroundColor: Color = Palette.BorderStrong,
    unfocusedBackgroundColor: Color = Color.Transparent,
    unfocusedBorderColor: Color = Color.Transparent,
    borderWidth: Dp = 2.dp,
    onClick: (() -> Unit)? = null
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { isFocused = it.isFocused }
        .then(
            if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier.focusable()
            }
        )
        .background(if (isFocused) focusedBackgroundColor else unfocusedBackgroundColor, shape)
        .border(
            borderWidth,
            if (isFocused) focusedBorderColor else unfocusedBorderColor,
            shape
        )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    fontSize: TextUnit = 14.sp,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = fontSize, color = Palette.TextMuted)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = value,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = Palette.TextSecondary,
            maxLines = 1,
            softWrap = false
        )
    }
}

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
    onResolutionPresetChanged: (ResolutionPreset) -> Unit
) {
    var showNameDialog by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
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
    val isAutoSupported = remember(context) { WormholeIdentity.hasOrientationSensor(context) }
    val availableOrientations = remember(isAutoSupported) {
        if (isAutoSupported) {
            ScreenOrientation.values().toList()
        } else {
            listOf(ScreenOrientation.LANDSCAPE, ScreenOrientation.PORTRAIT)
        }
    }
    val screenHeightDp = configuration.screenHeightDp
    val isTvOrCompact = screenHeightDp <= 650
    val isCompactScreen = displayInfo.height <= 900 || screenHeightDp <= 800

    val horizontalPadding = if (isTvOrCompact) 20.dp else if (isCompactScreen) 28.dp else 48.dp
    val verticalPadding = if (isTvOrCompact) 8.dp else if (isCompactScreen) 16.dp else 36.dp
    val cardSpacing = if (isTvOrCompact) 5.dp else if (isCompactScreen) 10.dp else 20.dp
    val cardPadding = if (isTvOrCompact) 6.dp else if (isCompactScreen) 14.dp else 20.dp
    val dividerSpacing = if (isTvOrCompact) 1.dp else if (isCompactScreen) 8.dp else 12.dp
    val columnSpacing = if (isTvOrCompact) 14.dp else if (isCompactScreen) 20.dp else 28.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The window extends into the cutout (edge to edge); keep text clear of the camera.
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Second Display",
                        fontSize = if (isTvOrCompact) 20.sp else if (isCompactScreen) 26.sp else 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Palette.SurfaceVariant,
                        border = BorderStroke(1.dp, Palette.Border),
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/swide-git/second-display/releases/latest")
                                    )
                                )
                            }
                        }
                    ) {
                        Text(
                            text = if (hasNewerRelease) "v$appVersion → v$latestReleaseVersion" else "v$appVersion",
                            fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = if (hasNewerRelease) Palette.Warning else Palette.TextMuted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(if (isTvOrCompact) 2.dp else if (isCompactScreen) 3.dp else 6.dp))
                Text(
                    text = "Mac / iPhone (Screen Mirroring) or Windows (Win + K) → $serviceName",
                    fontSize = if (isTvOrCompact) 12.sp else if (isCompactScreen) 14.sp else 18.sp,
                    color = Palette.TextSecondary
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Palette.AccentContainerDark,
                border = BorderStroke(1.dp, Palette.AccentContainer)
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = if (isTvOrCompact) 10.dp else 14.dp,
                        vertical = if (isTvOrCompact) 4.dp else 8.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isTvOrCompact) 6.dp else 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isTvOrCompact) 8.dp else 10.dp)
                            .background(Palette.Accent, shape = CircleShape)
                    )
                    Text(
                        text = if (status.startsWith("Visible")) "Ready to Connect" else status,
                        fontSize = if (isTvOrCompact) 12.sp else 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.Accent
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isTvOrCompact) 4.dp else if (isCompactScreen) 12.dp else 28.dp))

        val isPortraitLayout = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val controlsCard = @Composable {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, Palette.Border)
            ) {
                Column(modifier = Modifier.padding(cardPadding)) {
                    Text(
                        text = "Controls & Playback",
                        fontSize = if (isTvOrCompact) 13.sp else if (isCompactScreen) 16.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Text
                    )
                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 2.dp else if (isCompactScreen) 8.dp else 14.dp))

                    // Row 0: Screen Orientation
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Screen Orientation",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextSecondary
                            )
                            Text(
                                text = when (orientationSetting) {
                                    ScreenOrientation.LANDSCAPE -> if (!isAutoSupported) "Landscape (wide)" else "Forced landscape (wide)"
                                    ScreenOrientation.PORTRAIT -> if (!isAutoSupported) "Portrait (tall)" else "Forced portrait (tall)"
                                    ScreenOrientation.AUTO -> "Auto-detect from tilt sensor"
                                },
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Palette.TextMuted
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            availableOrientations.forEach { option ->
                                val isSelected = orientationSetting == option
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) Palette.AccentContainer else Palette.Chip,
                                    border = BorderStroke(1.dp, if (isSelected) Palette.Accent else Palette.Border),
                                    modifier = Modifier
                                        .tvFocusable(
                                            shape = RoundedCornerShape(6.dp),
                                            onClick = { onOrientationSettingChanged(option) }
                                        )
                                        .clickable { onOrientationSettingChanged(option) }
                                ) {
                                    Text(
                                        text = option.label,
                                        fontSize = if (isTvOrCompact) 9.sp else 11.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) Palette.Accent else Palette.TextMuted,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Palette.Border, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 0b: Preferred Resolution
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Preferred Resolution",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextSecondary
                            )
                            Text(
                                text = "Size offered to the sender; lower is lighter on Wi-Fi",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Palette.TextMuted
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        ResolutionPicker(
                            selected = resolutionPreset,
                            onSelected = onResolutionPresetChanged,
                            fontSize = if (isTvOrCompact) 9.sp else 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Palette.Border, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 1: Speaker Output
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { onAudioEnabledChanged(!audioEnabled) }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Speaker Output",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextSecondary
                            )
                            Text(
                                text = if (audioEnabled) "Play sound through device speakers" else "Device speakers muted",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Palette.TextMuted
                            )
                        }
                        Switch(
                            checked = audioEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Palette.Background,
                                checkedTrackColor = Palette.Accent,
                                uncheckedThumbColor = Palette.TextMuted,
                                uncheckedTrackColor = Palette.SurfaceVariant
                            ),
                            modifier = if (isTvOrCompact) Modifier.scale(0.8f) else Modifier
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Palette.Border, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 2: H.265 / HEVC Video (Experimental)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { onHevcEnabledChanged(!hevcEnabled) }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "H.265 video (Experimental)",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextSecondary
                            )
                            Text(
                                text = "Allow compatible senders to use H.265. Reconnect after changing. H.264 remains available.",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Palette.TextMuted
                            )
                        }
                        Switch(
                            checked = hevcEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Palette.Background,
                                checkedTrackColor = Palette.Accent,
                                uncheckedThumbColor = Palette.TextMuted,
                                uncheckedTrackColor = Palette.SurfaceVariant
                            ),
                            modifier = if (isTvOrCompact) Modifier.scale(0.8f) else Modifier
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Palette.Border, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 2b: Telemetry Overlay (Debug)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { onDebugOverlayEnabledChanged(!debugOverlayEnabled) }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Telemetry Overlay (Debug)",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextSecondary
                            )
                            Text(
                                text = "Display real-time stream stats (FPS, bitrate, resolution, codec) in top-left corner",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Palette.TextMuted
                            )
                        }
                        Switch(
                            checked = debugOverlayEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Palette.Background,
                                checkedTrackColor = Palette.Accent,
                                uncheckedThumbColor = Palette.TextMuted,
                                uncheckedTrackColor = Palette.SurfaceVariant
                            ),
                            modifier = if (isTvOrCompact) Modifier.scale(0.8f) else Modifier
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Palette.Border, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 3: Run in Background
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { onRunInBackgroundChanged(!runInBackground) }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Run in Background",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextSecondary
                            )
                            Text(
                                text = "Keep receiver discoverable when app is closed or minimized",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Palette.TextMuted
                            )
                        }
                        Switch(
                            checked = runInBackground,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Palette.Background,
                                checkedTrackColor = Palette.Accent,
                                uncheckedThumbColor = Palette.TextMuted,
                                uncheckedTrackColor = Palette.SurfaceVariant
                            ),
                            modifier = if (isTvOrCompact) Modifier.scale(0.8f) else Modifier
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Palette.Border, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 3b: Auto-open on connect
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = if (!canAutoOpen) onRequestAutoOpen else null
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-open on connect",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (canAutoOpen) Palette.TextSecondary else Palette.Warning
                            )
                            Text(
                                text = if (canAutoOpen)
                                    "App brings itself to front when a device starts streaming"
                                else
                                    "Allow \"Display over other apps\" so the receiver can show the stream automatically",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Palette.TextMuted
                            )
                        }
                        if (!canAutoOpen) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Palette.Warning),
                                color = Palette.WarningContainer,
                                modifier = Modifier
                                    .clickable { onRequestAutoOpen() }
                                    .padding(start = 8.dp)
                            ) {
                                Text(
                                    text = "Allow",
                                    fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Palette.Warning,
                                    modifier = Modifier.padding(
                                        horizontal = if (isTvOrCompact) 8.dp else 12.dp,
                                        vertical = if (isTvOrCompact) 3.dp else 6.dp
                                    )
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Palette.AccentContainerDark,
                                border = BorderStroke(1.dp, Palette.AccentContainer),
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = "Enabled",
                                    fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Palette.Accent,
                                    modifier = Modifier.padding(
                                        horizontal = if (isTvOrCompact) 8.dp else 12.dp,
                                        vertical = if (isTvOrCompact) 3.dp else 6.dp
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Palette.Border, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 4: Start on Boot
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { onStartOnBootChanged(!startOnBoot) }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start on Boot",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextSecondary
                            )
                            Text(
                                text = if (isTvOrCompact) "Start receiver when device powers on" else "Automatically start receiver when device powers on",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Palette.TextMuted
                            )
                        }
                        Switch(
                            checked = startOnBoot,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Palette.Background,
                                checkedTrackColor = Palette.Accent,
                                uncheckedThumbColor = Palette.TextMuted,
                                uncheckedTrackColor = Palette.SurfaceVariant
                            ),
                            modifier = if (isTvOrCompact) Modifier.scale(0.8f) else Modifier
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Palette.Border, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 5: Bonjour Service Name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { showNameDialog = true }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bonjour Service Name",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextSecondary
                            )
                            Text(
                                text = "Advertised in Screen Mirroring as: \"$serviceName\"",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Palette.TextMuted
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Palette.Accent),
                            color = Palette.AccentContainerDark
                        ) {
                            Text(
                                text = "Rename",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.Accent,
                                modifier = Modifier.padding(
                                    horizontal = if (isTvOrCompact) 8.dp else 12.dp,
                                    vertical = if (isTvOrCompact) 3.dp else 6.dp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Palette.Border, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 6: Receiver Service
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = onRestartServer
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Receiver Service",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextSecondary
                            )
                            Text(
                                text = "Restart if receiver does not appear on your device",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Palette.TextMuted
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Palette.AccentContainer),
                            color = Palette.Chip
                        ) {
                            Text(
                                text = "Restart",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextMuted,
                                modifier = Modifier.padding(
                                    horizontal = if (isTvOrCompact) 8.dp else 12.dp,
                                    vertical = if (isTvOrCompact) 3.dp else 6.dp
                                )
                            )
                        }
                    }
                }
            }
        }

        val networkCard = @Composable {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusable(
                        shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                        unfocusedBorderColor = Palette.Border,
                        borderWidth = 1.dp
                    ),
                shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(cardPadding)) {
                    Text(
                        text = "Network & Diagnostics",
                        fontSize = if (isTvOrCompact) 12.sp else if (isCompactScreen) 16.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Text
                    )
                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 2.dp else if (isCompactScreen) 8.dp else 14.dp))
                    val versionSummary = remember(appVersion, latestReleaseVersion) {
                        if (latestReleaseVersion != null) {
                            "v$appVersion (Latest: v$latestReleaseVersion)"
                        } else {
                            "v$appVersion"
                        }
                    }
                    if (isTvOrCompact) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                InfoRow(label = "Wi-Fi", value = networkDetails.wifiSsid, fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(1.dp))
                                InfoRow(label = "Services", value = "$serviceName (7000/7250)", fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(1.dp))
                                InfoRow(label = "Version", value = versionSummary, fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                InfoRow(label = "Device IP", value = networkDetails.ipAddress, fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(1.dp))
                                InfoRow(label = "Display", value = "${displayInfo.width}×${displayInfo.height}@${displayInfo.refreshRate}Hz", fontSize = 11.sp)
                            }
                        }
                    } else {
                        InfoRow(label = "Wi-Fi Network", value = networkDetails.wifiSsid)
                        Spacer(modifier = Modifier.height(if (isCompactScreen) 4.dp else 8.dp))
                        InfoRow(label = "Device IP", value = networkDetails.ipAddress)
                        Spacer(modifier = Modifier.height(if (isCompactScreen) 4.dp else 8.dp))
                        InfoRow(label = "Receiver Services", value = "$serviceName (AirPlay 7000 • Miracast 7250)")
                        Spacer(modifier = Modifier.height(if (isCompactScreen) 4.dp else 8.dp))
                        InfoRow(label = "Display", value = "${displayInfo.width} × ${displayInfo.height} @ ${displayInfo.refreshRate}Hz (Auto-fit)")
                        Spacer(modifier = Modifier.height(if (isCompactScreen) 4.dp else 8.dp))
                        InfoRow(label = "App Version", value = versionSummary)
                    }
                }
            }
        }

        val resolutionsCard = @Composable {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusable(
                        shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                        unfocusedBorderColor = Palette.Border,
                        borderWidth = 1.dp
                    ),
                shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(cardPadding)) {
                    Text(
                        text = "Preferred Resolutions (${displayInfo.aspectRatioLabel})",
                        fontSize = if (isTvOrCompact) 12.sp else if (isCompactScreen) 16.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Text
                    )
                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 2.dp else if (isCompactScreen) 6.dp else 12.dp))
                    Text(
                        text = displayInfo.recommendedResolutions,
                        fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = Palette.Accent
                    )
                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 1.dp else if (isCompactScreen) 4.dp else 8.dp))
                    Text(
                        text = if (isTvOrCompact)
                            "In macOS Settings → Displays, select $serviceName and toggle \"Show all resolutions\"."
                        else
                            "In macOS System Settings → Displays, select $serviceName and toggle \"Show all resolutions\" to pick from these ${displayInfo.aspectRatioLabel} modes (both Extended & Mirroring supported).",
                        fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                        color = Palette.TextMuted,
                        lineHeight = if (isTvOrCompact) 12.sp else 16.sp
                    )
                }
            }
        }

        val connectionsCard = @Composable { isFillMaxHeight: Boolean, modifier: Modifier ->
            Card(
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, Palette.Border)
            ) {
                val contentModifier = if (isFillMaxHeight) {
                    Modifier
                        .padding(if (isTvOrCompact) 10.dp else if (isCompactScreen) 16.dp else 20.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                        .padding(if (isTvOrCompact) 10.dp else if (isCompactScreen) 16.dp else 20.dp)
                        .fillMaxWidth()
                }
                Column(modifier = contentModifier) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Connections",
                            fontSize = if (isTvOrCompact) 14.sp else 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Palette.Text
                        )
                        Text(
                            text = "Last ${ConnectionHistory.MAX_ENTRIES} sessions",
                            fontSize = if (isTvOrCompact) 11.sp else 13.sp,
                            color = Palette.TextMuted
                        )
                    }

                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 6.dp else 16.dp))

                    if (recentConnections.isEmpty()) {
                        val boxModifier = if (isFillMaxHeight) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        Box(
                            modifier = boxModifier,
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No recent connections",
                                    fontSize = if (isTvOrCompact) 13.sp else 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Palette.TextMuted
                                )
                                Spacer(modifier = Modifier.height(if (isTvOrCompact) 3.dp else 6.dp))
                                Text(
                                    text = "Mirrored sessions from iPhone, iPad, or Mac will appear here.",
                                    fontSize = if (isTvOrCompact) 11.sp else 13.sp,
                                    color = Palette.TextFaint
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(if (isTvOrCompact) 4.dp else 10.dp)
                        ) {
                            recentConnections.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusable(
                                            shape = RoundedCornerShape(if (isTvOrCompact) 8.dp else 10.dp),
                                            unfocusedBackgroundColor = Palette.SurfaceVariant
                                        )
                                        .padding(
                                            horizontal = if (isTvOrCompact) 10.dp else 16.dp,
                                            vertical = if (isTvOrCompact) 4.dp else 12.dp
                                        ),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.clientName,
                                            fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Palette.Text
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val details = buildList {
                                            add(ConnectionHistory.formatTimestamp(entry.timestamp))
                                            if (entry.resolution.isNotBlank()) add(entry.resolution)
                                            if (entry.codec.isNotBlank()) add(entry.codec)
                                        }.joinToString(" • ")
                                        Text(
                                            text = details,
                                            fontSize = if (isTvOrCompact) 10.sp else 13.sp,
                                            color = Palette.TextMuted
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Palette.Surface
                                    ) {
                                        Text(
                                            text = ConnectionHistory.formatDuration(entry.durationSeconds),
                                            fontSize = if (isTvOrCompact) 10.sp else 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Palette.Accent,
                                            modifier = Modifier.padding(
                                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                                vertical = if (isTvOrCompact) 3.dp else 5.dp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isPortraitLayout) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(cardSpacing)
            ) {
                controlsCard()
                networkCard()
                resolutionsCard()
                connectionsCard(false, Modifier)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(columnSpacing)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(cardSpacing)
                ) {
                    controlsCard()
                    networkCard()
                    resolutionsCard()
                }
                connectionsCard(true, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }

    if (showNameDialog) {
        var tempName by remember { mutableStateOf(serviceName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = Palette.Surface,
            title = {
                Text(
                    text = "Change Bonjour Name",
                    fontSize = if (isTvOrCompact) 16.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.Text
                )
            },
            text = {
                Column {
                    Text(
                        text = "Customize the name shown in Screen Mirroring on macOS and iOS. Useful if you have multiple displays on the same Wi-Fi network.",
                        fontSize = if (isTvOrCompact) 12.sp else 14.sp,
                        color = Palette.TextMuted,
                        lineHeight = if (isTvOrCompact) 16.sp else 20.sp
                    )
                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 10.dp else 16.dp))
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { if (it.length <= 60) tempName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Palette.Text,
                            unfocusedTextColor = Palette.TextSecondary,
                            focusedBorderColor = Palette.Accent,
                            unfocusedBorderColor = Palette.Border,
                            focusedLabelColor = Palette.Accent,
                            unfocusedLabelColor = Palette.TextMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val saveInteraction = remember { MutableInteractionSource() }
                val isSaveFocused by saveInteraction.collectIsFocusedAsState()
                Button(
                    onClick = {
                        val trimmed = tempName.trim()
                        if (trimmed.isNotBlank() && trimmed != serviceName) {
                            onServiceNameChanged(trimmed)
                        }
                        showNameDialog = false
                    },
                    interactionSource = saveInteraction,
                    enabled = tempName.trim().isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSaveFocused) Palette.AccentFocused else Palette.Accent,
                        contentColor = Palette.Background
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.border(
                        if (isSaveFocused) 2.dp else 0.dp,
                        Color.White,
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Text("Save & Apply")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (serviceName != WormholeIdentity.DEFAULT_SERVICE_NAME) {
                        val resetInteraction = remember { MutableInteractionSource() }
                        val isResetFocused by resetInteraction.collectIsFocusedAsState()
                        TextButton(
                            onClick = {
                                onServiceNameChanged(WormholeIdentity.DEFAULT_SERVICE_NAME)
                                showNameDialog = false
                            },
                            interactionSource = resetInteraction,
                            modifier = Modifier
                                .background(
                                    if (isResetFocused) Palette.BorderStrong else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    if (isResetFocused) 1.5.dp else 0.dp,
                                    Palette.Accent,
                                    RoundedCornerShape(8.dp)
                                )
                        ) {
                            Text("Reset", color = if (isResetFocused) Color.White else Palette.TextMuted)
                        }
                    }
                    val cancelInteraction = remember { MutableInteractionSource() }
                    val isCancelFocused by cancelInteraction.collectIsFocusedAsState()
                    TextButton(
                        onClick = { showNameDialog = false },
                        interactionSource = cancelInteraction,
                        modifier = Modifier
                            .background(
                                if (isCancelFocused) Palette.BorderStrong else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                if (isCancelFocused) 1.5.dp else 0.dp,
                                Palette.Accent,
                                RoundedCornerShape(8.dp)
                            )
                    ) {
                        Text("Cancel", color = if (isCancelFocused) Color.White else Palette.TextMuted)
                    }
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
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.SurfaceVariant),
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
                Text(text = "♪", fontSize = 96.sp, color = Palette.Accent)
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
                fontSize = if (isCompact) 26.sp else 34.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
                lineHeight = if (isCompact) 30.sp else 40.sp
            )
            if (nowPlaying.artist.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = nowPlaying.artist,
                    fontSize = if (isCompact) 18.sp else 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.Accent,
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
                    fontSize = if (isCompact) 14.sp else 17.sp,
                    color = Palette.TextMuted,
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
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Palette.Accent,
                    trackColor = Palette.Border
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = NowPlaying.formatTime(position),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Palette.TextMuted
                    )
                    Text(
                        text = "-" + NowPlaying.formatTime(nowPlaying.durationSec - position),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Palette.TextMuted
                    )
                }
            }
            Spacer(modifier = Modifier.height(if (isCompact) 14.dp else 24.dp))
            Text(
                text = "Playing from ${clientName ?: "AirPlay"}",
                fontSize = if (isCompact) 12.sp else 14.sp,
                color = Palette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.Surface, Palette.Background)))
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
                telemetry = WormholeServer.renderer.telemetry,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        }
    }
}

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

    Surface(
        modifier = modifier.wrapContentSize(),
        shape = RoundedCornerShape(8.dp),
        color = Palette.BackgroundScrim,
        border = BorderStroke(1.dp, Palette.AccentTranslucent)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Video row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Palette.Accent, shape = CircleShape)
                )
                Text(
                    text = "VIDEO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.Accent
                )
                val codecShort = if (snapshot.codecName.isNotBlank()) {
                    " (" + snapshot.codecName.removePrefix("OMX.qcom.video.decoder.").removePrefix("OMX.google.") + ")"
                } else ""
                Text(
                    text = String.format(
                        java.util.Locale.US,
                        "%.1f fps • %s • %s%s",
                        snapshot.fps,
                        snapshot.formattedThroughput,
                        snapshot.formattedResolution,
                        codecShort
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.Text
                )
            }

            // Audio row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val audioActive = snapshot.audioActive && snapshot.audioEnabled
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (audioActive) Palette.Accent else Palette.TextFaint, shape = CircleShape)
                )
                Text(
                    text = "AUDIO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (audioActive) Palette.Accent else Palette.TextMuted
                )
                val audioText = if (snapshot.audioEnabled) {
                    "${snapshot.audioCodec} • Vol ${snapshot.formattedAudioVolume} • ${snapshot.formattedAudioStatus}"
                } else {
                    "Disabled / Muted"
                }
                Text(
                    text = audioText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.TextSecondary
                )
            }

            // Frames row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.size(6.dp))
                Text(
                    text = "SYNC ",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.TextFaint
                )
                Text(
                    text = "${snapshot.totalFramesRendered} video frames • ${snapshot.audioFramesPlayed} audio frames",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.TextMuted
                )
            }
        }
    }
}

@Composable
private fun OrientationNoticeDialog(
    notice: WormholeServer.OrientationNotice,
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

    val mode = if (notice.isPortrait) "Portrait" else "Landscape"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = if (notice.resolutionChange) "Resolution Changed" else "Display Rotated",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Text
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (notice.resolutionChange) "Advertised resolution changed to ${notice.width} × ${notice.height}."
                           else "Display orientation changed to $mode (${notice.width} × ${notice.height}).",
                    fontSize = 14.sp,
                    color = Palette.Text,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "AirPlay streaming was disconnected because Apple devices do not adapt to geometry changes mid-stream. Reconnect from Screen Mirroring on your Mac or iPhone to resume.",
                    fontSize = 13.sp,
                    color = Palette.TextMuted,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            val okInteraction = remember { MutableInteractionSource() }
            val isOkFocused by okInteraction.collectIsFocusedAsState()
            Button(
                onClick = onDismiss,
                interactionSource = okInteraction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOkFocused) Palette.AccentFocused else Palette.Accent,
                    contentColor = Palette.Background
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.border(
                    if (isOkFocused) 2.dp else 0.dp,
                    Color.White,
                    RoundedCornerShape(10.dp)
                )
            ) {
                Text("OK (${remainingSeconds}s)", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}


@Composable
private fun ResolutionPicker(selected: ResolutionPreset, onSelected: (ResolutionPreset) -> Unit, fontSize: TextUnit) {
    var expanded by remember { mutableStateOf(false) }
    val available = WormholeServer.availableResolutionPresets()
    // A preset that does not fit this panel is not applied; show what is actually advertised.
    val effective = if (selected in available) selected else ResolutionPreset.NATIVE
    Box {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Palette.AccentContainer,
            border = BorderStroke(1.dp, Palette.Accent),
            modifier = Modifier
                .tvFocusable(shape = RoundedCornerShape(6.dp), onClick = { expanded = true })
                .clickable { expanded = true }
        ) {
            Text(
                text = "${WormholeServer.resolutionLabel(effective)}  ▾",
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Accent,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Palette.Surface
        ) {
            available.forEach { preset ->
                val isSelected = preset == effective
                DropdownMenuItem(
                    text = {
                        Text(
                            text = WormholeServer.resolutionLabel(preset),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) Palette.Accent else Palette.TextSecondary
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(preset)
                    }
                )
            }
        }
    }
}

@Composable
private fun MirrorSettingRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(8.dp), onClick = onToggle)
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.TextSecondary)
            Text(text = subtitle, fontSize = 12.sp, color = Palette.TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Background,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.TextMuted,
                uncheckedTrackColor = Palette.SurfaceVariant
            )
        )
    }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(text = "Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Applies now", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
                MirrorSettingRow(
                    title = "Stream Stats",
                    subtitle = "FPS, bitrate, resolution and codec overlay",
                    checked = debugOverlayEnabled,
                    onToggle = { onDebugOverlayEnabledChanged(!debugOverlayEnabled) }
                )
                MirrorSettingRow(
                    title = "Speaker Output",
                    subtitle = if (audioEnabled) "Play sound through device speakers" else "Device speakers muted",
                    checked = audioEnabled,
                    onToggle = { onAudioEnabledChanged(!audioEnabled) }
                )
                HorizontalDivider(color = Palette.Border, thickness = 0.5.dp)
                Text(
                    text = "Ends this session; reconnect from Screen Mirroring",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.WarningBright
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "Preferred Resolution", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.TextSecondary)
                    Text(
                        text = "Size offered to the sender; lower is lighter on Wi-Fi",
                        fontSize = 12.sp,
                        color = Palette.TextMuted
                    )
                    ResolutionPicker(selected = resolutionPreset, onSelected = onResolutionPresetChanged, fontSize = 12.sp)
                }
                MirrorSettingRow(
                    title = "H.265 / HEVC Video",
                    subtitle = "Lower bitrate when the sender agrees to use it",
                    checked = hevcEnabled,
                    onToggle = { onHevcEnabledChanged(!hevcEnabled) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.Background)
            ) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onDisconnect) { Text("Disconnect", color = Palette.Error) }
        }
    )
}
