package com.lmalakar.onecr.privilege.embeddedadb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds this phone's own adbd services via mDNS — the same discovery adb itself
 * uses (adb_mdns.h):
 *   _adb-tls-pairing._tcp  — advertised while the "Pair device with pairing code"
 *                            dialog is open (port: pairing service)
 *   _adb-tls-connect._tcp  — the main wireless-debugging adbd port
 *
 * NSD needs no runtime permission and works on the local network segment.
 */
class AdbDeviceFinder(private val context: Context) {

    /** A discovered adb service instance. */
    data class FoundService(
        val name: String,
        val host: String,
        val port: Int,
        val kind: Kind,
    )

    enum class Kind { PAIRING, CONNECT }

    /**
     * Blocking discovery for [timeoutMs]. Returns every adb TLS service seen in
     * that window (usually exactly this device; a second debugged device may add
     * another instance).
     */
    fun discover(kind: Kind, timeoutMs: Long = 4000): List<FoundService> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val found = LinkedHashSet<FoundService>()
        val done = CountDownLatch(1)
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                done.countDown()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Resolve asynchronously; results race with the timeout window.
                runCatching {
                    nsd.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress ?: return
                                val port = info.port
                                if (host.isNotEmpty() && port > 0) {
                                    synchronized(found) {
                                        found.add(
                                            FoundService(info.serviceName ?: "", host, port, kind),
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        val type = when (kind) {
            Kind.PAIRING -> SERVICE_TYPE_PAIRING
            Kind.CONNECT -> SERVICE_TYPE_CONNECT
            else -> SERVICE_TYPE_CONNECT
        }
        val started = runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
        if (started.isFailure) return emptyList()

        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        return synchronized(found) { found.toList() }
    }

    companion object {
        const val SERVICE_TYPE_PAIRING = "adb-tls-pairing._tcp."
        const val SERVICE_TYPE_CONNECT = "adb-tls-connect._tcp."
    }
}
