package com.mirador.app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Filtre DNS local : un "VPN" qui ne fait passer QUE les requêtes DNS par Mirador.
 * Les domaines de la liste noire reçoivent "n'existe pas" ; les autres sont
 * transmis au DNS Cloudflare (1.1.1.1). Le reste du trafic n'est pas touché.
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.mirador.app.STOP_DNS"
        private const val VPN_ADDR = "10.111.222.2"
        private const val VPN_DNS = "10.111.222.1"
        private const val UPSTREAM = "1.1.1.1"

        @Volatile var running = false
            private set
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var pool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null) startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (!DnsBlocklist.loaded) DnsBlocklist.load(this)
        val builder = Builder()
            .setSession("Mirador – filtre DNS")
            .addAddress(VPN_ADDR, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)
        if (Build.VERSION.SDK_INT >= 29) builder.setBlocking(true)
        val fd = builder.establish() ?: return
        tun = fd
        running = true
        pool = Executors.newFixedThreadPool(4)
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        worker = Thread({ loop(input, output) }, "dns-filter").apply { start() }
    }

    private fun loop(input: FileInputStream, output: FileOutputStream) {
        try {
            readLoop(input, output)
        } catch (_: InterruptedException) {
        }
    }

    private fun readLoop(input: FileInputStream, output: FileOutputStream) {
        val buf = ByteArray(32767)
        while (running) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (!running) break
                Thread.sleep(20)
                continue
            }
            if (n <= 0) {
                Thread.sleep(10)
                continue
            }
            val q = DnsPacket.parse(buf.copyOf(n)) ?: continue
            if (DnsBlocklist.isBlocked(q.name)) {
                DnsBlocklist.record(q.name)
                write(output, DnsPacket.buildResponse(q, DnsPacket.nxdomain(q.dns)))
            } else {
                pool?.execute { forward(q, output) }
            }
        }
    }

    private fun forward(q: DnsQuery, output: FileOutputStream) {
        val s = DatagramSocket()
        try {
            protect(s) // ce socket sort directement, sans repasser par le VPN
            s.soTimeout = 5000
            s.send(DatagramPacket(q.dns, q.dns.size, InetAddress.getByName(UPSTREAM), 53))
            val rb = ByteArray(4096)
            val rp = DatagramPacket(rb, rb.size)
            s.receive(rp)
            write(output, DnsPacket.buildResponse(q, rb.copyOf(rp.length)))
        } catch (_: Exception) {
        } finally {
            s.close()
        }
    }

    private fun write(out: FileOutputStream, bytes: ByteArray) {
        try {
            synchronized(out) { out.write(bytes) }
        } catch (_: IOException) {
        }
    }

    private fun stopVpn() {
        running = false
        pool?.shutdownNow()
        pool = null
        runCatching { tun?.close() }
        tun = null
        worker?.interrupt()
        worker = null
    }

    override fun onRevoke() {
        Store.setDnsEnabled(this, false)
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
