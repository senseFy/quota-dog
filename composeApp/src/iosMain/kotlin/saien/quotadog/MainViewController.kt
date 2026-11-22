package saien.quotadog

import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSBundle
import saien.quotadog.hostsync.*

private val iosQuotaDogStore = QuotaDogStore(usageSnapshotStore = SettingsUsageSnapshotStore(phoneDisplay = true))

fun MainViewController(platform: IosPhonePlatform) = ComposeUIViewController {
    var phone by remember(platform) { mutableStateOf<PhoneSync?>(null) }
    DisposableEffect(platform) {
        platform.watchNetwork(object : IosSyncNetworkListener {
            override fun networkChanged(handle: Long) {
                if (phone == null) phone = try { platform.openPhoneSync() } catch (_: Exception) { null }
                phone?.useNetwork(handle.takeIf { it != 0L })
            }
        })
        onDispose { platform.stopWatchingNetwork(); phone?.close() }
    }
    fun select(image: Boolean) = platform.selectQr(image) { qr ->
        if (qr != null) phone?.pair(qr)
    }
    val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
    PhoneDashboard(phone, { select(false) }, { select(true) }, "v$version", iosQuotaDogStore)
}
