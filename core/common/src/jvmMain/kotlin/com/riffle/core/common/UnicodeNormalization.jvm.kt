package com.riffle.core.common

import java.text.Normalizer

actual fun normalizeToNfd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
