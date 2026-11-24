package com.live.tv.remote;

import java.net.Socket;
import java.io.IOException;
import dadb.AdbConnection;
import dadb.AdbKeyPair;
import dadb.AdbStream;

/**
 * Version-pinned bridge to dadb 1.2.6's JVM-public connection API.
 * Live owns the socket so an unfinished authorization can be timed out or cancelled.
 */
final class DadbSocketConnection implements AutoCloseable {
    private final AdbConnection connection;

    private DadbSocketConnection(AdbConnection connection) { this.connection = connection; }

    static DadbSocketConnection connect(Socket socket, AdbKeyPair keyPair) {
        return new DadbSocketConnection(AdbConnection.Companion.connect(socket, keyPair));
    }

    AdbStream open(String destination) throws IOException { return connection.open(destination); }
    boolean supportsFeature(String feature) { return connection.supportsFeature(feature); }
    @Override public void close() { connection.close(); }
}
