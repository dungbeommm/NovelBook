package vn.tramtruyen.reader.engine.network

import android.content.Context
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

object AppDns {
    enum class Provider(
        val label: String,
        val endpoint: String?,
        val bootstrap: List<String> = emptyList(),
    ) {
        CLOUDFLARE(
            "Cloudflare · 1.1.1.1",
            "https://cloudflare-dns.com/dns-query",
            listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
        ),
        GOOGLE(
            "Google · 8.8.8.8",
            "https://dns.google/dns-query",
            listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
        ),
        SYSTEM("DNS của Android", null),
    }

    private const val PREFS = "network_dns"
    private const val KEY_PROVIDER = "provider"
    private val selected = MutableStateFlow(Provider.CLOUDFLARE)
    val provider = selected.asStateFlow()

    private val resolvers by lazy {
        val transport = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        Provider.entries.mapNotNull { preset ->
            val endpoint = preset.endpoint ?: return@mapNotNull null
            preset to DnsOverHttps.Builder()
                .client(transport)
                .url(endpoint.toHttpUrl())
                .post(true)
                .bootstrapDnsHosts(preset.bootstrap.map { InetAddress.getByName(it) })
                .build()
        }.toMap()
    }

    private val dns = Dns { host ->
        val preset = selected.value
        if (preset.endpoint == null || !host.contains('.') || host.endsWith(".local", ignoreCase = true)) {
            Dns.SYSTEM.lookup(host)
        } else {
            try {
                resolvers.getValue(preset).lookup(host).ifEmpty { Dns.SYSTEM.lookup(host) }
            } catch (_: UnknownHostException) {
                Dns.SYSTEM.lookup(host)
            }
        }
    }

    fun init(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROVIDER, null)
        selected.value = Provider.entries.firstOrNull { it.name == stored } ?: Provider.CLOUDFLARE
    }

    fun select(context: Context, preset: Provider) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, preset.name)
            .apply()
        selected.value = preset
    }

    fun clientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder().dns(dns)
}
