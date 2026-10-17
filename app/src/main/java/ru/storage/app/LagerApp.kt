package ru.storage.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Подключает Firebase администратора (если он задал свой) раньше, чем что-либо обратится к базе. */
class LagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConnCfg.init(this)
        BWareDraft.app = this
        watchNetwork()
    }

    /** Как только появляется интернет — файлы из очереди (сканы накладных, фото) уходят на Google Диск. */
    private fun watchNetwork() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private fun push() {
                    try { Media.uploadPending(applicationContext, ProductDb.get(applicationContext)) } catch (e: Exception) { }
                }
                override fun onAvailable(network: Network) = push()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) push()
                }
            })
        } catch (e: Exception) {
        }
    }
}
