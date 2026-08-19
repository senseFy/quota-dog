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
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.awt.Color as AwtColor

internal const val TRAY_PANEL_WIDTH = 420
internal const val TRAY_PANEL_HEIGHT = 580
internal const val STATUS_BAR_ACCOUNT_LIMIT = 4

private const val TRAY_PANEL_MARGIN = 8

internal data class DesktopStatusBarState(
    val tooltip: String,
    val providerFilters: List<DesktopStatusBarProviderFilter>,
    val selectedProvider: String,
    val accounts: List<DesktopStatusBarAccount>,
    val moreAccounts: Int,
    val refreshEnabled: Boolean,
    val refreshBusy: Boolean,
    val darkTheme: Boolean,
    /** [UsageDisplayMode.name] — Used or Remaining. */
    val usageDisplayMode: String = UsageDisplayMode.Used.name,
)

internal data class DesktopStatusBarProviderFilter(
    val id: String,
    val label: String,
)

internal data class DesktopStatusBarAccount(
    val id: String,
    val title: String,
    val status: String,
    val emptyLabel: String,
    val provider: String,
    val busy: Boolean,
    val refreshable: Boolean,
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
    onRefreshAccount: (String) -> Unit,
    onShow: () -> Unit,
    onOpenWindow: () -> Unit,
    onQuit: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onFallbackClick: (x: Int, y: Int) -> Unit,
    onAvailabilityChanged: (Boolean) -> Unit,
) {
    val currentState = rememberUpdatedState(state)
    val currentOnRefresh = rememberUpdatedState(onRefresh)
    val currentOnRefreshAccount = rememberUpdatedState(onRefreshAccount)
    val currentOnShow = rememberUpdatedState(onShow)
    val currentOnOpenWindow = rememberUpdatedState(onOpenWindow)
    val currentOnQuit = rememberUpdatedState(onQuit)
    val currentOnSelectProvider = rememberUpdatedState(onSelectProvider)
    val currentOnFallbackClick = rememberUpdatedState(onFallbackClick)
    val currentOnAvailabilityChanged = rememberUpdatedState(onAvailabilityChanged)
    val callbacks = remember {
        StatusBarCallbacks(
            refresh = { currentOnRefresh.value() },
            refreshAccount = { currentOnRefreshAccount.value(it) },
            show = { currentOnShow.value() },
            openHide = {
                currentOnOpenWindow.value()
            },
            quit = { currentOnQuit.value() },
            selectProvider = { currentOnSelectProvider.value(it) },
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
    val refreshAccount: (String) -> Unit,
    val show: () -> Unit,
    val openHide: () -> Unit,
    val quit: () -> Unit,
    val selectProvider: (String) -> Unit,
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
    private val refreshAccountCallback: NativeAccountCallback,
    private val showCallback: NativeActionCallback,
    private val openHideCallback: NativeActionCallback,
    private val quitCallback: NativeActionCallback,
    private val selectProviderCallback: NativeProviderCallback,
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
                val refreshAccountCallback = NativeAccountCallback { accountKey ->
                    val id = accountKey.orEmpty()
                    EventQueue.invokeLater { callbacks.refreshAccount(id) }
                }
                val showCallback = NativeActionCallback {
                    EventQueue.invokeLater { callbacks.show() }
                }
                val openHideCallback = NativeActionCallback {
                    EventQueue.invokeLater { callbacks.openHide() }
                }
                val quitCallback = NativeActionCallback {
                    EventQueue.invokeLater { callbacks.quit() }
                }
                val selectProviderCallback = NativeProviderCallback { provider ->
                    EventQueue.invokeLater { callbacks.selectProvider(provider.orEmpty()) }
                }
                val handle = native.qd_statusbar_create(
                    refreshCallback,
                    showCallback,
                    openHideCallback,
                    quitCallback,
                    selectProviderCallback,
                    refreshAccountCallback,
                ) ?: error("Native status bar helper returned null")
                MacStatusBarIcon(
                    native,
                    handle,
                    refreshCallback,
                    refreshAccountCallback,
                    showCallback,
                    openHideCallback,
                    quitCallback,
                    selectProviderCallback,
                ).also {
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
        onShow: NativeActionCallback,
        onOpenHide: NativeActionCallback,
        onQuit: NativeActionCallback,
        onSelectProvider: NativeProviderCallback,
        onRefreshAccount: NativeAccountCallback,
    ): Pointer?

    fun qd_statusbar_update(handle: Pointer, json: String)
    fun qd_statusbar_dispose(handle: Pointer)
}

private fun interface NativeActionCallback : Callback {
    fun invoke()
}

private fun interface NativeProviderCallback : Callback {
    fun invoke(provider: String?)
}

private fun interface NativeAccountCallback : Callback {
    fun invoke(accountKey: String?)
}

internal fun AccountKey.toStatusBarId(): String = "${providerId.name}:$accountId"

internal fun parseStatusBarAccountKey(raw: String): AccountKey? {
    val separator = raw.indexOf(':')
    if (separator <= 0 || separator >= raw.lastIndex) return null
    val provider = ProviderId.entries.firstOrNull { it.name == raw.substring(0, separator) }
        ?: return null
    return AccountKey(provider, raw.substring(separator + 1))
}

private fun DesktopStatusBarState.toJson(): String {
    return buildString {
        append('{')
        appendJsonField("tooltip", tooltip)
        append(',')
        appendJsonField("selectedProvider", selectedProvider)
        append(',')
        appendJsonField("usageDisplayMode", usageDisplayMode)
        append(',')
        append("\"refreshEnabled\":").append(refreshEnabled)
        append(',')
        append("\"refreshBusy\":").append(refreshBusy)
        append(',')
        append("\"darkTheme\":").append(darkTheme)
        append(',')
        append("\"moreAccounts\":").append(moreAccounts)
        append(',')
        append("\"providerFilters\":[")
        providerFilters.forEachIndexed { index, filter ->
            if (index > 0) append(',')
            append('{')
            appendJsonField("id", filter.id)
            append(',')
            appendJsonField("label", filter.label)
            append('}')
        }
        append(']')
        append(',')
        append("\"accounts\":[")
        accounts.forEachIndexed { index, account ->
            if (index > 0) append(',')
            append('{')
            appendJsonField("id", account.id)
            append(',')
            appendJsonField("title", account.title)
            append(',')
            appendJsonField("status", account.status)
            append(',')
            appendJsonField("emptyLabel", account.emptyLabel)
            append(',')
            appendJsonField("provider", account.provider)
            append(',')
            append("\"busy\":").append(account.busy)
            append(',')
            append("\"refreshable\":").append(account.refreshable)
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
        val pad = 3.0
        val r = size / 2.0 - pad
        val cx = size / 2.0
        val cy = size / 2.0
        val a = 0.6 * r
        val disk = Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2)
        g.color = AwtColor(0xB8, 0xB8, 0xB8)
        g.fill(disk)

        // Remaining (80%) on the left: circle's left half + elliptical terminator.
        val remain = Path2D.Double()
        remain.append(Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 90.0, 180.0, Arc2D.OPEN), false)
        val ellipse = AffineTransform.getTranslateInstance(cx, cy).apply { scale(a, r) }
            .createTransformedShape(Arc2D.Double(-1.0, -1.0, 2.0, 2.0, 270.0, 180.0, Arc2D.OPEN))
        remain.append(ellipse, true)
        remain.closePath()
        g.color = AwtColor(0x11, 0x11, 0x11)
        g.fill(remain)
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

internal fun isMacOs(): Boolean {
    return System.getProperty("os.name", "").contains("Mac", ignoreCase = true)
}

private fun logStatusBarError(action: String, throwable: Throwable) {
    System.err.println("QuotaDog: failed to $action")
    throwable.printStackTrace(System.err)
}
