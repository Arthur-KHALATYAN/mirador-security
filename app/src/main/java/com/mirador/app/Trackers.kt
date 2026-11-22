package com.mirador.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class TrackerSeen(val addr: String, val type: String, val rssi: Int)

data class TrackerHistory(val addr: String, val type: String, val first: Long, val last: Long, val count: Int) {
    val minutes: Long get() = (last - first) / 60_000
    /** Vu au moins 3 fois sur 30 min ou plus : il vous accompagne. */
    val following: Boolean get() = count >= 3 && minutes >= 30
}

/** Détection des traqueurs Bluetooth (AirTag, SmartTag, Tile, Chipolo, Localiser de Google…). */
object Trackers {

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(c: Context) = permissions().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun bluetoothOn(c: Context) = c.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    private fun uuid16(u: java.util.UUID) = u.toString().substring(4, 8).lowercase()

    /** Type de traqueur reconnu, ou null. */
    fun identify(r: ScanResult): String? {
        val rec = r.scanRecord ?: return null
        rec.getManufacturerSpecificData(0x004C)?.let { d ->
            if (d.isNotEmpty() && d[0].toInt() == 0x12) return "Apple AirTag / réseau Localiser"
        }
        val uuids = rec.serviceUuids.orEmpty().map { uuid16(it.uuid) } +
            rec.serviceData.orEmpty().keys.map { uuid16(it.uuid) }
        return when {
            "fd5a" in uuids -> "Samsung SmartTag"
            "feed" in uuids || "feec" in uuids -> "Tile"
            "fe33" in uuids -> "Chipolo"
            "fcb2" in uuids -> "Traqueur compatible (Google / Apple)"
            "feaa" in uuids -> {
                val data = rec.serviceData.orEmpty().entries.firstOrNull { uuid16(it.key.uuid) == "feaa" }?.value
                if (data != null && data.isNotEmpty() && (data[0].toInt() == 0x40 || data[0].toInt() == 0x41)) "Google Localiser (Find My Device)" else null
            }
            else -> null
        }
    }

    /** Scan Bluetooth bloquant (à appeler hors du thread principal). */
    @SuppressLint("MissingPermission")
    fun scanBlocking(c: Context, durationMs: Long): List<TrackerSeen> {
        if (!hasPermissions(c) || !bluetoothOn(c)) return emptyList()
        val scanner = c.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return emptyList()
        val found = HashMap<String, TrackerSeen>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val type = identify(result) ?: return
                synchronized(found) { found[result.device.address] = TrackerSeen(result.device.address, type, result.rssi) }
            }
        }
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        main.post {
            runCatching {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            }.onFailure { done.countDown() }
        }
        main.postDelayed({
            runCatching { scanner.stopScan(cb) }
            done.countDown()
        }, durationMs)
        done.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        return synchronized(found) { found.values.toList() }
    }

    // ---------- Historique des observations (24 h) ----------

    fun history(c: Context): List<TrackerHistory> {
        val raw = c.getSharedPreferences("trackers", Context.MODE_PRIVATE).getString("h", "{}")
        val o = runCatching { JSONObject(raw!!) }.getOrDefault(JSONObject())
        return o.keys().asSequence().mapNotNull { k ->
            runCatching {
                val e = o.getJSONObject(k)
                TrackerHistory(k, e.getString("type"), e.getLong("first"), e.getLong("last"), e.getInt("n"))
            }.getOrNull()
        }.toList()
    }

    @Synchronized
    fun record(c: Context, seen: List<TrackerSeen>): List<TrackerHistory> {
        val now = System.currentTimeMillis()
        val map = history(c).filter { now - it.last < 24 * 3600_000L }.associateBy { it.addr }.toMutableMap()
        for (s in seen) {
            val old = map[s.addr]
            map[s.addr] = if (old == null) TrackerHistory(s.addr, s.type, now, now, 1)
            else old.copy(last = now, count = old.count + if (now - old.last > 4 * 60_000L) 1 else 0)
        }
        val o = JSONObject()
        map.values.forEach { o.put(it.addr, JSONObject().put("type", it.type).put("first", it.first).put("last", it.last).put("n", it.count)) }
        c.getSharedPreferences("trackers", Context.MODE_PRIVATE).edit().putString("h", o.toString()).apply()
        return map.values.toList()
    }

    /** Scan de fond : alerte si un traqueur inconnu vous suit. */
    fun backgroundCheck(c: Context) {
        val seen = scanBlocking(c, 10_000)
        val hist = record(c, seen)
        val ignored = Store.ignoredTrackers(c)
        val alerted = Store.alertedTrackers(c)
        val follow = hist.filter { it.following && it.addr !in ignored && it.addr !in alerted }
        for (t in follow) {
            Store.addLog(c, "trackers", "${t.type} vous suit depuis ${t.minutes} min")
            Notifier.alert(
                c, t.addr.hashCode(),
                "⚠️ Traqueur inconnu près de vous",
                "Un ${t.type} se déplace avec vous depuis ${t.minutes} min. Vérifiez vos sacs, poches, vêtements et votre voiture.",
                Intent(c, TrackerActivity::class.java),
            )
        }
        if (follow.isNotEmpty()) Store.setAlertedTrackers(c, alerted + follow.map { it.addr })
    }
}
