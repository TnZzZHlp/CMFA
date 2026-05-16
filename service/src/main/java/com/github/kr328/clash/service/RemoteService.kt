package com.github.kr328.clash.service

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.wrap
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.cancelAndJoinBlocking

class RemoteService : BaseService(), IRemoteService {
    private val binder = this.wrap()

    private var clash: ClashManager? = null
    private var profile: ProfileManager? = null
    private var clashBinder: IClashManager? = null
    private var profileBinder: IProfileManager? = null

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()

        clash = ClashManager(this)
        profile = ProfileManager(this)
        clashBinder = clash?.wrap() as IClashManager?
        profileBinder = profile?.wrap() as IProfileManager?

        registerWifiMonitor()
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterWifiMonitor()

        clash?.cancelAndJoinBlocking()
        profile?.cancelAndJoinBlocking()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun clash(): IClashManager {
        return clashBinder!!
    }

    override fun profile(): IProfileManager {
        return profileBinder!!
    }

    private fun registerWifiMonitor() {
        val connectivity = getSystemService<ConnectivityManager>() ?: return
        val request = NetworkRequest.Builder().apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }.build()

        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("WifiSsidMonitor onAvailable $network")
                checkAndRestart()
            }

            override fun onLost(network: Network) {
                Log.d("WifiSsidMonitor onLost $network")
                checkAndRestart()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                Log.d("WifiSsidMonitor onCapabilitiesChanged $network")
                checkAndRestart()
            }
        }

        connectivity.registerNetworkCallback(request, wifiCallback!!)
        checkAndRestart()
        Log.d("WifiSsidMonitor registered")
    }

    private fun unregisterWifiMonitor() {
        wifiCallback?.let {
            getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(it)
        }
        wifiCallback = null
    }

    private fun checkAndRestart() {
        val store = ServiceStore(this)
        if (!store.wifiSsidEnabled) return
        if (StatusProvider.serviceRunning) return

        val wifiManager = getSystemService<WifiManager>() ?: return
        val ssid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")

        if (ssid != null && ssid != "<unknown ssid>" && ssid in store.wifiSsidList) {
            Log.d("WifiSsidMonitor still on blocked SSID: $ssid")
            return
        }

        Log.i("WifiSsidMonitor starting VPN, SSID: $ssid")
        try {
            if (VpnService.prepare(this) != null) {
                Log.w("WifiSsidMonitor VPN not authorized")
                return
            }
            startForegroundServiceCompat(TunService::class.intent)
        } catch (e: Exception) {
            Log.w("WifiSsidMonitor start failed", e)
        }
    }
}