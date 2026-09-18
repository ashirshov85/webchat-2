package webchat.backend.auth.domain.model

/**
 * How a session was opened (data-model 003 §3): `password` login or an SSO
 * flow of an external identity provider. NULL (absent) = legacy password
 * rows of 002 written before the column existed — treated as password.
 */
enum class SessionAuthMethod {
    PASSWORD,
    SSO,
}
