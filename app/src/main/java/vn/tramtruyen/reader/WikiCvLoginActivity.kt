package vn.tramtruyen.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import vn.tramtruyen.reader.engine.sources.WikiCvSession

/**
 * Cửa sổ đăng nhập WikiCV chạy bằng WebView của chính app.
 *
 * WebView này và WebViewFetcher dùng chung kho CookieManager của tiến trình, nên
 * phiên WikiCV/Diễn đàn sau khi đăng nhập được tái sử dụng cho tìm kiếm và đọc
 * truyện. Mật khẩu do trang web xử lý trực tiếp; app không đọc hay lưu nội dung
 * các ô đăng nhập.
 */
class WikiCvLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var openedLogin = false
    private var restartingOAuth = false
    private lateinit var wikiSearchUrl: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
                else finishWithCookies()
            }
        })

        val cookies = CookieManager.getInstance().apply {
            setAcceptCookie(true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 12, 10)
        }
        val title = TextView(this).apply {
            text = "Đăng nhập WikiCV"
            textSize = 18f
            setTextColor(Color.BLACK)
        }
        val done = Button(this).apply {
            text = "Xong"
            setOnClickListener { finishWithCookies() }
        }
        bar.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(done)

        webView = WebView(this).apply {
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; SM-G991B) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/122.0.0.0 Mobile Safari/537.36"
            }
            cookies.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    val host = Uri.parse(url).host.orEmpty().lowercase()

                    // Khi diễn đàn đã đăng nhập, chạy lại OAuth để WikiCV nhận
                    // callback và tạo express.sid của chính wikicv.org.
                    if (host.endsWith("dichtienghoa.com")) {
                        view.evaluateJavascript(
                            """
                            (function(){
                              return !!document.querySelector(
                                'a[href*="/logout"], [data-ajaxify*="logout"], [data-action="logout"]'
                              );
                            })();
                            """.trimIndent()
                        ) { raw ->
                            if (raw == "true" && !restartingOAuth) {
                                restartingOAuth = true
                                view.post { view.loadUrl(WIKI_OAUTH_URL) }
                            }
                        }
                        return
                    }

                    if (host.endsWith("wikicv.org")) {
                        view.evaluateJavascript(
                            """
                            (function(){
                              var login=!!document.querySelector('a[data-action="login"]');
                              var wall=((document.body&&document.body.innerText)||'')
                                .indexOf('Đăng nhập để xem nội dung')>=0;
                              return (!login && !wall);
                            })();
                            """.trimIndent()
                        ) { raw ->
                            if (raw == "true") {
                                WikiCvSession.setLoggedIn(true)
                                view.postDelayed({ finishWithCookies() }, 450)
                            } else {
                                WikiCvSession.setLoggedIn(false)
                            }
                        }
                    }

                    // JS gốc dùng window.open('/auth/dth'), nhưng popup bị chặn
                    // trong Android WebView. Đổi popup thành điều hướng cùng WebView
                    // để vẫn chạy nguyên vẹn bước xóa cookie + tạo mã OAuth.
                    if (!openedLogin && host.endsWith("wikicv.org")) {
                        openedLogin = true
                        view.evaluateJavascript(
                            """
                            (function(){
                              window.open=function(url){
                                window.location.href=url;
                                return {closed:false};
                              };
                              var links=[].slice.call(document.querySelectorAll('a[data-action="login"]'));
                              if(links.length){ links[0].click(); return 'OPENED'; }
                              return 'NO_LOGIN_LINK';
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }
            }
        }

        root.addView(bar)
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)

        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty().trim()
        wikiSearchUrl = Uri.Builder()
            .scheme("https")
            .authority("wikicv.org")
            .path("/tim-kiem")
            .appendQueryParameter("q", query)
            .appendQueryParameter("qs", "1")
            .build()
            .toString()
        webView.loadUrl(wikiSearchUrl)
    }

    private fun finishWithCookies() {
        CookieManager.getInstance().flush()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUERY = "query"
        private const val WIKI_OAUTH_URL = "https://wikicv.org/auth/dth"
    }
}