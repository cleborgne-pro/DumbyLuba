package fr.luba.poc.gateway

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR code modules (true = dark), square, without the quiet zone (drawn as padding). */
object QrMatrix {
    fun encode(text: String): Array<BooleanArray> {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
    }

    /** Text of the QR code found in [source] (camera frame, test image); null if none can be read. */
    fun decode(source: LuminanceSource): String? = runCatching {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    }.getOrNull()
}

/** QR code drawn black on white (also in the dark theme, for the camera), with a quiet zone of four modules. */
@Composable
fun QrImage(text: String, description: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { QrMatrix.encode(text) }
    Canvas(modifier.aspectRatio(1f).background(Color.White).padding(12.dp).semantics { contentDescription = description }) {
        val count = modules.size
        val cell = size.minDimension / count
        for (y in 0 until count) for (x in 0 until count) if (modules[y][x])
            // Slight overlap: no hairline gaps between neighbouring modules once scaled.
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}
