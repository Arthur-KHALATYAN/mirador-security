package com.mirador.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Scanner de QR code de Google (via Play Services) : aucune permission caméra
 * n'est nécessaire pour Mirador. Le lien est vérifié AVANT d'être ouvert.
 */
object QrScanner {
    fun scan(a: Activity) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(a, options).startScan()
            .addOnSuccessListener { b ->
                val value = b.url?.url ?: b.rawValue
                if (value.isNullOrBlank()) {
                    a.toast("QR code vide")
                } else if (value.contains('.') && !value.trim().contains(' ')) {
                    a.startActivity(Intent(a, MiradorActivity::class.java).putExtra(MiradorActivity.EXTRA_URL, value.trim()))
                } else {
                    AlertDialog.Builder(a)
                        .setTitle("Contenu du QR code (pas un lien)")
                        .setMessage(value)
                        .setPositiveButton("OK", null)
                        .showTr()
                }
            }
            .addOnFailureListener { a.toast("Scanner QR indisponible : ${it.message}") }
    }
}
