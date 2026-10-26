package com.younes.netcontrol

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {

    data class AppItem(
        val pkg: String,
        val label: String,
        val icon: Drawable,
        val system: Boolean,
        var wifiBlocked: Boolean,
        var mobileBlocked: Boolean
    )

    private val REQ_VPN = 42
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var masterSwitch: Switch
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var showSystem: CheckBox
    private lateinit var list: ListView
    private lateinit var progress: ProgressBar

    private var allApps: List<AppItem> = emptyList()
    private val shown = ArrayList<AppItem>()
    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        masterSwitch = findViewById(R.id.masterSwitch)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        showSystem = findViewById(R.id.showSystem)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        list.adapter = adapter

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        masterSwitch.isChecked = Rules.isEnabled(this)
        masterSwitch.setOnCheckedChangeListener { _, on -> if (on) enableFirewall() else disableFirewall() }

        showSystem.isChecked = Rules.showSystem(this)
        showSystem.setOnCheckedChangeListener { _, v -> Rules.setShowSystem(this, v); filter() }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<Button>(R.id.blockAllWifi).setOnClickListener { setAll(wifi = true) }
        findViewById<Button>(R.id.blockAllMobile).setOnClickListener { setAll(wifi = false) }

        updateStatus()
        loadApps()
    }

    // --- Activation ---------------------------------------------------------------

    private fun enableFirewall() {
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, REQ_VPN) else onVpnGranted()
    }

    private fun onVpnGranted() {
        Rules.setEnabled(this, true)
        FirewallVpnService.send(this, FirewallVpnService.ACTION_START)
        updateStatus()
    }

    private fun disableFirewall() {
        Rules.setEnabled(this, false)
        FirewallVpnService.send(this, FirewallVpnService.ACTION_STOP)
        updateStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) onVpnGranted()
            else {
                masterSwitch.isChecked = false
                Toast.makeText(this, "Autorisation VPN refusée", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Si le VPN a été coupé depuis les réglages, refléter l'état réel
        if (masterSwitch.isChecked != Rules.isEnabled(this)) {
            masterSwitch.setOnCheckedChangeListener(null)
            masterSwitch.isChecked = Rules.isEnabled(this)
            masterSwitch.setOnCheckedChangeListener { _, on -> if (on) enableFirewall() else disableFirewall() }
        }
        updateStatus()
    }

    private fun updateStatus() {
        val w = Rules.blockedWifi(this).size
        val m = Rules.blockedMobile(this).size
        status.text = if (Rules.isEnabled(this))
            "Actif · $w bloquée(s) en Wi-Fi · $m en données mobiles"
        else
            "Désactivé · toutes les applications ont accès à internet"
    }

    private fun reload() {
        updateStatus()
        if (Rules.isEnabled(this)) FirewallVpnService.send(this, FirewallVpnService.ACTION_RELOAD)
    }

    // --- Liste des applications ---------------------------------------------------

    private fun loadApps() {
        progress.visibility = View.VISIBLE
        io.execute {
            val pm = packageManager
            val bw = Rules.blockedWifi(this)
            val bm = Rules.blockedMobile(this)
            val pkgs = if (Build.VERSION.SDK_INT >= 33)
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

            val apps = pkgs.asSequence()
                .filter { it.packageName != packageName }
                .filter { it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
                .mapNotNull { p ->
                    val ai = p.applicationInfo ?: return@mapNotNull null
                    AppItem(
                        pkg = p.packageName,
                        label = ai.loadLabel(pm).toString(),
                        icon = ai.loadIcon(pm),
                        system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                                 (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
                        wifiBlocked = p.packageName in bw,
                        mobileBlocked = p.packageName in bm
                    )
                }
                .sortedWith(compareBy({ !(it.wifiBlocked || it.mobileBlocked) }, { it.label.lowercase() }))
                .toList()

            runOnUiThread {
                allApps = apps
                progress.visibility = View.GONE
                filter()
            }
        }
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        val sys = showSystem.isChecked
        shown.clear()
        shown.addAll(allApps.filter {
            (sys || !it.system) && (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q))
        })
        adapter.notifyDataSetChanged()
    }

    /** Bloque (ou débloque si tout est déjà bloqué) toutes les apps affichées. */
    private fun setAll(wifi: Boolean) {
        val allBlocked = shown.all { if (wifi) it.wifiBlocked else it.mobileBlocked }
        val target = !allBlocked
        for (a in shown) {
            if (wifi) a.wifiBlocked = target else a.mobileBlocked = target
            Rules.setBlocked(this, a.pkg, wifi, target)
        }
        adapter.notifyDataSetChanged()
        reload()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            val app = shown[position]
            v.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            v.findViewById<TextView>(R.id.name).text = app.label
            v.findViewById<TextView>(R.id.pkg).text = app.pkg

            val wifiBtn = v.findViewById<TextView>(R.id.wifiBtn)
            val mobileBtn = v.findViewById<TextView>(R.id.mobileBtn)
            style(wifiBtn, "Wi-Fi", app.wifiBlocked)
            style(mobileBtn, "Données", app.mobileBlocked)

            wifiBtn.setOnClickListener {
                app.wifiBlocked = !app.wifiBlocked
                Rules.setBlocked(this@MainActivity, app.pkg, true, app.wifiBlocked)
                style(wifiBtn, "Wi-Fi", app.wifiBlocked)
                reload()
            }
            mobileBtn.setOnClickListener {
                app.mobileBlocked = !app.mobileBlocked
                Rules.setBlocked(this@MainActivity, app.pkg, false, app.mobileBlocked)
                style(mobileBtn, "Données", app.mobileBlocked)
                reload()
            }
            return v
        }

        private fun style(t: TextView, label: String, blocked: Boolean) {
            t.text = if (blocked) "✕ $label" else "✓ $label"
            t.setBackgroundResource(if (blocked) R.drawable.chip_blocked else R.drawable.chip_allowed)
            t.setTextColor(if (blocked) 0xFFB3261E.toInt() else 0xFF1B6E3A.toInt())
        }
    }
}
