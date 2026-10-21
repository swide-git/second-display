@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
        // Android 15+ draws edge to edge (targetSdk 35+); keep the bars hidden (swipe shows them
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

            SecondDisplayTheme {
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
    val available = WormholeServer.availableResolutionPresets()
    // A preset that does not fit this panel is not applied; show what is actually advertised.
    val effective = if (selected in available) selected else ResolutionPreset.NATIVE
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        available.forEach { preset ->
            DropdownMenuItem(
                text = { Text(WormholeServer.resolutionLabel(preset)) },
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
    val ready = status.startsWith("Visible")
    val wide = configuration.screenWidthDp >= 840 ||
        (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && configuration.screenWidthDp >= 600)

    val statusCard = @Composable {
        StatusCard(ready = ready, status = status, serviceName = serviceName, networkDetails = networkDetails)
    }

    val pickers = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            val available = WormholeServer.availableResolutionPresets()
            val effective = if (resolutionPreset in available) resolutionPreset else ResolutionPreset.NATIVE
            PickerTile(
                label = "Resolution",
                value = WormholeServer.resolutionLabel(effective),
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
            SettingRow(
                title = "Restart receiver",
                supporting = "Try this if it does not show up on your Mac or iPhone",
                icon = Icons.Filled.Refresh,
                onClick = onRestartServer,
                trailing = { TextButton(onClick = onRestartServer) { Text("Restart") } }
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
                    Spacer(modifier = Modifier.width(8.dp))
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
                    if (serviceName != WormholeIdentity.DEFAULT_SERVICE_NAME) {
                        TextButton(onClick = {
                            onServiceNameChanged(WormholeIdentity.DEFAULT_SERVICE_NAME)
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
                telemetry = WormholeServer.renderer.telemetry,
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
                        supportingContent = { Text(WormholeServer.resolutionLabel(resolutionPreset)) },
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
