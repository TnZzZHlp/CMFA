package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class WifiSsidObserveModule(service: Service) : Module<WifiSsidObserveModule.Event>(service) {
    sealed class Event {
        data object Matched : Event()
    }

    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private val wifiManager = service.getSystemService<WifiManager>()!!
    private val store = ServiceStore(service)

    private val request = NetworkRequest.Builder().apply {
        addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }.build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d("WifiSsidObserve onAvailable $network")
            checkSsid()
        }

        override fun onLost(network: Network) {
            Log.d("WifiSsidObserve onLost $network")
            checkSsid()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            Log.d("WifiSsidObserve onCapabilitiesChanged $network")
            checkSsid()
        }
    }

    private fun checkSsid() {
        if (!store.wifiSsidEnabled) return

        val ssid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: return
        if (ssid == "<unknown ssid>") return

        val list = store.wifiSsidList
        if (ssid in list) {
            Log.i("WifiSsidObserve matched, stopping VPN: $ssid")
            enqueueEvent(Event.Matched)
        }
    }

    override suspend fun run() {
        connectivity.registerNetworkCallback(request, callback)
        try {
            delay(Long.MAX_VALUE)
        } finally {
            withContext(NonCancellable) {
                connectivity.unregisterNetworkCallback(callback)
            }
        }
    }
}
