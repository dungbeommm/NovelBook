package vn.tramtruyen.reader.engine

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.widget.Toast

object WarpIntegration {
    const val PACKAGE = "com.cloudflare.onedotonedotonedotone"
    private const val STORE = "https://play.google.com/store/apps/details?id=$PACKAGE"
    fun installed(context: Context): Boolean = context.packageManager.getLaunchIntentForPackage(PACKAGE) != null
    fun systemVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
    fun download(context: Context) {
        if (!launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")))) {
            if (!launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(STORE))))
                Toast.makeText(context, "Không tìm thấy ứng dụng mở cửa hàng", Toast.LENGTH_LONG).show()
        }
    }
    fun open(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (intent == null) download(context)
        else if (!launch(context, intent))
            Toast.makeText(context, "Không mở được 1.1.1.1. Hãy mở từ màn hình chính.", Toast.LENGTH_LONG).show()
    }
    private fun launch(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
