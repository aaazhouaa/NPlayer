package com.nplayer.core.media.network

import com.nplayer.core.media.network.clients.FtpClient
import com.nplayer.core.media.network.clients.SftpClient
import com.nplayer.core.media.network.clients.SmbClient
import com.nplayer.core.media.network.clients.WebDavClient
import com.nplayer.core.media.network.keys.SshKeyStore
import com.nplayer.core.model.NetworkConnection
import com.nplayer.core.model.NetworkProtocol
import org.koin.core.annotation.Single

@Single
class DefaultNetworkClientFactory(private val sshKeyStore: SshKeyStore) : NetworkClientFactory {
    override fun create(connection: NetworkConnection): NetworkClient = when (connection.protocol) {
        NetworkProtocol.SMB -> SmbClient(connection = connection)

        NetworkProtocol.FTP -> FtpClient(connection = connection)

        NetworkProtocol.SFTP -> SftpClient(
            connection = connection,
            sshKeyStore = sshKeyStore,
        )

        NetworkProtocol.WEBDAV -> WebDavClient(connection = connection)
    }
}
