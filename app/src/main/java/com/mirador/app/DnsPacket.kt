package com.mirador.app

/** Requête DNS (IPv4/UDP) extraite d'un paquet lu sur l'interface VPN. */
class DnsQuery(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val dns: ByteArray,
    val name: String,
)

/** Lecture / écriture minimale de paquets IPv4 + UDP + DNS. */
object DnsPacket {

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte()
        b[i + 1] = v.toByte()
    }

    fun parse(p: ByteArray): DnsQuery? {
        if (p.size < 28) return null
        if ((p[0].toInt() shr 4) and 0xF != 4) return null // IPv4 uniquement
        val ihl = (p[0].toInt() and 0xF) * 4
        if (p[9].toInt() != 17) return null // UDP
        if (p.size < ihl + 8) return null
        val srcPort = u16(p, ihl)
        val dstPort = u16(p, ihl + 2)
        if (dstPort != 53) return null
        val udpLen = u16(p, ihl + 4)
        val start = ihl + 8
        val end = minOf(p.size, ihl + udpLen)
        if (end - start < 12) return null
        val dns = p.copyOfRange(start, end)
        val name = readName(dns, 12) ?: return null
        return DnsQuery(p.copyOfRange(12, 16), p.copyOfRange(16, 20), srcPort, dstPort, dns, name)
    }

    /** Lit le nom de la 1re question ; renvoie aussi la position de fin via questionEnd. */
    private fun readName(d: ByteArray, start: Int): String? {
        var i = start
        val sb = StringBuilder()
        while (i < d.size) {
            val len = d[i].toInt() and 0xFF
            if (len == 0) return sb.toString().lowercase()
            if (len and 0xC0 != 0) return null
            if (i + 1 + len > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        return null
    }

    private fun questionEnd(d: ByteArray): Int? {
        var i = 12
        while (i < d.size) {
            val len = d[i].toInt() and 0xFF
            if (len == 0) return (i + 1 + 4).takeIf { it <= d.size }
            i += 1 + len
        }
        return null
    }

    /** Réponse « ce domaine n'existe pas » (NXDOMAIN). */
    fun nxdomain(q: ByteArray): ByteArray {
        val r = q.copyOf(questionEnd(q) ?: q.size)
        r[2] = (0x80 or (q[2].toInt() and 0x01)).toByte() // QR=1, RD recopié
        r[3] = 0x83.toByte() // RA=1, RCODE=3
        for (k in 6 until 12) r[k] = 0 // aucune réponse
        return r
    }

    /** Construit le paquet IPv4/UDP de réponse vers l'appli qui a posé la question. */
    fun buildResponse(q: DnsQuery, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val b = ByteArray(total)
        b[0] = 0x45
        put16(b, 2, total)
        put16(b, 6, 0x4000) // ne pas fragmenter
        b[8] = 64 // TTL
        b[9] = 17 // UDP
        System.arraycopy(q.dstIp, 0, b, 12, 4)
        System.arraycopy(q.srcIp, 0, b, 16, 4)
        put16(b, 10, checksum(b, 0, 20))
        put16(b, 20, q.dstPort)
        put16(b, 22, q.srcPort)
        put16(b, 24, 8 + payload.size)
        put16(b, 26, 0) // somme UDP facultative en IPv4
        System.arraycopy(payload, 0, b, 28, payload.size)
        return b
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
