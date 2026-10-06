package com.netino.vpn.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * zxing's default CaptureActivity is landscape-only, which made the phone rotate while scanning.
 * This subclass is declared portrait in the manifest.
 */
class PortraitCaptureActivity : CaptureActivity()

object QrImage {
    /** QR code of [text] (black on white, small quiet zone); null if it doesn't fit in a QR code. */
    fun encode(text: String, size: Int = 720): Bitmap? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Decodes a QR code from a picked image (screenshots of configs shared in Telegram etc.). */
    fun decode(context: Context, uri: Uri): String? = runCatching {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        decode(scaled(bmp))
    }.getOrNull()

    private fun scaled(b: Bitmap): Bitmap {
        val max = 1600
        if (b.width <= max && b.height <= max) return b
        val f = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private fun decode(b: Bitmap): String? {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val source = RGBLuminanceSource(b.width, b.height, px)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        val reader = MultiFormatReader().apply { setHints(hints) }
        // Try normal, then inverted (white-on-dark QR codes)
        return runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()
            ?: runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }.getOrNull()
    }
}
