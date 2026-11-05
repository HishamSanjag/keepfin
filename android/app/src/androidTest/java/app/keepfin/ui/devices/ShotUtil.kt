package app.keepfin.ui.devices

import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Renders a node of the composition to a PNG in the app's private files folder (pull it with run-as). It does not depend on what the
 * emulator's display is showing, so it works while the screen is black.
 */
internal fun saveNode(name: String, node: SemanticsNodeInteraction) {
    val bitmap = node.captureToImage().asAndroidBitmap()
    val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shots").also { it.mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    Log.i("saveShot", "saved $name")
}
