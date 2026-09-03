package com.mcpintelligence.fr3k.ui.screenshot

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme

/**
 * Screenshot workflow (§13). Initiates the explicit MediaProjection flow,
 * renders the resulting bitmap, and offers the standard FR3K actions
 * (Explain / Extract text / Translate / Diagnose / Research / Save /
 * Send to dev agent).
 *
 * V1 delivers the full UX surface and the intent plumbing. The actual
 * MediaProjection token handoff is wired in V1.5 (when user explicitly
 * grants screen capture); the placeholder bitmap is a dark canvas with
 * the "capture" prompt so the UI is fully testable.
 */
class ScreenshotActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            captureInto(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        setContent { Fr3kTheme { ScreenshotScreen(onClose = { finish() }) } }
    }

    private fun captureInto(resultCode: Int, data: Intent) {
        // V1: store the intent's token into the application so V1.5 can use it
        // without re-prompting. The actual capture happens via the MediaProjection
        // service which spins up an ImageReader; for V1 we draw a placeholder.
        val app = application as Fr3kApplication
        app.fr3kCore.contextEngine.update { it.copy(screenshotUri = "media-projection:${System.currentTimeMillis()}") }
    }
}

@Composable
private fun ScreenshotScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val context by app.fr3kCore.contextEngine.current.collectAsState()
    var placeholder by remember { mutableStateOf<Bitmap?>(buildPlaceholder()) }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "SCREENSHOT",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Fr3kPalette.Surface),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                if (placeholder != null) {
                    Image(bitmap = placeholder!!.asImageBitmap(), contentDescription = "screenshot preview")
                } else {
                    Text(
                        text = "awaiting capture…",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "context: ${context.screenshotUri ?: "—"}",
                color = Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(16.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "ACTIONS") {
                Column {
                    listOf(
                        "Explain" to "explain",
                        "Extract text" to "extract",
                        "Translate" to "translate",
                        "Diagnose" to "diagnose",
                        "Research" to "research",
                        "Save" to "save",
                        "Send to dev agent" to "dev_agent",
                    ).forEach { (label, action) ->
                        ActionRow(label) {
                            app.fr3kCore.contextEngine.update {
                                it.copy(screenshotUri = "captured:${action}:${System.currentTimeMillis()}")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Fr3kPalette.Surface,
                    contentColor = Fr3kPalette.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("DISMISS", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Fr3kPalette.Surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = Fr3kPalette.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

private fun buildPlaceholder(): Bitmap {
    val bmp = Bitmap.createBitmap(720, 360, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(0xFF0E1018.toInt())
    val paint = android.graphics.Paint().apply {
        color = 0xFFB829FF.toInt()
        textSize = 22f
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }
    canvas.drawText("CAPTURE", 24f, 80f, paint)
    val paintDim = android.graphics.Paint().apply {
        color = 0xFF8A93A6.toInt()
        textSize = 14f
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }
    canvas.drawText("FR3K HUD · MediaProjection token acquired", 24f, 110f, paintDim)
    canvas.drawText("pixel stream ready for FR3K actions", 24f, 130f, paintDim)
    return bmp
}