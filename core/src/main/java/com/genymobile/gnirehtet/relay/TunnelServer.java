/*
 * Copyright (C) 2017 Genymobile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by LibreHU (2026): free port, secret sent first by the client, listener of departures.
 */

package com.genymobile.gnirehtet.relay;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Handle the connections from the clients.
 */
public class TunnelServer {

    private static final String TAG = TunnelServer.class.getSimpleName();

    /** Time a client has to send the secret, in ms. */
    private static final int SECRET_TIMEOUT_MS = 5000;

    private final List<Client> clients = new ArrayList<>();
    private final byte[] secret;
    private final CloseListener<Client> onClientGone;
    private final int port;

    public TunnelServer(int port, Selector selector) throws IOException {
        this(port, selector, null, null);
    }

    /**
     * port 0: a free port ({@link #getPort()}). secret: bytes a client must send first, before anything else (other
     * apps of the phone can reach 127.0.0.1 too); null: none. onClientGone: told when a client disconnects.
     */
    public TunnelServer(int port, Selector selector, byte[] secret, CloseListener<Client> onClientGone) throws IOException {
        this.secret = secret;
        this.onClientGone = onClientGone;
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        // ServerSocketChannel.bind() requires API 24
        serverSocketChannel.socket().bind(new InetSocketAddress(Inet4Address.getLoopbackAddress(), port));
        this.port = serverSocketChannel.socket().getLocalPort();

        SelectionHandler socketChannelHandler = (selectionKey) -> {
            try {
                ServerSocketChannel channel = (ServerSocketChannel) selectionKey.channel();
                acceptClient(selector, channel);
            } catch (IOException e) {
                Log.e(TAG, "Cannot accept client", e);
            }
        };
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT, socketChannelHandler);
    }

    public int getPort() {
        return port;
    }

    public int clientCount() {
        return clients.size();
    }

    private void acceptClient(Selector selector, ServerSocketChannel serverSocketChannel) throws IOException {
        SocketChannel socketChannel = serverSocketChannel.accept();
        if (socketChannel == null) {
            return;
        }
        if (secret != null && !readSecret(socketChannel)) {
            Log.w(TAG, "Client refused: wrong or missing secret");
            socketChannel.close();
            return;
        }
        socketChannel.configureBlocking(false);
        // will register the socket on the selector
        Client client = new Client(selector, socketChannel, this::removeClient);
        clients.add(client);
        Log.i(TAG, "Client #" + client.getId() + " connected");
    }

    /** The secret, read in blocking mode with a timeout (once per client, before it joins the selector). */
    private boolean readSecret(SocketChannel channel) {
        try {
            channel.configureBlocking(true);
            Socket socket = channel.socket();
            socket.setSoTimeout(SECRET_TIMEOUT_MS);
            byte[] received = new byte[secret.length];
            // not closed: that would close the channel
            new DataInputStream(socket.getInputStream()).readFully(received);
            socket.setSoTimeout(0);
            return MessageDigest.isEqual(received, secret);
        } catch (IOException e) {
            return false;
        }
    }

    private void removeClient(Client client) {
        clients.remove(client);
        Log.i(TAG, "Client #" + client.getId() + " disconnected");
        if (onClientGone != null) {
            onClientGone.onClosed(client);
        }
    }

    public void cleanUp() {
        for (Client client : clients) {
            client.cleanExpiredConnections();
        }
    }
}
