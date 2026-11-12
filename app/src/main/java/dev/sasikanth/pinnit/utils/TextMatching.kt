package dev.sasikanth.pinnit.utils

import java.text.Normalizer

private val DIACRITICS = Regex("\\p{Mn}+")

/** Lowercase without accents, so "Código" matches "codigo". */
fun String.normalizedForSearch(): String =
  Normalizer.normalize(this, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().trim()
