package dev.agentrelay.ssh.android

import android.content.Context
import dev.agentrelay.connection.api.ConnectionProvider
import dev.agentrelay.ssh.api.SshConnectionManager
import dev.agentrelay.ssh.api.SshCredentialStore
import dev.agentrelay.ssh.api.SshHostKeyStore
import dev.agentrelay.ssh.api.SshProfileStore
import dev.agentrelay.ssh.api.SshConnectionProvider
import dev.agentrelay.ssh.jsch.JschSshConnector
import java.io.Closeable

class AndroidSshConnectionEnvironment private constructor(
    val provider: ConnectionProvider,
    val profiles: SshProfileStore,
    val credentials: SshCredentialStore,
    val hostKeys: SshHostKeyStore,
    val agentKeys: AndroidKeystoreAgentKeyManager,
) : Closeable {
    override fun close() = provider.close()

    companion object {
        fun create(context: Context): AndroidSshConnectionEnvironment {
            val applicationContext = context.applicationContext
            val documents = EncryptedFileDocumentStore(applicationContext)
            val profiles = AndroidSshProfileStore(documents)
            val credentials = AndroidKeystoreSshCredentialStore(documents)
            val hostKeys = AndroidSshHostKeyStore(documents)
            val agentKeys = AndroidKeystoreAgentKeyManager()
            val connector = JschSshConnector(
                agentIdentityProvider = AndroidKeystoreAgentIdentityProvider(agentKeys),
            )
            val manager = SshConnectionManager(
                profileStore = profiles,
                credentialStore = credentials,
                hostKeyStore = hostKeys,
                connector = connector,
            )
            return AndroidSshConnectionEnvironment(
                provider = SshConnectionProvider(
                    profileStore = profiles,
                    manager = manager,
                ),
                profiles = profiles,
                credentials = credentials,
                hostKeys = hostKeys,
                agentKeys = agentKeys,
            )
        }
    }
}
