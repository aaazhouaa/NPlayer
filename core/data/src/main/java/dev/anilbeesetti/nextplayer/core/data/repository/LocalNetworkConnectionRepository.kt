package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.database.dao.NetworkConnectionDao
import dev.anilbeesetti.nextplayer.core.database.entities.NetworkConnectionEntity
import dev.anilbeesetti.nextplayer.core.model.NetworkAuthentication
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single
class LocalNetworkConnectionRepository(private val networkConnectionDao: NetworkConnectionDao) :
    NetworkConnectionRepository {

    override fun getConnections(): Flow<List<NetworkConnection>> =
        networkConnectionDao.getAll().map { entities -> entities.map { it.toModel() } }

    override suspend fun getConnection(id: Long): NetworkConnection? =
        networkConnectionDao.getById(id = id)?.toModel()

    override suspend fun upsert(connection: NetworkConnection): Long =
        networkConnectionDao.upsert(connection.toEntity())

    override suspend fun delete(id: Long) = networkConnectionDao.deleteById(id = id)

    private fun NetworkConnectionEntity.toModel() = NetworkConnection(
        id = id,
        name = name,
        protocol = storedEnumOrDefault(
            name = "protocol",
            value = protocol,
            fallback = NetworkProtocol.SMB,
        ),
        host = host,
        port = port,
        path = path,
        username = username,
        password = password,
        useHttps = useHttps,
        authentication = storedEnumOrDefault(
            name = "authentication",
            value = authentication,
            fallback = NetworkAuthentication.PASSWORD,
        ),
        privateKeyFileName = privateKeyFileName,
        privateKeyPassphrase = privateKeyPassphrase,
        hostKeyFingerprint = hostKeyFingerprint,
    )

    /**
     * Reads a stored enum name, logging instead of silently substituting [fallback]: an unknown
     * protocol or authentication value would otherwise turn a connection into a different one.
     */
    private inline fun <reified T : Enum<T>> NetworkConnectionEntity.storedEnumOrDefault(
        name: String,
        value: String,
        fallback: T,
    ): T = runCatching { enumValueOf<T>(value) }
        .onFailure { error ->
            Logger.logWarning(
                tag = TAG,
                message = "unknown $name \"$value\" stored for connection $id, using $fallback",
                throwable = error,
            )
        }
        .getOrDefault(fallback)

    private companion object {
        private const val TAG = "NetworkConnectionRepository"
    }

    private fun NetworkConnection.toEntity() = NetworkConnectionEntity(
        id = id,
        name = name,
        protocol = protocol.name,
        host = host,
        port = port,
        path = path,
        username = username,
        password = password,
        useHttps = useHttps,
        authentication = authentication.name,
        privateKeyFileName = privateKeyFileName,
        privateKeyPassphrase = privateKeyPassphrase,
        hostKeyFingerprint = hostKeyFingerprint,
    )
}
