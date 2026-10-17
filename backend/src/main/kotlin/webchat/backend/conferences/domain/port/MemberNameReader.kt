package webchat.backend.conferences.domain.port

import java.util.UUID

/**
 * The member username read port of the conferences domain (T018): the
 * `name` claim of a №47 client token is the member's `username`
 * (livekit-integration.md §3 — «username пользователя»), resolved here
 * so a conference service never touches the 002 auth aggregate or the
 * users tables directly (the DIP discipline of
 * [ChatMembershipChecker]).
 *
 * `null` resolves only for a caller the `users` table does not know —
 * impossible for an authenticated chat member by the 002/006 foreign
 * keys; the mint treats it as the invariant breach it is.
 */
fun interface MemberNameReader {
    /** The `users.username` of [userId], or `null` when the row does not exist. */
    fun usernameOf(userId: UUID): String?
}
