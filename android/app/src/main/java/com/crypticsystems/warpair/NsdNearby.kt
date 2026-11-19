package com.crypticsystems.warpair

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class NsdNearby(context: Context) {
    data class Peer(val serviceName: String, val host: String, val port: Int, val deviceName: String)

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val peers = ConcurrentHashMap<String, Peer>()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val discovering = AtomicBoolean(false)

    var onPeersChanged: ((List<Peer>) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null

    fun advertise(deviceName: String, port: Int = LanLink.DEFAULT_PORT) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = "WarPair-$deviceName".take(60)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) =
                postStatus("NSD register failed: $errorCode")
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) =
                postStatus("Advertising ${serviceInfo.serviceName} :$port")
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }
        registrationListener = listener
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (t: Throwable) { postStatus("NSD register error: ${t.message}") }
    }

    fun stopAdvertising() {
        val listener = registrationListener ?: return
        try { nsd.unregisterService(listener) } catch (_: Throwable) {}
        registrationListener = null
    }

    fun startDiscovery() {
        if (!discovering.compareAndSet(false, true)) return
        stopDiscoveryInternal(true)
        peers.clear()
        notifyPeers()
        try {
            val lock = wifi.createMulticastLock("WarPair-NSD").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        } catch (t: Throwable) {
            discovering.set(false)
            postStatus("Nearby discovery could not enable Wi-Fi multicast: ${t.message}")
            return
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering.set(false)
                releaseMulticastLock()
                postStatus("Nearby discovery failed to start (NSD $errorCode). Check Wi-Fi and permissions.")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                postStatus("Nearby discovery could not stop cleanly (NSD $errorCode).")
            }
            override fun onDiscoveryStarted(serviceType: String) =
                postStatus("Scanning this Wi-Fi network for WarPair peers…")
            override fun onDiscoveryStopped(serviceType: String) {
                discovering.set(false)
                releaseMulticastLock()
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("warpair", ignoreCase = true) ||
                    serviceInfo.serviceType == SERVICE_TYPE
                ) resolve(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peers.remove(serviceInfo.serviceName)
                notifyPeers()
            }
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            discovering.set(false)
            releaseMulticastLock()
            postStatus("Nearby discovery error: ${t.message}")
        }
    }

    fun stopDiscovery() = stopDiscoveryInternal(false)

    private fun stopDiscoveryInternal(keepFlag: Boolean) {
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null) {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Throwable) {}
        }
        if (!keepFlag) {
            discovering.set(false)
            releaseMulticastLock()
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        try { if (lock.isHeld) lock.release() } catch (_: Throwable) {}
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        try {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    postStatus("Found ${serviceInfo.serviceName}, but could not resolve it (NSD $errorCode).")
                }
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    if (host.contains(':')) return
                    val port = if (resolved.port > 0) resolved.port else LanLink.DEFAULT_PORT
                    val deviceName = resolved.serviceName.removePrefix("WarPair-").ifBlank { resolved.serviceName }
                    peers[resolved.serviceName] = Peer(resolved.serviceName, host, port, deviceName)
                    notifyPeers()
                    postStatus("Found ${peers.size} WarPair peer(s) on this Wi-Fi network.")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "resolve", t)
            postStatus("Could not resolve nearby WarPair device: ${t.message}")
        }
    }

    fun currentPeers(): List<Peer> = peers.values.sortedBy { it.deviceName }
    fun shutdown() { stopAdvertising(); stopDiscovery(); peers.clear() }
    private fun notifyPeers() { val list = currentPeers(); main.post { onPeersChanged?.invoke(list) } }
    private fun postStatus(msg: String) { main.post { onStatus?.invoke(msg) } }

    companion object {
        const val SERVICE_TYPE = "_warpair._tcp."
        private const val TAG = "WarPairNsd"
    }
}
