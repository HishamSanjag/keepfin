package app.keepfin.core.copies

import java.text.Normalizer

internal actual fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
