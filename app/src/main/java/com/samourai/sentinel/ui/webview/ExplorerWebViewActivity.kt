package com.samourai.sentinel.ui.webview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.*
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.databinding.ActivityExplorerWebViewBinding
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import timber.log.Timber
import java.net.InetSocketAddress


class ExplorerWebViewActivity : AppCompatActivity() {

    lateinit var client: WebViewClient
    var tx: Tx? = null
    var url = ""
    lateinit var binding: ActivityExplorerWebViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExplorerWebViewBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        setSupportActionBar(binding.toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (SentinelState.selectedTx != null) {
            tx = SentinelState.selectedTx!!
            title = tx!!.hash.split("-")[0]
        } else {
            finish()
        }

        binding.webView.setBackgroundColor(0)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
            WebViewCompat.startSafeBrowsing(applicationContext) { }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(binding.webView.settings, WebSettingsCompat.FORCE_DARK_ON)
        }
        //Check webkit supports proxy
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // #241: plain ON is published at boot=5, before the SOCKS
            // listener exists (#135) - the old setProxy() at plain ON
            // interpolated a null proxy into the rule ("SOCKS:/null": a
            // rule pointing at a literal host named "null"), and the old
            // else-arm loaded STARTING sessions direct too. Fail closed
            // until fully ready; the Tor observer's ON arm below completes
            // the load once the listener is live.
            when {
                SentinelTorManager.isTorReady() -> setProxy()
                SentinelTorManager.getTorState().state == EnumTorState.OFF ->
                    // Tor explicitly disabled: direct is the expected mode.
                    load()
                else -> {
                    // STARTING / ON-before-listener: defer, show the same
                    // progress the page load uses - fetch nothing.
                    binding.progressWeb.visibility = View.VISIBLE
                }
            }
        } else {
            this.showFloatingSnackBar(binding.webView, text = "Your android does not support proxy enabled WebView", actionText = "Continue", actionClick = {
                load()
            }
            )
        }
    }

    private fun setProxy() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // #241: null must never reach the rule - callers gate on
            // isTorReady(); this re-reads and fails closed.
            val proxy = SentinelTorManager.getProxy() ?: run {
                Timber.w("Explorer proxy deferred: SOCKS listener not yet published (#241)")
                return
            }
            // androidx.webkit expects "socks://host:port" - the old
            // "SOCKS:/" + address() built "SOCKS:///127.0.0.1:9050" from
            // InetSocketAddress's leading slash (and "SOCKS:/null" on the
            // boot=5 window). Build the rule from the parsed fields.
            val address = proxy.address() as? InetSocketAddress ?: return
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("socks://${address.hostName}:${address.port}")
                .build()
            ProxyController.getInstance().setProxyOverride(proxyConfig, {
                load()
            }, {
            })
        }
    }



    @SuppressLint("SetJavaScriptEnabled")
    private fun load() {
        binding.webView.settings.builtInZoomControls = true
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true

        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressWeb.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                Timber.i("onPageCommitVisible: ")
                binding.progressWeb.visibility = View.INVISIBLE
            }
        }
        tx?.let {
            url = intent.getStringExtra(EXTRA_URL)
                ?: ExplorerRepository().getExplorer(it.hash.split("-")[0])
            binding.webView.loadUrl(url)
        }

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.web_explorer, menu)
        SentinelTorManager.getTorStateLiveData().observe(this) {
            menu.findItem(R.id.menu_web_tor).icon =
                ContextCompat.getDrawable(applicationContext, R.drawable.ic_tor_on)
            val icon = menu.findItem(R.id.menu_web_tor).icon
            when (it.state) {
                EnumTorState.STARTING -> {
                    icon?.setTint(ContextCompat.getColor(applicationContext, R.color.md_amber_300))
                }
                EnumTorState.ON -> {
                    icon?.setTint(ContextCompat.getColor(applicationContext, R.color.md_green_600))
                    // #241: ON fires at boot=5; only route through the proxy
                    // at full readiness. Until then this screen fetches
                    // nothing - the next state emission (listener published,
                    // boot=100) completes the load through the proxy.
                    if (SentinelTorManager.isTorReady()) {
                        setProxy()
                    }
                }
                EnumTorState.OFF -> {
                    menu.findItem(R.id.menu_web_tor).icon =
                        ContextCompat.getDrawable(applicationContext, R.drawable.ic_tor_disabled)
                    menu.findItem(R.id.menu_web_tor).icon?.setTint(
                        ContextCompat.getColor(
                            applicationContext,
                            R.color.md_grey_400
                        )
                    )
                }
                else -> {
                }
            }
        }
        return super.onCreateOptionsMenu(menu)
    }


    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
            }
            R.id.menu_web_copy_tx -> {
                tx?.hash?.let { copyText(it) }
            }
            R.id.menu_web_copy_url -> {
                binding.webView.url?.let { copyText(it) }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun copyText(string: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        val clipData = ClipData
                .newPlainText("", string)
        if (cm != null) {
            cm.setPrimaryClip(clipData)
            Toast.makeText(applicationContext, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /**
         * #107 tier 1: when present, load this URL instead of the
         * user's selected explorer. The entropy row's external
         * analysis link sets it; every other caller omits it and
         * gets unchanged explorer behavior.
         */
        const val EXTRA_URL = "com.samourai.sentinel.ui.webview.EXTRA_URL"
    }
}