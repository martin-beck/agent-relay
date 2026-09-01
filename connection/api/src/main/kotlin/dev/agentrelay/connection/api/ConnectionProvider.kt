package dev.agentrelay.connection.api

import java.io.Closeable

interface ConnectionProvider : Closeable {
    val descriptor: ConnectionProviderDescriptor

    val profileManager: ConnectionProfileManager?
        get() = null

    suspend fun profiles(): List<ConnectionProfileSummary>

    fun connection(profileId: ConnectionProfileId): ManagedConnection
}

class ConnectionProviderRegistry(providers: Iterable<ConnectionProvider>) : Closeable {
    private val registered: Map<ConnectionProviderId, ConnectionProvider>

    init {
        val all = providers.toList()
        val duplicates = all.groupBy { it.descriptor.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Duplicate connection provider ids: " + duplicates.joinToString()
        }
        registered = all.associateBy { it.descriptor.id }
    }

    fun descriptors(): List<ConnectionProviderDescriptor> =
        registered.values.map { it.descriptor }.sortedBy { it.displayName }

    fun provider(id: ConnectionProviderId): ConnectionProvider = registered[id]
        ?: throw NoSuchElementException("No connection provider registered for $id")

    suspend fun profiles(): List<ConnectionProfileSummary> = registered.values
        .flatMap { it.profiles() }
        .sortedWith(
            compareBy<ConnectionProfileSummary> { it.providerId.value }
                .thenBy { it.label },
        )

    fun profileManager(providerId: ConnectionProviderId): ConnectionProfileManager =
        provider(providerId).profileManager
            ?: throw UnsupportedOperationException(
                "Connection provider does not support profile management",
            )

    override fun close() {
        registered.values.forEach(ConnectionProvider::close)
    }
}
