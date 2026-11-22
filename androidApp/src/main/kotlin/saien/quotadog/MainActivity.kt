package saien.quotadog

import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import saien.quotadog.hostsync.AndroidSyncKeys
import saien.quotadog.hostsync.PhoneDashboard
import saien.quotadog.hostsync.PhoneSync
import saien.quotadog.hostsync.RelayTicketRejected
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private var phone: PhoneSync? = null
    private var pendingQr: String? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var watcher: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null
    private val scan = registerForActivityResult(ScanContract()) { it.contents?.let(::accept) }
    private val open = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { accept(runCatching { decodeQr(it) }.getOrDefault("")) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeAndroidPlatform(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        phone = try {
            System.loadLibrary("quotadog_host_tls")
            val keys = AndroidSyncKeys(this)
            PhoneSync(HttpClient(OkHttp) {
                followRedirects = false
                engine { config {
                    followRedirects(false)
                    followSslRedirects(false)
                    pingInterval(60, TimeUnit.SECONDS)
                    addInterceptor { chain ->
                        val response = chain.proceed(chain.request())
                        if (response.code == 403) { response.close(); throw RelayTicketRejected() }
                        response
                    }
                } }
                install(WebSockets)
            }, keys.identity, keys)
        } catch (_: Exception) { null } catch (_: UnsatisfiedLinkError) { null }
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        setContent { PhoneDashboard(phone, { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)) }, { open.launch(arrayOf("image/*")) }, "v$version") }
    }
    private fun decodeQr(uri: Uri): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0)
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val bitmap = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) } ?: error("Invalid image")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = RGBLuminanceSource(bitmap.width, bitmap.height, pixels); bitmap.recycle()
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(image))).text
    }
    private fun accept(qr: String) { if (network != null) phone?.pair(qr) else pendingQr = qr }
    private fun useNetwork(next: Network?) {
        network = next; phone?.useNetwork(next?.networkHandle)
        if (next != null) { pendingQr?.let(::accept); pendingQr = null }
    }
    override fun onResume() {
        super.onResume()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = runOnUiThread { if (watcher === this) useNetwork(n) }
            override fun onLost(n: Network) = runOnUiThread { if (watcher === this && network == n) useNetwork(null) }
        }
        watcher = callback
        connectivity.registerDefaultNetworkCallback(callback)
        useNetwork(connectivity.activeNetwork)
    }
    override fun onPause() {
        watcher?.let(connectivity::unregisterNetworkCallback); watcher = null
        useNetwork(null); super.onPause()
    }
    override fun onDestroy() { phone?.close(); super.onDestroy() }
}
