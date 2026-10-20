package com.netino.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.netino.desktop.L
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** QR codes: configs shown to the phone's camera, and read back from the screen, an image or the clipboard. */
object Qr {

    /** [text] as a QR image ([px] per module); null if it doesn't fit in a QR code. */
    fun encode(text: String, px: Int = 6): ImageBitmap? = encodeImage(text, px)?.toComposeImageBitmap()

    fun encodeImage(text: String, px: Int = 6): BufferedImage? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val img = BufferedImage(m.width * px, m.height * px, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val c = if (m[x, y]) 0x000000 else 0xFFFFFF
            for (dy in 0 until px) for (dx in 0 until px) img.setRGB(x * px + dx, y * px + dy, c)
        }
        img
    }.getOrNull()

    /** Every QR code in [img]'s text (a screen can show several). */
    fun decode(img: BufferedImage): List<String> = runCatching {
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(img.width, img.height, px)))
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(bitmap, hints).map { it.text }.distinct()
    }.getOrDefault(emptyList())

    /** QR codes visible on any screen (a config shown in Telegram, a browser, ...), at full resolution. */
    fun scanScreen(): List<String> = runCatching {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }.reduce { a, b -> a.union(b) }
        val shot = Robot().createMultiResolutionScreenCapture(Rectangle(bounds)).resolutionVariants.maxByOrNull { it.getWidth(null) }!!
        val img = BufferedImage(shot.getWidth(null), shot.getHeight(null), BufferedImage.TYPE_INT_RGB)
        img.graphics.apply { drawImage(shot, 0, 0, null); dispose() }
        decode(img)
    }.getOrDefault(emptyList())

    fun fromFile(f: File): List<String> = runCatching { ImageIO.read(f)?.let(::decode).orEmpty() }.getOrDefault(emptyList())

    /** An image copied to the clipboard (a screenshot of a QR code). */
    fun fromClipboard(): List<String> = runCatching {
        val c = Toolkit.getDefaultToolkit().systemClipboard
        if (!c.isDataFlavorAvailable(DataFlavor.imageFlavor)) return emptyList()
        val image = c.getData(DataFlavor.imageFlavor) as java.awt.Image
        val img = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB)
        img.graphics.apply { drawImage(image, 0, 0, null); dispose() }
        decode(img)
    }.getOrDefault(emptyList())

    fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/** A QR code to scan with the phone (Netino, v2rayNG, ...), with the text under it and a copy button. */
@Composable
fun QrDialog(title: String, text: String, note: String? = null, onDismiss: () -> Unit) {
    val image = remember(text) { Qr.encode(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.width(420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (image != null) Image(image, null, Modifier.size(320.dp).background(Color.White).padding(4.dp), filterQuality = FilterQuality.None)
                else Text(L.t("این متن برای QR بیش از حد بلند است؛ کپی‌اش کنید.", "Too long for a QR code; copy it instead."), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) ; Spacer(Modifier.height(6.dp)) }
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        },
        confirmButton = { TextButton(onClick = { Qr.copy(text) }) { Text(L.t("کپی", "Copy")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.t("بستن", "Close")) } },
    )
}
