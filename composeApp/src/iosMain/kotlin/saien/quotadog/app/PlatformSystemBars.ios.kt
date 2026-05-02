package saien.quotadog.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Bridge from Compose dark/light state to native iOS chrome (status bar + home indicator).
 * Swift host calls [IosAppearanceBridge.register] with a handler that updates
 * `UIWindow.overrideUserInterfaceStyle`.
 */
interface IosAppearanceHandler {
    /** [dark] = true when Compose background is dark; iOS chrome should switch accordingly. */
    fun applyAppearance(dark: Boolean)
}

object IosAppearanceBridge {
    private var handler: IosAppearanceHandler? = null
    private var pending: Boolean? = null

    fun register(handler: IosAppearanceHandler?) {
        this.handler = handler
        // If Compose has already produced a value before Swift registered, replay it.
        if (handler != null) pending?.let { handler.applyAppearance(it) }
    }

    internal fun apply(dark: Boolean) {
        pending = dark
        handler?.applyAppearance(dark)
    }
}

@Composable
actual fun ApplyPlatformSystemBars(darkAppearance: Boolean) {
    LaunchedEffect(darkAppearance) {
        IosAppearanceBridge.apply(darkAppearance)
    }
}
