/**
 * Client-side pre-validation of group metadata (feature 006, T026;
 * FR-001/FR-007): the title is trimmed at both ends, then must be
 * non-empty and at most 64 characters after trim; the optional
 * description is at most 256 characters after trim — the same rules
 * the server enforces on №27 and №29 (400 invalid_title /
 * invalid_description). Shared by the creation dialog (T026) and the
 * rename form (T053): an invalid draft never reaches the API.
 */

export const GROUP_TITLE_MAX_LENGTH = 64

export const GROUP_DESCRIPTION_MAX_LENGTH = 256

export type GroupTitleValidation =
  { readonly ok: true; readonly title: string } | { readonly ok: false; readonly error: string }

export function validateGroupTitle(raw: string): GroupTitleValidation {
  const title = raw.trim()
  if (title.length === 0) {
    return {
      ok: false,
      error: `Название группы обязательно: укажите от 1 до ${GROUP_TITLE_MAX_LENGTH} символов`,
    }
  }
  if (title.length > GROUP_TITLE_MAX_LENGTH) {
    return {
      ok: false,
      error: `Название слишком длинное: ${title.length} из ${GROUP_TITLE_MAX_LENGTH} допустимых символов`,
    }
  }
  return { ok: true, title }
}

/**
 * The description is optional: a whitespace-only draft is treated as
 * absent (the field is simply omitted from №27/№29), a non-empty one
 * is sent trimmed and must fit the 256-character limit.
 */
export type GroupDescriptionValidation =
  | { readonly ok: true; readonly description: string | undefined }
  | { readonly ok: false; readonly error: string }

export function validateGroupDescription(raw: string): GroupDescriptionValidation {
  const description = raw.trim()
  if (description.length > GROUP_DESCRIPTION_MAX_LENGTH) {
    return {
      ok: false,
      error: `Описание слишком длинное: ${description.length} из ${GROUP_DESCRIPTION_MAX_LENGTH} допустимых символов`,
    }
  }
  return { ok: true, description: description.length > 0 ? description : undefined }
}
