package com.pocketnode.ui.lightning

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders a QR code for the given data string.
 * Uses ZXing for encoding — no network or Play Services needed.
 */
@Composable
fun QrCodeImage(
    data: String,
    modifier: Modifier = Modifier,
    size: Int = 280
) {
    val bitmap = remember(data) { generateQrBitmap(data, size) }
    if (bitmap != null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.size(size.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

private fun generateQrBitmap(data: String, size: Int): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
