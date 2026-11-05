package app.keepfin.core.music

import java.text.Normalizer

internal actual fun nfkd(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
