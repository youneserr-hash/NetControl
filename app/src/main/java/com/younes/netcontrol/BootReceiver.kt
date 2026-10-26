package com.younes.netcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Relance le pare-feu après un redémarrage du téléphone s'il était activé. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Rules.isEnabled(ctx) && VpnService.prepare(ctx) == null) {
            FirewallVpnService.send(ctx, FirewallVpnService.ACTION_START)
        }
    }
}
