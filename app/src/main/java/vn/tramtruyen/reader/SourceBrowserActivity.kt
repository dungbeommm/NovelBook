package vn.tramtruyen.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.CircularProgressIndicator
import vn.tramtruyen.reader.model.ColorMode
import vn.tramtruyen.reader.model.DEFAULT_ACCENT
import vn.tramtruyen.reader.model.DEFAULT_SEED
import vn.tramtruyen.reader.model.MONET
import vn.tramtruyen.reader.model.UiTheme
import vn.tramtruyen.reader.presentation.SettingsStore
import vn.tramtruyen.reader.presentation.motion.rememberHaptics
import vn.tramtruyen.reader.presentation.components.noGrayClick
import vn.tramtruyen.reader.presentation.theme.ReaderTheme
import vn.tramtruyen.reader.presentation.theme.hex
import vn.tramtruyen.reader.engine.sources.BrowserSession
import vn.tramtruyen.reader.engine.sources.BrowserHistoryManager
import vn.tramtruyen.reader.engine.sources.GeckoFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.tramtruyen.reader.engine.ReadingEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Browser MD3/Monet, bố cục gọn theo mẫu Via và dùng chung WebView tải truyện ngầm. */
open class SourceBrowserActivity : ComponentActivity(), BrowserSession.Observer {
    private var currentUrl by mutableStateOf("")
    private var currentTitle by mutableStateOf("")
    private var loadProgress by mutableIntStateOf(0)
    private var canGoBackState by mutableStateOf(false)
    private var canGoForwardState by mutableStateOf(false)
    private var webContainer: FrameLayout? = null
    private var listenJob: Job? = null
    private var preparingChapter by mutableStateOf(false)
    private var openingGecko by mutableStateOf(false)

    private var uiTheme by mutableStateOf(UiTheme.SYSTEM)
    private var colorMode by mutableStateOf(ColorMode.SYSTEM_WALLPAPER)
    private var seedColor by mutableStateOf(hex(MONET[DEFAULT_SEED] ?: DEFAULT_ACCENT))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            runCatching { SettingsStore(this@SourceBrowserActivity).load() }.getOrNull()?.let { settings ->
                uiTheme = settings.uiTheme
                colorMode = settings.colorMode
                seedColor = hex(MONET[settings.monetSeed] ?: settings.accentColor)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBackPage()) finish()
            }
        })

        setContent {
            ReaderTheme(
                uiTheme = uiTheme,
                colorMode = colorMode,
                seedColor = seedColor,
            ) {
                BrowserScreen()
            }
        }

        // Khi mở trình duyệt lần đầu (không phải do xoay màn hình tạo lại),
        // ưu tiên chương đang nghe; chỉ về trang chủ khi không có link phù hợp.
        if (savedInstanceState == null) {
            val latest = vn.tramtruyen.reader.engine.Store.getHistory().firstOrNull()
            fun value(key: String) = (latest?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
            val target = vn.tramtruyen.reader.engine.BrowserOpenPolicy.resolve(
                explicit = intent?.getStringExtra(EXTRA_URL),
                chapterUrl = value("chapterUrl"),
                storyUrl = value("storyUrl"),
            )
            openUrl(target)
        }
    }

    override fun onStart() {
        super.onStart()
        webContainer?.let(::attachBrowser)
    }

    override fun onStop() {
        detachBrowser()
        super.onStop()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BrowserScreen() {
        val cs = MaterialTheme.colorScheme
        val haptics = rememberHaptics()
        val history by BrowserHistoryManager.items.collectAsState()
        var addressDraft by remember { mutableStateOf(currentUrl) }
        var addressFocused by remember { mutableStateOf(false) }
        var menuOpen by remember { mutableStateOf(false) }
        var historyOpen by remember { mutableStateOf(false) }
        var findOpen by remember { mutableStateOf(false) }
        var findQuery by remember { mutableStateOf("") }
        var desktopMode by remember { mutableStateOf(isDesktopPage()) }
        val focusManager = LocalFocusManager.current

        LaunchedEffect(currentUrl, addressFocused) {
            if (!addressFocused && currentUrl.isNotBlank() && currentUrl != "about:blank") {
                addressDraft = currentUrl
            }
        }

        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize()) {
                Surface(
                    color = cs.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(Modifier.statusBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            BrowserIconButton(
                                icon = Icons.Rounded.Home,
                                description = "Trang chủ",
                                enabled = true,
                                size = 48,
                                iconSize = 26,
                            ) { openBrowserHome() }

                            Surface(
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(24.dp),
                                color = cs.surfaceContainerHigh,
                            ) {
                                Row(
                                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BrowserIcon(
                                        icon = Icons.Rounded.Tune,
                                        description = "Điều khiển trang",
                                        tint = cs.onSurface,
                                        size = 24,
                                    )
                                    Spacer(Modifier.size(10.dp))
                                    BasicTextField(
                                        value = addressDraft,
                                        onValueChange = { addressDraft = it },
                                        modifier = Modifier.weight(1f).onFocusChanged {
                                            addressFocused = it.isFocused
                                        },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            color = cs.onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        cursorBrush = SolidColor(cs.primary),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            imeAction = ImeAction.Go,
                                        ),
                                        keyboardActions = KeyboardActions(onGo = {
                                            val target = addressDraft.trim().let {
                                                if (it.startsWith("https://")) it else "https://$it"
                                            }
                                            openUrl(if (GeckoFeature.supports(target)) target else addressDraft)
                                            focusManager.clearFocus()
                                            addressFocused = false
                                        }),
                                        decorationBox = { inner ->
                                            Box(contentAlignment = Alignment.CenterStart) {
                                                if (addressDraft.isBlank()) {
                                                    Text(
                                                        "Tìm kiếm hoặc nhập địa chỉ",
                                                        color = cs.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                    )
                                                }
                                                inner()
                                            }
                                        },
                                    )
                                }
                            }

                            Box {
                                BrowserIconButton(
                                    icon = Icons.Rounded.MoreVert,
                                    description = "Menu trình duyệt",
                                    enabled = true,
                                    size = 48,
                                    iconSize = 26,
                                ) { menuOpen = true }
                                BrowserMenu(
                                    expanded = menuOpen,
                                    onDismiss = { menuOpen = false },
                                    canGoBack = canGoBackState,
                                    canGoForward = canGoForwardState,
                                    desktopMode = desktopMode,
                                    onHistory = {
                                        menuOpen = false
                                        historyOpen = true
                                    },
                                    onFindInPage = {
                                        menuOpen = false
                                        findOpen = true
                                    },
                                    onDesktopMode = { enabled ->
                                        menuOpen = false
                                        desktopMode = enabled
                                        setDesktopPage(enabled)
                                    },
                                    onCloseBrowser = {
                                        menuOpen = false
                                        finish()
                                    },
                                )
                            }
                        }
                        if (openingGecko) {
                            androidx.compose.material3.LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                            )
                        } else if (loadProgress in 0..99) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { loadProgress / 100f },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = cs.primary,
                                trackColor = Color.Transparent,
                            )
                        } else {
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth().background(cs.surface)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            FrameLayout(context).also { frame ->
                                webContainer = frame
                                attachBrowser(frame)
                            }
                        },
                    )
                    DisposableEffect(Unit) {
                        onDispose {
                            detachBrowser()
                            webContainer = null
                        }
                    }

                    // Giữ hành động nghe tách khỏi thanh điều hướng, giống một FAB
                    // thực thụ và không làm các nút browser bị phình to.
                    FloatingActionButton(
                        onClick = {
                            haptics.snap()
                            listenCurrentChapter()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp)
                            .size(48.dp),
                        shape = CircleShape,
                        containerColor = cs.primaryContainer,
                        contentColor = cs.onPrimaryContainer,
                    ) {
                        if (preparingChapter) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else BrowserIcon(
                            Icons.Rounded.GraphicEq,
                            "Nghe chương này",
                            cs.onPrimaryContainer,
                            22,
                        )
                    }
                }

            }
        }

        if (historyOpen) {
            HistorySheet(
                historyEntries = history,
                onDismiss = { historyOpen = false },
                onOpen = { entry ->
                    haptics.tap()
                    historyOpen = false
                    openUrl(entry.url)
                },
                onRemove = { entry ->
                    haptics.tick()
                    BrowserHistoryManager.remove(this@SourceBrowserActivity, entry)
                },
                onClear = {
                    haptics.snap()
                    BrowserHistoryManager.clear(this@SourceBrowserActivity)
                },
            )
        }

        if (findOpen) {
            FindInPageDialog(
                query = findQuery,
                onQueryChange = {
                    findQuery = it
                    findOnPage(it)
                },
                onPrevious = { findNextOnPage(false) },
                onNext = { findNextOnPage(true) },
                onDismiss = {
                    findOpen = false
                    findOnPage("")
                },
            )
        }
    }

    @Composable
    private fun BrowserMenu(
        expanded: Boolean,
        onDismiss: () -> Unit,
        canGoBack: Boolean,
        canGoForward: Boolean,
        desktopMode: Boolean,
        onHistory: () -> Unit,
        onFindInPage: () -> Unit,
        onDesktopMode: (Boolean) -> Unit,
        onCloseBrowser: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.widthIn(min = 290.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrowserIconButton(Icons.Rounded.ArrowBack, "Quay lại", canGoBack) {
                    onDismiss()
                    goBackPage()
                }
                BrowserIconButton(Icons.Rounded.ArrowForward, "Tiến tới", canGoForward) {
                    onDismiss()
                    goForwardPage()
                }
                BrowserIconButton(Icons.Rounded.Refresh, "Tải lại", true) {
                    onDismiss()
                    reloadPage()
                }
                BrowserIconButton(Icons.Rounded.Home, "Trang chủ", true) {
                    onDismiss()
                    openBrowserHome()
                }
            }
            HorizontalDivider()
            BrowserMenuItem(Icons.Rounded.GraphicEq, "Nghe chương này") {
                onDismiss()
                listenCurrentChapter()
            }
            BrowserMenuItem(Icons.Rounded.FindInPage, "Tìm trong trang", onFindInPage)
            BrowserMenuItem(Icons.Rounded.History, "Lịch sử", onHistory)
            BrowserMenuItem(Icons.Rounded.ContentCopy, "Sao chép liên kết") {
                onDismiss()
                copyUrl()
            }
            BrowserMenuItem(Icons.Rounded.Share, "Chia sẻ") {
                onDismiss()
                shareUrl()
            }
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Rounded.DesktopWindows, null) },
                text = { Text("Trang web cho máy tính") },
                trailingIcon = {
                    Switch(
                        checked = desktopMode,
                        onCheckedChange = onDesktopMode,
                    )
                },
                onClick = { onDesktopMode(!desktopMode) },
            )
            BrowserMenuItem(Icons.Rounded.OpenInBrowser, "Mở bằng trình duyệt khác") {
                onDismiss()
                openExternal()
            }
            HorizontalDivider()
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Rounded.Close, null, tint = cs.error)
                },
                text = {
                    Text("Đóng trình duyệt", color = cs.error)
                },
                onClick = onCloseBrowser,
            )
        }
    }

    @Composable
    private fun BrowserMenuItem(icon: ImageVector, label: String, action: () -> Unit) {
        DropdownMenuItem(
            leadingIcon = { Icon(icon, null) },
            text = { Text(label) },
            onClick = action,
        )
    }

    @Composable
    private fun FindInPageDialog(
        query: String,
        onQueryChange: (String) -> Unit,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Tìm trong trang") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Nhập từ cần tìm") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            BrowserSession.findInPage(query)
                            BrowserSession.findNext(true)
                        }),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onPrevious, enabled = query.isNotBlank()) {
                            Text("Trước")
                        }
                        TextButton(onClick = onNext, enabled = query.isNotBlank()) {
                            Text("Sau")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Xong") }
            },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HistorySheet(
        historyEntries: List<BrowserHistoryManager.Entry>,
        onDismiss: () -> Unit,
        onOpen: (BrowserHistoryManager.Entry) -> Unit,
        onRemove: (BrowserHistoryManager.Entry) -> Unit,
        onClear: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = cs.surface,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Lịch sử duyệt web",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${historyEntries.size} trang gần đây",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                if (historyEntries.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Xóa tất cả") }
                }
            }
            HorizontalDivider(color = cs.outlineVariant)
            if (historyEntries.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(180.dp).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.History,
                            null,
                            Modifier.size(36.dp),
                            tint = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Chưa có lịch sử", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Các trang bạn mở trong browser sẽ xuất hiện tại đây",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp)
                        .navigationBarsPadding(),
                ) {
                    items(
                        items = historyEntries,
                        key = { "${it.visitedAt}:${it.url}" },
                    ) { entry ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().noGrayClick {
                                onOpen(entry)
                            },
                            headlineContent = {
                                Text(
                                    entry.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        entry.url,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        formatHistoryTime(entry.visitedAt),
                                        color = cs.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            leadingContent = {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = cs.secondaryContainer,
                                ) {
                                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Rounded.History,
                                            null,
                                            tint = cs.onSecondaryContainer,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { onRemove(entry) }) {
                                    Icon(Icons.Rounded.DeleteOutline, "Xóa mục này")
                                }
                            },
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                            ),
                        )
                        HorizontalDivider(
                            Modifier.padding(start = 76.dp),
                            color = cs.outlineVariant.copy(alpha = .65f),
                        )
                    }
                }
            }
        }
    }

    private fun formatHistoryTime(timestamp: Long): String =
        SimpleDateFormat("HH:mm · dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))

    @Composable
    private fun BrowserIconButton(
        icon: ImageVector,
        description: String,
        enabled: Boolean,
        size: Int = 44,
        iconSize: Int = 23,
        action: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        val haptics = rememberHaptics()
        Box(
            modifier = Modifier.size(size.dp)
                .clip(CircleShape)
                .noGrayClick(enabled = enabled) {
                    haptics.tap()
                    action()
                },
            contentAlignment = Alignment.Center,
        ) {
            BrowserIcon(
                icon,
                description,
                if (enabled) cs.onSurface else cs.onSurface.copy(alpha = .28f),
                iconSize,
            )
        }
    }

    @Composable
    private fun BrowserIcon(icon: ImageVector, description: String?, tint: Color, size: Int) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(size.dp),
            tint = tint,
        )
    }

    private fun listenCurrentChapter() {
        val url = browserUrl()
        if (!url.startsWith("https://", ignoreCase = true)) {
            Toast.makeText(this, "Hãy mở một trang chương HTTPS trước", Toast.LENGTH_SHORT).show()
            return
        }
        if (listenJob?.isActive == true) return
        listenJob = lifecycleScope.launch {
            preparingChapter = true
            try {
                withContext(Dispatchers.IO) { TramTruyenApplication.awaitReady() }
                val chapter = readBrowserChapter(url) ?: return@launch
                startActivity(ReaderActivity.browserChapterIntent(this@SourceBrowserActivity, url, chapter))
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(
                    this@SourceBrowserActivity,
                    error.message ?: "Không lấy được nội dung chương",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                preparingChapter = false
            }
        }
    }

    private fun copyUrl() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("URL", browserUrl()))
        Toast.makeText(this, "Đã sao chép liên kết", Toast.LENGTH_SHORT).show()
    }

    private fun shareUrl() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, currentTitle)
            putExtra(Intent.EXTRA_TEXT, browserUrl())
        }, "Chia sẻ trang"))
    }

    private fun openExternal() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl()))) }
            .onFailure { Toast.makeText(this, "Không tìm thấy trình duyệt khác", Toast.LENGTH_SHORT).show() }
    }

    private fun openUrl(url: String) {
        if (!onUserNavigation(url)) navigatePage(url)
    }

    override fun onUserNavigation(url: String): Boolean {
        if (intent.getBooleanExtra(EXTRA_WEBVIEW_ONLY, false) || !GeckoFeature.supports(url)) return false
        if (openingGecko) return true
        openingGecko = true
        lifecycleScope.launch {
            try {
                if (GeckoFeature.open(this@SourceBrowserActivity, url)) finish()
                else navigatePage(url)
            } finally {
                openingGecko = false
            }
        }
        return true
    }

    override fun onUrlChanged(url: String) { currentUrl = url }
    override fun onTitleChanged(title: String) { currentTitle = title }
    override fun onProgressChanged(progress: Int) { loadProgress = progress }
    override fun onNavigationChanged(canGoBack: Boolean, canGoForward: Boolean) {
        canGoBackState = canGoBack
        canGoForwardState = canGoForward
    }

    protected open fun attachBrowser(container: FrameLayout) {
        BrowserSession.attach(this, container, this)
    }

    protected open fun detachBrowser() = BrowserSession.detach(this)
    protected open fun browserUrl() = BrowserSession.currentUrl()
    protected open fun navigatePage(input: String) { BrowserSession.navigate(input) }
    protected open fun openBrowserHome() { BrowserSession.home() }
    protected open fun goBackPage() = BrowserSession.goBack()
    protected open fun goForwardPage() { BrowserSession.goForward() }
    protected open fun reloadPage() { BrowserSession.reload() }
    protected open fun isDesktopPage() = BrowserSession.isDesktopMode()
    protected open fun setDesktopPage(enabled: Boolean) { BrowserSession.setDesktopMode(enabled) }
    protected open fun findOnPage(query: String) { BrowserSession.findInPage(query) }
    protected open fun findNextOnPage(forward: Boolean) { BrowserSession.findNext(forward) }

    protected open suspend fun readBrowserChapter(url: String): String? {
        val snapshot = BrowserSession.captureCurrentChapter(url)
        val chapter = withContext(Dispatchers.Default) {
            ReadingEngine.chapterFromBrowser(snapshot.url, snapshot.html)
        }
        check(BrowserSession.isCurrent(snapshot)) {
            "Trang đã thay đổi. Hãy bấm nghe lại trên chương đang mở."
        }
        return chapter
    }

    companion object {
        /** URL tùy chọn để mở ngay khi khởi động trình duyệt (vd: trang chủ nguồn truyện). */
        const val EXTRA_URL = "browser_open_url"
        const val EXTRA_WEBVIEW_ONLY = "browser_webview_only"
    }
}
