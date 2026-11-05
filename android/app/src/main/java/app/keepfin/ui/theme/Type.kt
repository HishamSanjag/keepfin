package app.keepfin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.keepfin.R

/*
 * Manrope (headings) and Inter (text): variable fonts bundled in res/font (SIL OFL, licence texts in
 * assets/third-party/NOTICES.txt). minSdk 26 supports variable font axes, so every weight comes from one file.
 */

@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, vararg weights: Int): FontFamily = FontFamily(
    weights.map { w ->
        Font(resId, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

val ManropeFamily: FontFamily = variable(R.font.manrope_variable, 400, 500, 600, 700, 800)
val InterFamily: FontFamily = variable(R.font.inter_variable, 400, 500, 600, 700)

/**
 * Scale from dev-docs/plans/MAIN-REDESIGN.md section 1 in sp: display (page titles; 34 on a phone), title 22/28,
 * subtitle 16/22, body 15/21, caption 12.5/16. Headings Manrope 600-800, text Inter 400-600.
 */
internal fun studioTypography(): Typography {
    val base = Typography()
    fun head(size: Float, line: Float, weight: Int, tracking: Float = 0f) =
        TextStyle(fontFamily = ManropeFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight), letterSpacing = tracking.sp)
    fun text(size: Float, line: Float, weight: Int, tracking: Float = 0f) =
        TextStyle(fontFamily = InterFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight), letterSpacing = tracking.sp)
    return base.copy(
        displayLarge = head(40f, 44f, 800, -0.8f),
        displayMedium = head(36f, 42f, 800, -0.6f),
        displaySmall = head(34f, 40f, 800, -0.6f),
        headlineLarge = head(30f, 36f, 700, -0.4f),
        headlineMedium = head(26f, 32f, 700, -0.3f),
        headlineSmall = head(24f, 30f, 700, -0.3f),
        titleLarge = head(22f, 28f, 700, -0.2f),
        titleMedium = head(16f, 22f, 700),
        titleSmall = head(14f, 20f, 700),
        bodyLarge = text(16f, 22f, 400),
        bodyMedium = text(15f, 21f, 400),
        bodySmall = text(12.5f, 16f, 400, 0.1f),
        labelLarge = text(14f, 20f, 600),
        labelMedium = text(12f, 16f, 600, 0.1f),
        labelSmall = text(11f, 14f, 600, 0.3f),
    )
}
