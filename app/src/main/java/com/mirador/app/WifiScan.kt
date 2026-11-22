package com.mirador.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

enum class DevKind(val label: String, val icon: Int, val tint: Int) {
    SELF("Ce téléphone", R.drawable.ic_phone, 0xFF6366F1.toInt()),
    ROUTER("Box / routeur", R.drawable.ic_wifi, 0xFF14B8A6.toInt()),
    CAMERA("Caméra", R.drawable.ic_camera, 0xFFF59E0B.toInt()),
    TV("TV / multimédia", R.drawable.ic_tv, 0xFF0EA5E9.toInt()),
    PRINTER("Imprimante", R.drawable.ic_print, 0xFF64748B.toInt()),
    COMPUTER("Ordinateur", R.drawable.ic_computer, 0xFF8B5CF6.toInt()),
    PHONE("Téléphone / tablette", R.drawable.ic_phone, 0xFF10B981.toInt()),
    IOT("Objet connecté", R.drawable.ic_devices, 0xFFEC4899.toInt()),
    UNKNOWN("Appareil inconnu", R.drawable.ic_devices, 0xFF94A3B8.toInt()),
}

/** Un appareil trouvé sur le réseau local. */
class NetDevice(val ip: String) {
    var name: String? = null          // nom annoncé (UPnP, mDNS) ou nom DNS
    var mdnsName: String? = null
    var mdnsHost: String? = null
    var dnsName: String? = null
    var vendor: String? = null
    var model: String? = null
    var server: String? = null        // en-tête « Server » (HTTP / UPnP)
    var title: String? = null         // titre de la page web de l'appareil
    var mac: String? = null
    val services = sortedSetOf<String>()
    val ports = sortedSetOf<Int>()
    var gateway = false
    var self = false
    var isNew = false
    var kind = DevKind.UNKNOWN

    /** Identifiant stable pour reconnaître l'appareil d'une analyse à l'autre. */
    val key: String get() = (mac ?: name)?.lowercase() ?: ip
    val label: String get() = name ?: model ?: kind.label
    val ipNum: Int get() = ip.substringAfterLast('.').toIntOrNull() ?: 0
}

data class WifiResult(val subnet: String, val devices: List<NetDevice>, val time: Long, val firstScan: Boolean)

/**
 * « Qui est sur mon Wi-Fi ? » : découverte des appareils du réseau local, sans serveur externe.
 * Combine sondage TCP, mDNS/Bonjour, SSDP/UPnP, nom DNS inverse et page web de l'appareil.
 */
object WifiScan {
    @Volatile var last: WifiResult? = null

    /** Ports qui servent à repérer qu'un appareil est présent (ouvert OU refusé = présent). */
    private val LIVE_PORTS = intArrayOf(80, 443, 22, 53, 139, 445, 554, 62078, 8080, 7000)

    /** Ports vérifiés ensuite sur les appareils trouvés, pour les identifier. */
    private val EXTRA_PORTS = intArrayOf(21, 23, 548, 631, 1883, 3389, 5000, 5900, 8000, 8008, 8009, 8443, 9100, 34567, 37777, 49152)

    val PORT_NAMES = mapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 53 to "DNS", 80 to "Web (HTTP)", 139 to "Partage Windows",
        443 to "Web (HTTPS)", 445 to "Partage Windows", 548 to "Partage Apple", 554 to "Flux vidéo (RTSP)",
        631 to "Impression (IPP)", 1883 to "MQTT (objets connectés)", 3389 to "Bureau à distance", 5000 to "UPnP / NAS",
        5900 to "VNC (prise en main)", 7000 to "AirPlay", 8000 to "Web / caméra", 8008 to "Chromecast", 8009 to "Chromecast",
        8080 to "Web (HTTP)", 8443 to "Web (HTTPS)", 9100 to "Impression", 34567 to "Enregistreur vidéo (DVR)",
        37777 to "Caméra Dahua", 49152 to "UPnP", 62078 to "Synchro iPhone / iPad",
    )

    private val MDNS_TYPES = listOf(
        "_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_ipp._tcp", "_printer._tcp", "_pdl-datastream._tcp",
        "_http._tcp", "_hap._tcp", "_spotify-connect._tcp", "_smb._tcp", "_device-info._tcp", "_companion-link._tcp",
        "_rtsp._tcp", "_workstation._tcp", "_amzn-wplay._tcp", "_sonos._tcp", "_androidtvremote2._tcp", "_matter._tcp",
        "_ssh._tcp", "_sftp-ssh._tcp",
    )

    class Net(val network: Network, val self: Inet4Address, val prefix: Int, val gateway: Inet4Address?)

    /** Réseau Wi-Fi actuel (hors VPN), ou null si le téléphone n'est pas sur un Wi-Fi. */
    fun currentWifi(c: Context): Net? {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: continue
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway as? Inet4Address
            return Net(n, la.address as Inet4Address, la.prefixLength, gw)
        }
        return null
    }

    private fun toInt(a: Inet4Address) = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
    private fun toAddr(i: Int) = InetAddress.getByAddress(
        byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()),
    ) as Inet4Address

    /** Adresses à analyser : le sous-réseau du téléphone, limité à 254 adresses (/24). */
    private fun hosts(net: Net): Pair<String, List<Inet4Address>> {
        val p = net.prefix.coerceIn(24, 30)
        val mask = -1 shl (32 - p)
        val base = toInt(net.self) and mask
        val size = 1 shl (32 - p)
        return "${toAddr(base).hostAddress}/$p" to (1 until size - 1).map { toAddr(base + it) }
    }

    /** true = port ouvert, false = appareil présent mais port fermé, null = pas de réponse. */
    private fun probe(net: Net, ip: InetAddress, port: Int, timeout: Int): Boolean? {
        val s = Socket()
        return try {
            net.network.bindSocket(s)
            s.connect(InetSocketAddress(ip, port), timeout)
            true
        } catch (e: ConnectException) {
            if (e.message?.contains("refused", true) == true) false else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { s.close() }
        }
    }

    fun scan(c: Context, progress: (String) -> Unit): WifiResult? {
        val net = currentWifi(c) ?: return null
        val (subnet, hosts) = hosts(net)
        val inLan = hosts.map { it.hostAddress!! }.toHashSet()
        val selfIp = net.self.hostAddress!!
        val gwIp = net.gateway?.hostAddress
        val devs = ConcurrentHashMap<String, NetDevice>()
        fun dev(ip: String) = devs.computeIfAbsent(ip) { NetDevice(it) }

        val wm = c.applicationContext.getSystemService(WifiManager::class.java)
        val lock = runCatching { wm?.createMulticastLock("mirador-scan")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val pool = Executors.newFixedThreadPool(96)
        try {
            // Annonces des appareils (mDNS / UPnP) en parallèle du sondage
            val mdns = pool.submit<Map<String, NetDevice>> { mdns(net, inLan) }
            val ssdp = pool.submit<Map<String, Pair<String?, String?>>> { ssdp(net, inLan) }

            progress("Recherche des appareils…")
            val live = hosts.flatMap { h -> LIVE_PORTS.map { p -> Triple(h.hostAddress!!, p, pool.submit<Boolean?> { probe(net, h, p, 450) }) } }
            for ((ip, p, f) in live) {
                val r = runCatching { f.get(8, TimeUnit.SECONDS) }.getOrNull() ?: continue
                val d = dev(ip)
                if (r) d.ports += p
            }

            // Ancien Android (≤ 9) : la table ARP donne aussi les adresses MAC
            runCatching { File("/proc/net/arp").readLines().drop(1) }.getOrNull()?.forEach { l ->
                val f = l.trim().split(Regex("\\s+"))
                if (f.size >= 4 && f[0] in inLan && f[3] != "00:00:00:00:00:00") dev(f[0]).mac = f[3].lowercase()
            }

            // Fusion des annonces mDNS (noms, services, modèles)
            runCatching { mdns.get(10, TimeUnit.SECONDS) }.getOrNull()?.forEach { (ip, m) ->
                val d = dev(ip)
                d.mdnsName = d.mdnsName ?: m.mdnsName
                d.mdnsHost = d.mdnsHost ?: m.mdnsHost
                d.model = d.model ?: m.model
                d.vendor = d.vendor ?: m.vendor
                d.services += m.services
            }
            // Fusion des réponses UPnP
            val ssdpMap = runCatching { ssdp.get(10, TimeUnit.SECONDS) }.getOrNull().orEmpty()
            for ((ip, v) in ssdpMap) dev(ip).let { it.server = it.server ?: v.first }
            dev(selfIp).self = true
            gwIp?.takeIf { it in inLan }?.let { dev(it).gateway = true }

            progress("Identification de ${devs.size} appareil(s)…")
            val found = devs.values.toList()
            val extra = found.flatMap { d ->
                EXTRA_PORTS.filter { it !in d.ports }.map { p -> Triple(d, p, pool.submit<Boolean?> { probe(net, InetAddress.getByName(d.ip), p, 700) }) }
            }
            // Description UPnP (nom, fabricant, modèle), page web et nom DNS de chaque appareil
            val upnp = ssdpMap.mapNotNull { (ip, v) ->
                val loc = v.second ?: return@mapNotNull null
                ip to pool.submit<Map<String, String>> { upnpDescription(net, ip, loc) }
            }
            val dns = found.map { d -> d to pool.submit<String?> { reverseDns(d.ip) } }
            for ((d, p, f) in extra) if (runCatching { f.get(8, TimeUnit.SECONDS) }.getOrNull() == true) d.ports += p
            val web = devs.values.mapNotNull { d ->
                val port = intArrayOf(80, 8080, 8000).firstOrNull { it in d.ports } ?: return@mapNotNull null
                d to pool.submit<Pair<String?, String?>> { httpBanner(net, d.ip, port) }
            }
            for ((ip, f) in upnp) {
                val m = runCatching { f.get(6, TimeUnit.SECONDS) }.getOrNull() ?: continue
                val d = dev(ip)
                d.name = d.name ?: m["friendlyName"]
                d.vendor = m["manufacturer"] ?: d.vendor
                d.model = listOfNotNull(m["modelName"], m["modelDescription"]).firstOrNull() ?: d.model
            }
            for ((d, f) in dns) d.dnsName = runCatching { f.get(6, TimeUnit.SECONDS) }.getOrNull()
            for ((d, f) in web) runCatching { f.get(6, TimeUnit.SECONDS) }.getOrNull()?.let { (srv, t) ->
                d.server = d.server ?: srv
                d.title = t
            }
        } finally {
            pool.shutdownNow()
            runCatching { lock?.release() }
        }

        val list = devs.values.onEach { d ->
            d.name = listOf(d.name, d.mdnsName, d.dnsName, d.mdnsHost).firstOrNull { !it.isNullOrBlank() }?.trim()
            d.kind = classify(d)
        }.toList()

        // Nouveaux appareils depuis la dernière analyse de CE réseau
        val netId = "${gwIp ?: "?"}|$subnet"
        val prev = Store.wifiSeen(c, netId)
        list.forEach { it.isNew = prev != null && !it.self && it.key !in prev }
        Store.setWifiSeen(c, netId, prev.orEmpty() + list.map { it.key })

        val res = WifiResult(subnet, list, System.currentTimeMillis(), prev == null)
        last = res
        return res
    }

    // ---------------------------------------------------------------- Identification

    fun classify(d: NetDevice): DevKind {
        if (d.self) return DevKind.SELF
        if (d.gateway) return DevKind.ROUTER
        val t = listOfNotNull(d.name, d.mdnsName, d.mdnsHost, d.dnsName, d.vendor, d.model, d.server, d.title, d.services.joinToString(" "))
            .joinToString(" ").lowercase()
        fun has(vararg k: String) = k.any { it in t }
        val p = d.ports
        return when {
            554 in p || 37777 in p || 34567 in p || has(
                "camera", "caméra", "ipcam", "webcam", "netcam", "hikvision", "dahua", "ezviz", "tapo c", "reolink", "foscam",
                "wyze", "amcrest", "imou", "vstarcam", "annke", "uniview", "arlo", "eufy", "blink", "_rtsp", "nvr", "dvr",
            ) -> DevKind.CAMERA
            has("freebox server", "livebox", "bbox", "sfr box", "fritz", "router", "routeur", "gateway", "openwrt", "mikrotik", "unifi", "access point", "repeater", "répéteur") -> DevKind.ROUTER
            9100 in p || 631 in p || has("printer", "imprimante", "_ipp", "_printer", "_pdl", "epson", "laserjet", "officejet", "deskjet", "brother", "canon", "kyocera", "xerox", "lexmark") -> DevKind.PRINTER
            8008 in p || 8009 in p || has(
                "chromecast", "googlecast", "android tv", "androidtv", "bravia", "tizen", "webos", "smart tv", "smarttv", "roku", "fire tv",
                "firetv", "amzn", "appletv", "apple tv", "sonos", "speaker", "echo", "homepod", "nest", "freebox player", "mediarenderer", "_raop", "_spotify",
            ) -> DevKind.TV
            62078 in p || has("iphone", "ipad", "android", "galaxy", "pixel", "redmi", "xiaomi", "oneplus", "huawei", "oppo", "realme", "_companion-link") -> DevKind.PHONE
            3389 in p || 445 in p || 139 in p || 548 in p || 5900 in p ||
                has("macbook", "imac", "mac mini", "desktop", "laptop", "windows", "_smb", "_workstation", "synology", "qnap", "nas") -> DevKind.COMPUTER
            1883 in p || has("_hap", "_matter", "tuya", "shelly", "sonoff", "tasmota", "espressif", "esp_", "hue", "yeelight", "meross", "tado", "netatmo", "somfy") -> DevKind.IOT
            7000 in p || has("_airplay") -> DevKind.TV
            else -> DevKind.UNKNOWN
        }
    }

    /** Services risqués ouverts sur l'appareil. */
    fun risks(d: NetDevice): List<String> = buildList {
        if (23 in d.ports) add("Telnet ouvert : accès non chiffré, souvent visé par les pirates")
        if (21 in d.ports) add("FTP ouvert : transfert non chiffré")
        if (5900 in d.ports && d.kind != DevKind.COMPUTER) add("Prise en main à distance (VNC) ouverte")
    }

    // ---------------------------------------------------------------- Nom DNS inverse

    private fun reverseDns(ip: String): String? = runCatching {
        val n = InetAddress.getByName(ip).canonicalHostName
        if (n == ip || n.isBlank()) null
        else n.substringBefore('.').takeIf { it.isNotBlank() && it.lowercase() != "localhost" }
    }.getOrNull()

    // ---------------------------------------------------------------- Page web de l'appareil

    private fun httpBanner(net: Net, ip: String, port: Int): Pair<String?, String?> {
        val con = net.network.openConnection(URL("http://$ip:$port/")) as HttpURLConnection
        return try {
            con.connectTimeout = 1500
            con.readTimeout = 1500
            con.instanceFollowRedirects = false
            con.setRequestProperty("User-Agent", "Mirador")
            val code = con.responseCode
            val server = con.getHeaderField("Server")
            val realm = con.getHeaderField("WWW-Authenticate")?.let { Regex("realm=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            val body = runCatching { (if (code < 400) con.inputStream else con.errorStream)?.use { readMax(it, 16_384) } }.getOrNull().orEmpty()
            val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(body)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")?.take(60)?.takeIf { it.isNotBlank() }
            server to (title ?: realm)
        } catch (e: Exception) {
            null to null
        } finally {
            con.disconnect()
        }
    }

    private fun readMax(i: java.io.InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < max) {
            val n = i.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    // ---------------------------------------------------------------- SSDP / UPnP

    /** IP → (en-tête SERVER, URL de description). */
    private fun ssdp(net: Net, lan: Set<String>): Map<String, Pair<String?, String?>> {
        val out = HashMap<String, Pair<String?, String?>>()
        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
        runCatching {
            DatagramSocket().use { s ->
                net.network.bindSocket(s)
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val end = System.currentTimeMillis() + 3500
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    s.soTimeout = (end - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                    val pk = DatagramPacket(buf, buf.size)
                    try { s.receive(pk) } catch (e: SocketTimeoutException) { break }
                    val ip = pk.address.hostAddress ?: continue
                    if (ip !in lan) continue
                    val headers = String(pk.data, 0, pk.length, Charsets.UTF_8).lines()
                        .mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().uppercase() to l.substring(it + 1).trim() } }
                        .toMap()
                    val prev = out[ip]
                    out[ip] = (prev?.first ?: headers["SERVER"]) to (prev?.second ?: headers["LOCATION"])
                }
            }
        }
        return out
    }

    /** Lit la description UPnP d'un appareil (uniquement sur sa propre adresse, jamais ailleurs). */
    private fun upnpDescription(net: Net, ip: String, location: String): Map<String, String> {
        val url = runCatching { URL(location) }.getOrNull() ?: return emptyMap()
        if (url.protocol != "http" || url.host != ip) return emptyMap()
        val con = net.network.openConnection(url) as HttpURLConnection
        return try {
            con.connectTimeout = 1500
            con.readTimeout = 2000
            con.instanceFollowRedirects = false
            val xml = con.inputStream.use { readMax(it, 65_536) }
            listOf("friendlyName", "manufacturer", "modelName", "modelDescription").mapNotNull { tag ->
                Regex("<$tag>([^<]{1,80})</$tag>").find(xml)?.groupValues?.get(1)?.let { tag to unescape(it.trim()) }
            }.filter { it.second.isNotBlank() }.toMap()
        } catch (e: Exception) {
            emptyMap()
        } finally {
            con.disconnect()
        }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    // ---------------------------------------------------------------- mDNS / Bonjour

    private fun mdns(net: Net, lan: Set<String>): Map<String, NetDevice> {
        val out = HashMap<String, NetDevice>()
        runCatching {
            DatagramSocket().use { s ->
                net.network.bindSocket(s)
                val q = mdnsQuery()
                val group = InetAddress.getByName("224.0.0.251")
                s.send(DatagramPacket(q, q.size, group, 5353))
                val end = System.currentTimeMillis() + 3500
                var resent = false
                val buf = ByteArray(9000)
                while (System.currentTimeMillis() < end) {
                    if (!resent && System.currentTimeMillis() > end - 2000) { s.send(DatagramPacket(q, q.size, group, 5353)); resent = true }
                    s.soTimeout = (end - System.currentTimeMillis()).toInt().coerceIn(1, 1000)
                    val pk = DatagramPacket(buf, buf.size)
                    try { s.receive(pk) } catch (e: SocketTimeoutException) { continue }
                    val ip = pk.address.hostAddress ?: continue
                    if (ip !in lan) continue
                    runCatching { parseMdns(pk.data.copyOf(pk.length), out.getOrPut(ip) { NetDevice(ip) }) }
                }
            }
        }
        return out
    }

    private fun mdnsQuery(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, MDNS_TYPES.size.toByte(), 0, 0, 0, 0, 0, 0))
        for (t in MDNS_TYPES) {
            "$t.local".split('.').forEach { val b = it.toByteArray(); out.write(b.size); out.write(b) }
            out.write(0)
            out.write(byteArrayOf(0, 12, 0x80.toByte(), 1)) // PTR, réponse en unicast demandée (QU), classe IN
        }
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Lit un nom DNS (avec compression) ; renvoie le nom et la position après le nom. */
    private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var i = start
        var end = -1
        var jumps = 0
        while (i < b.size) {
            val len = b[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if (len and 0xC0 == 0xC0) {
                if (i + 1 >= b.size || ++jumps > 20) break
                if (end < 0) end = i + 2
                i = ((len and 0x3F) shl 8) or (b[i + 1].toInt() and 0xFF)
                continue
            }
            if (i + 1 + len > b.size) break
            labels += String(b, i + 1, len, Charsets.UTF_8)
            i += 1 + len
        }
        return labels.joinToString(".") to (if (end >= 0) end else i)
    }

    private fun parseMdns(b: ByteArray, d: NetDevice) {
        if (b.size < 12) return
        val qd = u16(b, 4)
        val rr = u16(b, 6) + u16(b, 8) + u16(b, 10)
        var i = 12
        repeat(qd) { i = readName(b, i).second + 4 }
        repeat(rr) {
            if (i >= b.size) return
            val (name, next) = readName(b, i)
            i = next
            if (i + 10 > b.size) return
            val type = u16(b, i)
            val len = u16(b, i + 8)
            val rd = i + 10
            i = rd + len
            if (i > b.size) return
            when (type) {
                12 -> { // PTR : type de service → instance (« Salon._googlecast._tcp.local »)
                    val target = readName(b, rd).first
                    if (name.startsWith("_")) d.services += name.substringBefore("._")
                    val inst = target.substringBefore("._")
                    if (inst.isNotBlank() && !inst.startsWith("_") && d.mdnsName == null) d.mdnsName = inst
                }
                33 -> if (name.contains("._")) { // SRV : nom d'hôte de l'appareil
                    val host = readName(b, rd + 6).first.removeSuffix(".local").removeSuffix(".")
                    if (host.isNotBlank() && d.mdnsHost == null) d.mdnsHost = host
                    if (d.mdnsName == null) name.substringBefore("._").takeIf { it.isNotBlank() }?.let { d.mdnsName = it }
                    name.substringAfter("._", "").substringBefore("._").takeIf { it.isNotBlank() }?.let { d.services += "_$it" }
                }
                16 -> { // TXT : modèle, nom affiché, fabricant
                    var j = rd
                    while (j < rd + len) {
                        val l = b[j].toInt() and 0xFF
                        if (j + 1 + l > rd + len) break
                        val kv = String(b, j + 1, l, Charsets.UTF_8)
                        j += 1 + l
                        val k = kv.substringBefore('=').lowercase()
                        val v = kv.substringAfter('=', "").trim().takeIf { it.isNotBlank() && it.length <= 80 } ?: continue
                        when (k) {
                            "fn" -> d.mdnsName = v
                            "md", "model", "ty", "usb_mdl" -> if (d.model == null) d.model = v
                            "usb_mfg", "manufacturer" -> if (d.vendor == null) d.vendor = v
                        }
                    }
                }
            }
        }
    }
}
