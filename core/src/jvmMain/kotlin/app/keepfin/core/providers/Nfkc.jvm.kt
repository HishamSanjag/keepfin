package app.keepfin.core.providers

import java.text.Normalizer

internal actual fun nfkc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
