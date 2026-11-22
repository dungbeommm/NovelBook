package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.Config
import vn.tramtruyen.reader.TramTruyenApplication

/**
 * Chỉ lưu cờ phiên đã được WikiCV xác nhận. Cookie thật do Android WebView
 * quản lý; app không lưu tên tài khoản, mật khẩu hay token Google.
 */
object WikiCvSession {
    private const val PREFS = "wikicv_session"
    private const val KEY_LOGGED_IN = "logged_in"

    private fun context() = Config.appContext ?: TramTruyenApplication.appContext

    fun isLoggedIn(): Boolean =
        context()?.getSharedPreferences(PREFS, 0)
            ?.getBoolean(KEY_LOGGED_IN, false)
            ?: false

    fun setLoggedIn(value: Boolean) {
        context()?.getSharedPreferences(PREFS, 0)
            ?.edit()
            ?.putBoolean(KEY_LOGGED_IN, value)
            ?.apply()
    }
}