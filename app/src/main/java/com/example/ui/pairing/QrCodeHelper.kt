package com.example.ui.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

data class PairingQrPayload(
    val pairingId: String,
    val pairingCode: String,
    val primaryDeviceId: String,
    val primaryDeviceName: String,
    val primaryPublicKey: String
) {
    fun toUriString(): String {
        val json = JSONObject().apply {
            put("v", 1)
            put("pid", pairingId)
            put("code", pairingCode)
            put("did", primaryDeviceId)
            put("dname", primaryDeviceName)
            put("pk", primaryPublicKey)
        }.toString()
        return PREFIX + json
    }

    companion object {
        private const val PREFIX = "dualphonesms://"

        fun parse(raw: String): PairingQrPayload? {
            val body = raw.trim().removePrefix(PREFIX)
            return try {
                val obj = JSONObject(body)
                val pid = obj.getString("pid")
                val code = obj.getString("code")
                val did = obj.getString("did")
                val dname = obj.optString("dname", "Primary Phone")
                val pk = obj.getString("pk")
                if (pid.isBlank() || code.isBlank() || pk.isBlank()) null
                else PairingQrPayload(pid, code, did, dname, pk)
            } catch (_: Exception) {
                null
            }
        }
    }
}

object QrCodeHelper {
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }
    }
}
