package com.homerelay.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homerelay.app.qr.QrCodeGenerator

/**
 * Renders a QR matrix with pure Compose Canvas drawing — no bitmap allocation,
 * uses HomeRelay's own encoder (zero dependencies). On encode failure, renders
 * nothing and reports false via onEncoded.
 */
@Composable
fun QrImage(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
) {
    val matrix = remember(text) {
        runCatching { QrCodeGenerator.encodeText(text, QrCodeGenerator.Ecc.MEDIUM) }.getOrNull()
    }
    if (matrix == null) {
        Text(
            "QR unavailable for this text",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val quiet = 2 // modules of quiet zone (small screen; still decodable)
    val total = matrix.size + quiet * 2
    Canvas(
        modifier
            .size(size)
            .background(Color.White),
    ) {
        val module = this.size.width / total
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.getModule(x, y)) {
                    drawRect(
                        color = Color(0xFF0B1C1E),
                        topLeft = androidx.compose.ui.geometry.Offset(
                            (x + quiet) * module,
                            (y + quiet) * module,
                        ),
                        size = androidx.compose.ui.geometry.Size(module, module),
                    )
                }
            }
        }
    }
}
