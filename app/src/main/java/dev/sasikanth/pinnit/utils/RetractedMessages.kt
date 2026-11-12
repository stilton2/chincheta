package dev.sasikanth.pinnit.utils

/**
 * Detects the text WhatsApp puts in a notification when the sender deletes a message,
 * so the listener can mark the original message (which it kept) instead of saving this.
 */
object RetractedMessages {

  private val RETRACTING_APPS = setOf("com.whatsapp", "com.whatsapp.w4b")
  private val PLACEHOLDERS = listOf(
    "se eliminó este mensaje",
    "este mensaje fue eliminado",
    "mensaje eliminado",
    "this message was deleted"
  )

  fun isPlaceholder(sourcePackage: String, content: String?): Boolean {
    if (sourcePackage !in RETRACTING_APPS || content == null) return false
    val text = content.lowercase().trim().removeSuffix(".")
    // Group chats prefix the sender: "Ana: Se eliminó este mensaje"
    val message = text.substringAfter(": ", text)
    // Allow a leading symbol/emoji before the placeholder text.
    return PLACEHOLDERS.any { message.endsWith(it) && message.length <= it.length + 4 }
  }

  /** "Ana: " for group chats, null for one-to-one chats. */
  fun senderPrefix(content: String): String? =
    if (content.contains(": ")) content.substringBefore(": ") + ": " else null
}
