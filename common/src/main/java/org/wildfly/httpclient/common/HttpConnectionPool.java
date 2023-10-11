/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.httpclient.common;

import io.undertow.UndertowOptions;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.UndertowClient;
import io.undertow.connector.ByteBufferPool;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.XnioExecutor;
import org.xnio.XnioWorker;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pool of HTTP connections for a given host pool.
 *
 * @author Stuart Douglas
 * @author Flavia Rainone
 */
public class HttpConnectionPool implements Closeable {

    private final int maxConnections;
    private final int maxStreamsPerConnection;
    private final XnioWorker worker;
    private final ByteBufferPool byteBufferPool;
    private final OptionMap options;
    private final HostPool hostPool;
    private final long connectionIdleTimeout;

    private final Map<Object, ConcurrentLinkedDeque<ClientConnectionHolder>> connections = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<RequestHolder> pendingConnectionRequests = new ConcurrentLinkedDeque<>();
    private final AtomicInteger activeInvocationCount = new AtomicInteger();
    private final Map<SSLContext, UndertowXnioSsl> sslInstances = new ConcurrentHashMap<>();

    private final Object NULL_SSL_CONTEXT = new Object();
    private final PoolAuthenticationContext poolAuthenticationContext = new PoolAuthenticationContext();

    public HttpConnectionPool(int maxConnections, int maxStreamsPerConnection, XnioWorker worker, ByteBufferPool byteBufferPool, OptionMap options, HostPool hostPool, long connectionIdleTimeout) {
        this.maxConnections = maxConnections;
        this.maxStreamsPerConnection = maxStreamsPerConnection;
        this.worker = worker;
        this.byteBufferPool = byteBufferPool;
        this.hostPool = hostPool;
        this.connectionIdleTimeout = connectionIdleTimeout;
        final String hostname = hostPool.getUri().getHost();
        if ("https".equals(hostPool.getUri().getScheme()) && hostname != null) {
            try {
                final InetAddress address = hostPool.getAddress().getAddress();
                if (address.toString().charAt(0) != '/') {
                    // address not starting with / means hostname used, add the SNI option
                    options = OptionMap.builder().addAll(options).set(UndertowOptions.SSL_SNI_HOSTNAME, hostname).getMap();
                }
            } catch (UnknownHostException e) {
                // if exception the URI contains a hostname
                options = OptionMap.builder().addAll(options).set(UndertowOptions.SSL_SNI_HOSTNAME, hostname).getMap();
            }
        }
        this.options = options;
    }

    public void getConnection(ConnectionListener connectionListener, ErrorListener errorListener, boolean ignoreConnectionLimits, SSLContext sslContext) {
        pendingConnectionRequests.add(new RequestHolder(connectionListener, errorListener, ignoreConnectionLimits, sslContext));
        runPending();
    }

    public void returnConnection(ClientConnectionHolder connection) {
        activeInvocationCount.decrementAndGet();
        if (connection.getConnection().isOpen()) {
            connections.get(connection.sslContext == null ? NULL_SSL_CONTEXT : connection.sslContext).add(connection);
        }
        runPending();
    }

    protected ClientConnectionHolder createClientConnectionHolder(ClientConnection connection, URI uri, SSLContext sslContext) {
        return new ClientConnectionHolder(connection, uri, sslContext);
    }

    int getProtocolVersion() {
        return Protocol.LATEST;
    }

    private void runPending() {

        int count;
        do {
            count = activeInvocationCount.get();
            if (count == maxConnections) {
                return;
            }
        } while (!activeInvocationCount.compareAndSet(count, count + 1));
        RequestHolder next = pendingConnectionRequests.poll();
        if (next == null) {
            activeInvocationCount.decrementAndGet();
            return;
        }
        SSLContext sslContext = null;
        UndertowXnioSsl ssl = null;
        if (hostPool.getUri().getScheme().equals("https")) {
            sslContext = next.context;
            if (sslContext != null) {
                ssl = sslInstances.get(sslContext);
                if (ssl == null) {
                    sslInstances.put(sslContext, ssl = new UndertowXnioSsl(worker.getXnio(), OptionMap.EMPTY, sslContext));
                }
            }
        }
        Object key = sslContext == null ? NULL_SSL_CONTEXT : sslContext;
        ConcurrentLinkedDeque<ClientConnectionHolder> queue = connections.get(key);
        if (queue == null) {
            connections.putIfAbsent(key, new ConcurrentLinkedDeque<>());
            queue = connections.get(key);
        }
        for (; ; ) {
            ClientConnectionHolder existingConnection = queue.poll();
            if (existingConnection == null) {
                break;
            }
            if(!existingConnection.connection.isOpen()) {
                continue;
            }
            if (existingConnection.tryAcquire()) {
                next.connectionListener.done(existingConnection);
                return;
            }
        }

        HostPool.AddressResult hostPoolAddress = hostPool.getAddress();
        InetAddress address;
        try {
            address = hostPoolAddress.getAddress();
        } catch (UnknownHostException e) {
            next.errorListener.error(e);
            return;
        }

        try {

            final SSLContext context = sslContext;
            System.out.println("HttpConnectionPool: use UndertowClient to create connection, thread = " + Thread.currentThread().getName());
            UndertowClient.getInstance().connect(new ClientCallback<ClientConnection>() {
                @Override
                public void completed(ClientConnection result) {
                    System.out.println("HttpConnectionPool: use connection to process handler, thread = " + Thread.currentThread().getName());
                    result.getCloseSetter().set((ChannelListener<ClientConnection>) connections::remove);
                    ClientConnectionHolder clientConnectionHolder = createClientConnectionHolder(result, hostPoolAddress.getURI(), context);
                    clientConnectionHolder.tryAcquire(); //aways suceeds
                    next.connectionListener.done(clientConnectionHolder);
                }

                @Override
                public void failed(IOException e) {
                    hostPoolAddress.failed(); //notify the host pool that this host has failed
                    activeInvocationCount.decrementAndGet();
                    next.errorListener.error(e);
                }
            }, new URI(hostPoolAddress.getURI().getScheme(), hostPoolAddress.getURI().getUserInfo(), address.getHostAddress(), hostPoolAddress.getURI().getPort(), "/", null, null), worker, ssl, byteBufferPool, options);
        } catch (URISyntaxException e) {
            next.errorListener.error(e);
        }


    }

    @Override
    public void close() throws IOException {
        //TODO
    }

    public interface ConnectionListener {

        void done(ConnectionHandle connection);

    }

    public interface ErrorListener {

        void error(Exception e);

    }

    public interface ConnectionHandle {
        ClientConnection getConnection();

        void done(boolean close);

        URI getUri();

        PoolAuthenticationContext getAuthenticationContext();

        void sendRequest(ClientRequest request, ClientCallback<ClientExchange> callback);
    }


    private static class RequestHolder {
        final ConnectionListener connectionListener;
        final ErrorListener errorListener;
        final boolean ignoreConnectionLimits;
        final SSLContext context;

        private RequestHolder(ConnectionListener connectionListener, ErrorListener errorListener, boolean ignoreConnectionLimits, SSLContext context) {
            this.connectionListener = connectionListener;
            this.errorListener = errorListener;
            this.ignoreConnectionLimits = ignoreConnectionLimits;
            this.context = context;
        }
    }

    protected class ClientConnectionHolder implements ConnectionHandle {

        private volatile AtomicInteger state = new AtomicInteger();
        private final ClientConnection connection;
        private final URI uri;
        private volatile XnioExecutor.Key timeoutKey;
        private long timeout;
        private final SSLContext sslContext;

        // keep track if the connection is in use (!IN_USE = idle)
        private static final int IN_USE  = 1;
        // indicate this connection is closed
        private static final int CLOSED  = 1 << 1;

        private final Runnable timeoutTask = new Runnable() {
            @Override
            public void run() {
                timeoutKey = null;
                if (hasFlags(CLOSED)) {
                    return;
                }
                long time = System.currentTimeMillis();
                if (timeout > time) {
                    timeoutKey = connection.getIoThread().executeAfter(this, timeout - time, TimeUnit.MILLISECONDS);
                    return;
                }
                if (tryClose()) {
                    activeInvocationCount.decrementAndGet();
                    runPending(); //needed to avoid a very unlikely race
                }
            }
        };

        ClientConnectionHolder(ClientConnection connection, URI uri, SSLContext sslContext) {
            this.connection = connection;
            this.uri = uri;
            this.sslContext = sslContext;
        }

        final boolean tryClose() {
            if (setFlagsIfCleared(CLOSED, IN_USE)) {
                IoUtils.safeClose(connection);
                return true;
            }
            return false;
        }

        final boolean tryAcquire() {
            return setFlagsIfCleared(IN_USE, CLOSED);
        }

        @Override
        public ClientConnection getConnection() {
            return connection;
        }

        @Override
        public void done(boolean close) {
            if (!clearFlags(IN_USE)) {
                return;
            }
            if (close) {
                IoUtils.safeClose(connection);
            }
            timeout = System.currentTimeMillis() + connectionIdleTimeout;


            if (timeoutKey == null && connectionIdleTimeout > 0 && !close) {
                timeoutKey = connection.getIoThread().executeAfter(timeoutTask, connectionIdleTimeout, TimeUnit.MILLISECONDS);
            }
            returnConnection(this);
        }

        @Override
        public URI getUri() {
            return uri;
        }

        @Override
        public PoolAuthenticationContext getAuthenticationContext() {
            return poolAuthenticationContext;
        }

        @Override
        public void sendRequest(ClientRequest request, ClientCallback<ClientExchange> callback) {
            connection.sendRequest(request, callback);
        }

        protected final void setFlags(int flags) {
            int oldState;
            do {
                oldState = state.get();
                if ((oldState & flags) == flags || (oldState & CLOSED) == CLOSED) {
                    return;
                }
            } while (! state.compareAndSet(oldState, oldState | flags));
        }

        protected final boolean setFlagsIfCleared(int flags, int clearedFlags) {
            int oldState;
            do {
                oldState = state.get();
                if ((oldState & flags) == flags || (oldState & clearedFlags) == clearedFlags) {
                    return false;
                }
            } while (! state.compareAndSet(oldState, oldState | flags));
            return true;
        }

        protected final boolean clearFlags(int flags) {
            int oldState;
            do {
                oldState = state.get();
                if ((oldState & flags) == 0) {
                    return false;
                }
            } while (! state.compareAndSet(oldState, oldState & ~flags));
            return true;
        }

        protected final boolean hasFlags(int flags) {
            return (state.get() & flags) == flags;
        }
    }

}
