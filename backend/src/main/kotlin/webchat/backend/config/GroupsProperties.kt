package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Group-chat limits of 006-group-chats, bound from `groups.*`
 * (application.yml). Values mirror the public contract constants
 * (api-contract.md header, FR-001/FR-002): the group size cap including
 * the owner ([maxMembers] — `409 group_full` when active + batch
 * exceeds it), and the metadata validation bounds — group title after
 * trim (1..titleMaxLength) and optional description
 * (descriptionMaxLength). Consumed by GroupService/GroupRolePolicy
 * value objects; asserted by IT T018a (GroupLimitsIT, lowered
 * `groups.max-members` via SPRING_APPLICATION_JSON).
 */
@ConfigurationProperties(prefix = "groups")
data class GroupsProperties(
    val maxMembers: Int,
    val titleMaxLength: Int,
    val descriptionMaxLength: Int,
)
