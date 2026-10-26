package com.younes.netcontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

/**
 * Pare-feu sans root basé sur VpnService.
 *
 * Principe : seules les applications à bloquer sont envoyées dans un tunnel VPN local
 * (addAllowedApplication). Le service lit les paquets du tunnel et les jette :
 * ces applications n'ont donc plus d'internet. Les autres applications ne passent
 * pas par le tunnel et fonctionnent normalement.
 *
 * La liste change selon le réseau actif : Wi-Fi -> règles "Wi-Fi", données mobiles -> règles "Mobile".
 */
class FirewallVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.younes.netcontrol.START"
        const val ACTION_STOP = "com.younes.netcontrol.STOP"
        const val ACTION_RELOAD = "com.younes.netcontrol.RELOAD"
        private const val CHANNEL = "firewall"
        private const val NOTIF_ID = 1
        private const val TAG = "NetControl"

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, FirewallVpnService::class.java).setAction(action)
            if (action == ACTION_START && Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    enum class NetType { WIFI, MOBILE, NONE }

    private var tun: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null
    private var currentNet = NetType.NONE
    private var currentBlocked: Set<String>? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (callback == null) startFirewall() else apply(force = true)
            }
            else -> startFirewall()
        }
        return START_STICKY
    }

    private fun startFirewall() {
        startInForeground()
        if (callback == null) registerNetworkWatcher()
        apply(force = true)
    }

    // --- Surveillance du réseau (Wi-Fi / mobile) ---------------------------------

    private fun registerNetworkWatcher() {
        val cm = getSystemService(ConnectivityManager::class.java)
        currentNet = detectNet(cm, cm.activeNetwork)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                onNetChanged(typeOf(caps))
            }
            override fun onLost(network: Network) {
                onNetChanged(detectNet(cm, cm.activeNetwork))
            }
        }
        // Le réseau par défaut de NOTRE appli n'est jamais le VPN (on ne s'y ajoute pas),
        // donc on voit le vrai réseau sous-jacent.
        cm.registerDefaultNetworkCallback(cb)
        callback = cb
    }

    private fun detectNet(cm: ConnectivityManager, n: Network?): NetType {
        val caps = n?.let { cm.getNetworkCapabilities(it) } ?: return NetType.NONE
        return typeOf(caps)
    }

    private fun typeOf(caps: NetworkCapabilities): NetType = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.MOBILE
        else -> NetType.NONE
    }

    @Synchronized
    private fun onNetChanged(t: NetType) {
        if (t == currentNet) return
        currentNet = t
        Log.i(TAG, "Réseau : $t")
        apply(force = false)
    }

    // --- Construction du tunnel -------------------------------------------------

    @Synchronized
    private fun apply(force: Boolean) {
        val blocked = when (currentNet) {
            NetType.WIFI -> Rules.blockedWifi(this)
            NetType.MOBILE -> Rules.blockedMobile(this)
            NetType.NONE -> emptySet()
        }.filter { it != packageName && isInstalled(it) }.toSet()

        if (!force && blocked == currentBlocked) return
        currentBlocked = blocked
        updateNotification(blocked.size)

        if (blocked.isEmpty()) {
            // IMPORTANT : un VPN sans liste d'apps capturerait TOUTES les apps. On ferme donc le tunnel.
            closeTun()
            return
        }

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress("10.111.222.1", 32)
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setBlocking(true)
            .setConfigureIntent(mainIntent())
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        for (pkg in blocked) {
            try { builder.addAllowedApplication(pkg) } catch (e: PackageManager.NameNotFoundException) { }
        }

        val newTun = try { builder.establish() } catch (e: Exception) {
            Log.e(TAG, "establish() a échoué", e); null
        }
        if (newTun == null) return // permission VPN retirée
        val old = tun
        tun = newTun
        startDrain(newTun)
        try { old?.close() } catch (_: Exception) { }
    }

    /** Lit et jette tous les paquets : c'est ce qui coupe internet aux apps bloquées. */
    private fun startDrain(pfd: ParcelFileDescriptor) {
        drainThread = Thread {
            val buf = ByteArray(32767)
            try {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    while (!Thread.currentThread().isInterrupted) {
                        if (input.read(buf) < 0) break
                    }
                }
            } catch (_: Exception) { /* tunnel fermé */ }
        }.apply { isDaemon = true; name = "tun-drain"; start() }
    }

    private fun closeTun() {
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        drainThread?.interrupt()
        drainThread = null
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getApplicationInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    private fun shutdown() {
        callback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) { }
        }
        callback = null
        currentBlocked = null
        closeTun()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onRevoke() {
        // Un autre VPN a pris la main ou l'utilisateur a coupé le VPN dans les réglages
        Rules.setEnabled(this, false)
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // --- Notification -----------------------------------------------------------

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(count: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Pare-feu", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val net = when (currentNet) {
            NetType.WIFI -> "Wi-Fi"
            NetType.MOBILE -> "Données mobiles"
            NetType.NONE -> "Hors ligne"
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
                else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Pare-feu actif · $net")
            .setContentText("$count application(s) bloquée(s) sur ce réseau")
            .setContentIntent(mainIntent())
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        val n = buildNotification(currentBlocked?.size ?: 0)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(count: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(count))
    }
}
