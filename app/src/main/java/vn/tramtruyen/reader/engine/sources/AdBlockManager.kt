package vn.tramtruyen.reader.engine.sources

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Applies the app's host, URL, and query-name rules to browser requests. */
object AdBlockManager {
    data class State(
        val installed: Boolean = false,
        val enabled: Boolean = true,
        val updating: Boolean = false,
        val ruleCount: Int = 0,
        val lastUpdated: Long = 0L,
        val error: String? = null,
    )

    private data class Rules(
        val blockedHosts: Set<String> = emptySet(),
        val urlFragments: List<String> = emptyList(),
        val queryKeys: Set<String> = emptySet(),
    ) {
        val count: Int get() = blockedHosts.size + urlFragments.size + queryKeys.size

        companion object {
            val EMPTY = Rules()
        }
    }

    private const val PREFS = "browser_adguard"
    private const val KEY_ENABLED = "enabled"
    private const val LEGACY_RULE_FILE = "adguard_domains.txt"
    private const val LEGACY_ALLOW_FILE = "adguard_allow_domains.txt"
    private const val RULE_ASSET = "adblock_rules.json"

    @Volatile private var activeRules = Rules.EMPTY
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val blockedCounter = AtomicLong(0L)

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        removeLegacyFiles(app)
        reload(app)
    }

    /** Loads the downloaded extension when valid, otherwise keeps the APK copy active. */
    fun reload(context: Context? = appContext) {
        val app = context?.applicationContext ?: return
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        activeRules = loadRules(app)
        _state.value = State(
            installed = activeRules.count > 0,
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            ruleCount = activeRules.count,
        )
    }

    fun setEnabled(context: Context? = appContext, enabled: Boolean) {
        context ?: return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        _state.update { it.copy(enabled = enabled, error = null) }
    }

    fun shouldBlock(rawUrl: String): Boolean {
        if (!_state.value.enabled) return false
        val url = rawUrl.lowercase(Locale.ROOT)
        if (url.isBlank()) return false

        val rules = activeRules
        val host = runCatching { Uri.parse(rawUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
        val hostMatch = host != null && rules.blockedHosts.any { host == it || host.endsWith(".$it") }
        val fragmentMatch = rules.urlFragments.any(url::contains)
        val queryMatch = queryParameterNames(rawUrl).any(rules.queryKeys::contains)

        if (hostMatch || fragmentMatch || queryMatch) {
            blockedCounter.incrementAndGet()
            return true
        }
        return false
    }

    private fun queryParameterNames(rawUrl: String): Set<String> {
        val queryStart = rawUrl.indexOf('?')
        if (queryStart < 0) return emptySet()
        val query = rawUrl.substring(queryStart + 1).substringBefore('#')
        if (query.isBlank()) return emptySet()
        return query.split('&', ';').asSequence()
            .map { it.substringBefore('=').replace('+', ' ') }
            .map { Uri.decode(it).lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun loadRules(context: Context): Rules {
        val downloaded = File(context.filesDir, "adblock_extension/$RULE_ASSET")
        if (downloaded.isFile) {
            val packaged = runCatching { parseRules(downloaded.readText(Charsets.UTF_8)) }.getOrNull()
            if (packaged != null && packaged.count > 0) return packaged
        }
        return runCatching {
            val text = context.assets.open(RULE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseRules(text)
        }.getOrDefault(Rules.EMPTY)
    }

    private fun parseRules(text: String): Rules {
        val root = JSONObject(text)
        check(root.optString("format") == "tramtruyen-adblock-rules-v1") {
            "Unsupported ad-block rules format."
        }

        fun strings(key: String): List<String> {
            val array = root.optJSONArray(key) ?: error("Missing rule list: $key")
            return buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim().lowercase(Locale.ROOT)
                    if (value.isNotEmpty()) add(value)
                }
            }
        }

        val hosts = strings("blocked_hosts").filter { host ->
            host.contains('.') && host.matches(Regex("[a-z0-9.-]{3,253}")) &&
                !host.startsWith('.') && !host.endsWith('.') && ".." !in host
        }.toSet()
        val fragments = strings("url_fragments").filter { fragment ->
            fragment.length >= 3 && fragment.all { it.code >= 0x20 }
        }.distinct()
        val queryKeys = strings("query_keys").filter { key ->
            key.matches(Regex("[a-z0-9_-]{2,80}"))
        }.toSet()

        val rules = Rules(hosts, fragments, queryKeys)
        check(rules.count > 0) { "No usable ad-block rules." }
        return rules
    }

    private fun removeLegacyFiles(context: Context) {
        val dir = File(context.filesDir, "adblock")
        runCatching { File(dir, LEGACY_RULE_FILE).delete() }
        runCatching { File(dir, LEGACY_ALLOW_FILE).delete() }
    }
}
