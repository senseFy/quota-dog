package saien.quotadog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.AWTException
import java.awt.BasicStroke
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.awt.Color as AwtColor

internal const val TRAY_PANEL_WIDTH = 420
internal const val TRAY_PANEL_HEIGHT = 580
internal const val STATUS_BAR_ACCOUNT_LIMIT = 4

private const val TRAY_PANEL_MARGIN = 8

internal data class DesktopStatusBarState(
    val tooltip: String,
    val summary: String,
    val accounts: List<DesktopStatusBarAccount>,
    val moreAccounts: Int,
    val refreshEnabled: Boolean,
    val windowVisible: Boolean,
)

internal data class DesktopStatusBarAccount(
    val title: String,
    val status: String,
    val provider: String,
    val busy: Boolean,
    val windows: List<DesktopStatusBarUsageWindow>,
)

internal data class DesktopStatusBarUsageWindow(
    val label: String,
    val usedPct: Int,
    val remainingPct: Int,
    val resetLabel: String,
)

@Composable
internal fun DesktopStatusBarIcon(
    state: DesktopStatusBarState,
    onRefresh: () -> Unit,
    onOpenWindow: () -> Unit,
    onHideWindow: () -> Unit,
    onQuit: () -> Unit,
    onFallbackClick: (x: Int, y: Int) -> Unit,
    onAvailabilityChanged: (Boolean) -> Unit,
) {
    val currentState = rememberUpdatedState(state)
    val currentOnRefresh = rememberUpdatedState(onRefresh)
    val currentOnOpenWindow = rememberUpdatedState(onOpenWindow)
    val currentOnHideWindow = rememberUpdatedState(onHideWindow)
    val currentOnQuit = rememberUpdatedState(onQuit)
    val currentOnFallbackClick = rememberUpdatedState(onFallbackClick)
    val currentOnAvailabilityChanged = rememberUpdatedState(onAvailabilityChanged)
    val callbacks = remember {
        StatusBarCallbacks(
            refresh = { currentOnRefresh.value() },
            openHide = {
                if (currentState.value.windowVisible) {
                    currentOnHideWindow.value()
                } else {
                    currentOnOpenWindow.value()
                }
            },
            quit = { currentOnQuit.value() },
            fallbackClick = { x, y -> currentOnFallbackClick.value(x, y) },
        )
    }
    val handle = remember {
        createStatusBarIcon(currentState.value, callbacks)
    }

    LaunchedEffect(handle, currentState.value) {
        handle?.update(currentState.value)
    }

    LaunchedEffect(handle) {
        currentOnAvailabilityChanged.value(handle != null)
    }

    DisposableEffect(handle) {
        onDispose {
            handle?.dispose()
            currentOnAvailabilityChanged.value(false)
        }
    }
}

internal fun statusBarPanelPosition(x: Int, y: Int): WindowPosition {
    val pointer = MouseInfo.getPointerInfo()?.location
    val anchorX = if (x == 0) pointer?.x ?: x else x
    val anchorY = if (y == 0) pointer?.y ?: y else y
    val bounds = usableScreenBoundsFor(anchorX, anchorY)

    val desiredX = anchorX - TRAY_PANEL_WIDTH + 28
    val desiredY = if (anchorY < bounds.centerY) {
        anchorY + 12
    } else {
        anchorY - TRAY_PANEL_HEIGHT - 12
    }
    val minX = bounds.x + TRAY_PANEL_MARGIN
    val maxX = bounds.x + bounds.width - TRAY_PANEL_WIDTH - TRAY_PANEL_MARGIN
    val minY = bounds.y + TRAY_PANEL_MARGIN
    val maxY = bounds.y + bounds.height - TRAY_PANEL_HEIGHT - TRAY_PANEL_MARGIN

    return WindowPosition.Absolute(
        desiredX.clampTo(minX, maxX).dp,
        desiredY.clampTo(minY, maxY).dp,
    )
}

private data class StatusBarCallbacks(
    val refresh: () -> Unit,
    val openHide: () -> Unit,
    val quit: () -> Unit,
    val fallbackClick: (Int, Int) -> Unit,
)

private fun createStatusBarIcon(
    state: DesktopStatusBarState,
    callbacks: StatusBarCallbacks,
): StatusBarHandle? {
    if (isMacOs()) {
        return MacStatusBarIcon.create(state, callbacks)
    }
    return AwtStatusBarIcon.create(state, callbacks.fallbackClick)
}

private interface StatusBarHandle {
    fun update(state: DesktopStatusBarState)
    fun dispose()
}

private class AwtStatusBarIcon private constructor(
    private val tray: SystemTray,
    private val icon: TrayIcon,
    private val listener: MouseAdapter,
) : StatusBarHandle {
    override fun update(state: DesktopStatusBarState) {
        icon.toolTip = state.tooltip
    }

    override fun dispose() {
        icon.removeMouseListener(listener)
        tray.remove(icon)
    }

    companion object {
        fun create(state: DesktopStatusBarState, onClick: (Int, Int) -> Unit): AwtStatusBarIcon? {
            if (!SystemTray.isSupported()) return null
            val tray = SystemTray.getSystemTray()
            val icon = TrayIcon(createAwtTrayImage(), state.tooltip).apply {
                isImageAutoSize = true
            }
            val listener = object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) {
                        onClick(event.xOnScreen, event.yOnScreen)
                    }
                }
            }
            icon.addMouseListener(listener)
            return try {
                tray.add(icon)
                AwtStatusBarIcon(tray, icon, listener)
            } catch (_: AWTException) {
                icon.removeMouseListener(listener)
                null
            }
        }
    }
}

private class MacStatusBarIcon private constructor(
    private val native: MacStatusBarNative,
    private val handle: Pointer,
    private val refreshCallback: NativeActionCallback,
    private val openHideCallback: NativeActionCallback,
    private val quitCallback: NativeActionCallback,
) : StatusBarHandle {
    override fun update(state: DesktopStatusBarState) {
        runCatching {
            native.qd_statusbar_update(handle, state.toJson())
        }.onFailure {
            logStatusBarError("update macOS status item", it)
        }
    }

    override fun dispose() {
        runCatching {
            native.qd_statusbar_dispose(handle)
        }.onFailure {
            logStatusBarError("dispose macOS status item", it)
        }
    }

    companion object {
        fun create(state: DesktopStatusBarState, callbacks: StatusBarCallbacks): MacStatusBarIcon? {
            return runCatching {
                val native = loadMacStatusBarNative()
                val refreshCallback = NativeActionCallback {
                    EventQueue.invokeLater { callbacks.refresh() }
                }
                val openHideCallback = NativeActionCallback {
                    EventQueue.invokeLater { callbacks.openHide() }
                }
                val quitCallback = NativeActionCallback {
                    EventQueue.invokeLater { callbacks.quit() }
                }
                val handle = native.qd_statusbar_create(
                    refreshCallback,
                    openHideCallback,
                    quitCallback,
                ) ?: error("Native status bar helper returned null")
                MacStatusBarIcon(native, handle, refreshCallback, openHideCallback, quitCallback).also {
                    it.update(state)
                }
            }.onFailure {
                logStatusBarError("create macOS status item", it)
            }.getOrNull()
        }
    }
}

private fun loadMacStatusBarNative(): MacStatusBarNative {
    val resource = "/macos/libQuotaDogStatusBar.dylib"
    val stream = MacStatusBarNative::class.java.getResourceAsStream(resource)
        ?: error("Missing native helper resource $resource")
    val dylib = Files.createTempFile("quotadog-statusbar", ".dylib")
    stream.use { input ->
        Files.newOutputStream(dylib).use { output ->
            input.copyTo(output)
        }
    }
    dylib.toFile().deleteOnExit()
    return Native.load(dylib.toAbsolutePath().toString(), MacStatusBarNative::class.java)
}

private interface MacStatusBarNative : Library {
    fun qd_statusbar_create(
        onRefresh: NativeActionCallback,
        onOpenHide: NativeActionCallback,
        onQuit: NativeActionCallback,
    ): Pointer?

    fun qd_statusbar_update(handle: Pointer, json: String)
    fun qd_statusbar_dispose(handle: Pointer)
}

private fun interface NativeActionCallback : Callback {
    fun invoke()
}

private fun DesktopStatusBarState.toJson(): String {
    return buildString {
        append('{')
        appendJsonField("tooltip", tooltip)
        append(',')
        appendJsonField("summary", summary)
        append(',')
        append("\"refreshEnabled\":").append(refreshEnabled)
        append(',')
        append("\"windowVisible\":").append(windowVisible)
        append(',')
        append("\"moreAccounts\":").append(moreAccounts)
        append(',')
        append("\"accounts\":[")
        accounts.forEachIndexed { index, account ->
            if (index > 0) append(',')
            append('{')
            appendJsonField("title", account.title)
            append(',')
            appendJsonField("status", account.status)
            append(',')
            appendJsonField("provider", account.provider)
            append(',')
            append("\"busy\":").append(account.busy)
            append(',')
            append("\"windows\":[")
            account.windows.forEachIndexed { windowIndex, window ->
                if (windowIndex > 0) append(',')
                append('{')
                appendJsonField("label", window.label)
                append(',')
                append("\"usedPct\":").append(window.usedPct)
                append(',')
                append("\"remainingPct\":").append(window.remainingPct)
                append(',')
                appendJsonField("resetLabel", window.resetLabel)
                append('}')
            }
            append(']')
            append('}')
        }
        append(']')
        append('}')
    }
}

private fun StringBuilder.appendJsonField(name: String, value: String) {
    append('"').append(name).append("\":")
    appendJsonString(value)
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}

private fun createAwtTrayImage(): Image {
    val size = 32
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = AwtColor(0x2F, 0x7D, 0x5B)
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawArc(7, 8, 18, 18, 200, 140)
        g.drawLine(16, 18, 22, 13)
        g.fillOval(14, 16, 4, 4)
    } finally {
        g.dispose()
    }
    return image
}

private fun usableScreenBoundsFor(x: Int, y: Int): Rectangle {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val device = environment.screenDevices.firstOrNull {
        it.defaultConfiguration.bounds.contains(x, y)
    } ?: environment.defaultScreenDevice
    val config = device.defaultConfiguration
    val bounds = Rectangle(config.bounds)
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}

private fun Int.clampTo(min: Int, max: Int): Int {
    if (max < min) return min
    return coerceIn(min, max)
}

private fun isMacOs(): Boolean {
    return System.getProperty("os.name", "").contains("Mac", ignoreCase = true)
}

private fun logStatusBarError(action: String, throwable: Throwable) {
    System.err.println("QuotaDog: failed to $action")
    throwable.printStackTrace(System.err)
}
