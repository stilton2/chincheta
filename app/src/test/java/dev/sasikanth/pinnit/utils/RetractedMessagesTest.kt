package dev.sasikanth.pinnit.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RetractedMessagesTest {

  @Test
  fun `detects WhatsApp deleted message placeholders`() {
    assertThat(RetractedMessages.isPlaceholder("com.whatsapp", "Se eliminó este mensaje")).isTrue()
    assertThat(RetractedMessages.isPlaceholder("com.whatsapp", "Este mensaje fue eliminado.")).isTrue()
    assertThat(RetractedMessages.isPlaceholder("com.whatsapp", "This message was deleted")).isTrue()
    assertThat(RetractedMessages.isPlaceholder("com.whatsapp.w4b", "🚫 Se eliminó este mensaje")).isTrue()
  }

  @Test
  fun `detects placeholder in group chats with sender prefix`() {
    assertThat(RetractedMessages.isPlaceholder("com.whatsapp", "Ana: Se eliminó este mensaje")).isTrue()
    assertThat(RetractedMessages.senderPrefix("Ana: Se eliminó este mensaje")).isEqualTo("Ana: ")
    assertThat(RetractedMessages.senderPrefix("Se eliminó este mensaje")).isNull()
  }

  @Test
  fun `ignores normal messages and other apps`() {
    assertThat(RetractedMessages.isPlaceholder("com.whatsapp", "Te dije que se eliminó este mensaje ayer")).isFalse()
    assertThat(RetractedMessages.isPlaceholder("com.whatsapp", "Hola, ¿qué tal?")).isFalse()
    assertThat(RetractedMessages.isPlaceholder("org.telegram.messenger", "Se eliminó este mensaje")).isFalse()
    assertThat(RetractedMessages.isPlaceholder("com.whatsapp", null)).isFalse()
  }
}
