package com.younes.netcontrol

import android.content.Context

/**
 * Stockage des règles : pour chaque application, est-elle bloquée en Wi-Fi ? en données mobiles ?
 */
object Rules {
    private const val PREFS = "rules"
    private const val KEY_WIFI = "blocked_wifi"
    private const val KEY_MOBILE = "blocked_mobile"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SHOW_SYSTEM = "show_system"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun blockedWifi(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_WIFI, emptySet())!!.toSet()

    fun blockedMobile(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_MOBILE, emptySet())!!.toSet()

    fun setBlocked(ctx: Context, pkg: String, wifi: Boolean, blocked: Boolean) {
        val key = if (wifi) KEY_WIFI else KEY_MOBILE
        val set = (prefs(ctx).getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        if (blocked) set.add(pkg) else set.remove(pkg)
        prefs(ctx).edit().putStringSet(key, set).apply()
    }

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    fun showSystem(ctx: Context) = prefs(ctx).getBoolean(KEY_SHOW_SYSTEM, false)
    fun setShowSystem(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_SHOW_SYSTEM, v).apply()
}
