/**
 * Client-side pre-validation of message text (feature 004, T026;
 * FR-003): the text is trimmed at both ends (inner whitespace is
 * preserved), then must be non-empty and at most MESSAGE_MAX_LENGTH
 * characters after trim — the same rule the server enforces
 * (400 text_blank / text_too_long). Shared by the composer
 * (MessageInput) and the outbox (T034): invalid text never reaches
 * the chat or the outbox (US1-4).
 */

export const MESSAGE_MAX_LENGTH = 4096

export type MessageTextValidation =
  { readonly ok: true; readonly text: string } | { readonly ok: false; readonly error: string }

export function validateMessageText(raw: string): MessageTextValidation {
  const text = raw.trim()
  if (text.length === 0) {
    return { ok: false, error: 'Сообщение не может быть пустым' }
  }
  if (text.length > MESSAGE_MAX_LENGTH) {
    return {
      ok: false,
      error: `Сообщение слишком длинное: ${text.length} из ${MESSAGE_MAX_LENGTH} допустимых символов`,
    }
  }
  return { ok: true, text }
}
