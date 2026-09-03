package dev.agentrelay.ssh.api

import dev.agentrelay.connection.api.ConnectionProfileFieldId
import dev.agentrelay.connection.api.ConnectionProfileOperationId

/**
 * Stable identifiers used by the Secure Shell profile editor contract.
 *
 * UI adapters use these identifiers to provide platform-native localization while the SSH module
 * remains independent from Android resources. Field values that are not declared here, such as
 * configured jump-host profile ids and labels, are provider or user data and must remain verbatim.
 */
object SshConnectionProfileSchema {
    val PROFILE_LABEL = ConnectionProfileFieldId("profile-label")
    val HOST = ConnectionProfileFieldId("host-name")
    val PORT = ConnectionProfileFieldId("host-port")
    val USERNAME = ConnectionProfileFieldId("username")
    val JUMP_HOST = ConnectionProfileFieldId("jump-host")
    val AUTHENTICATION = ConnectionProfileFieldId("authentication")
    val PASSWORD = ConnectionProfileFieldId("password")
    val PRIVATE_KEY = ConnectionProfileFieldId("private-key")
    val PASSPHRASE_MODE = ConnectionProfileFieldId("passphrase-mode")
    val PASSPHRASE = ConnectionProfileFieldId("passphrase")
    val PUBLIC_KEY = ConnectionProfileFieldId("public-key")

    val INSTALL_PUBLIC_KEY = ConnectionProfileOperationId("install-public-key")
    val VERIFY_KEY_LOGIN = ConnectionProfileOperationId("verify-key-login")

    const val AUTHENTICATION_PASSWORD = "password"
    const val AUTHENTICATION_IMPORTED_KEY = "imported-key"
    const val AUTHENTICATION_AGENT_BACKED = "agent-backed"
    const val DIRECT_CONNECTION = "direct"
    const val PASSPHRASE_KEEP = "keep"
    const val PASSPHRASE_NONE = "none"
    const val PASSPHRASE_REPLACE = "replace"

    val FIELD_IDS = setOf(
        PROFILE_LABEL,
        HOST,
        PORT,
        USERNAME,
        JUMP_HOST,
        AUTHENTICATION,
        PASSWORD,
        PRIVATE_KEY,
        PASSPHRASE_MODE,
        PASSPHRASE,
        PUBLIC_KEY,
    )

    val OPERATION_IDS = setOf(INSTALL_PUBLIC_KEY, VERIFY_KEY_LOGIN)
}
