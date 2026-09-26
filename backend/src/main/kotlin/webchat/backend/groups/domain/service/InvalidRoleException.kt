package webchat.backend.groups.domain.service

/**
 * 400 `invalid_role` (api-contract.md №34): the submitted `role` is not
 * the contract enum `admin`|`member` — `owner` is deliberately NOT
 * assignable by this operation (ownership moves only via the №35
 * transfer), so it is refused here exactly like any unknown label. The
 * submitted value is never echoed (constitution V).
 *
 * Thrown by the №34 request validation (T016 `SetMemberRoleRequest`),
 * rendered by T017 (`GroupsExceptionHandler`); pinned red-first by
 * GroupsExceptionHandlerTest (T015a).
 */
class InvalidRoleException : RuntimeException("role must be one of: admin, member")
