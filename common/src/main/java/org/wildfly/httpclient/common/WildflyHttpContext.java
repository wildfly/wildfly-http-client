/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022 Red Hat, Inc., and individual contributors
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
import io.undertow.connector.ByteBufferPool;
import io.undertow.server.DefaultByteBufferPool;
import org.wildfly.common.context.ContextManager;
import org.wildfly.common.context.Contextual;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.XnioWorker;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.security.AccessController.doPrivileged;

/**
 * Represents the current configured state of the HTTP contexts.
 *
 * @author Stuart Douglas
 * @author Flavia Rainone
 */
public class WildflyHttpContext implements Contextual<WildflyHttpContext> {

    private static final int LEAK_DETECTION = Integer.getInteger("org.wildfly.http-client.buffer-leak-detection", 0);

    /**
     * The context manager for HTTP endpoints.
     */
    static ContextManager<WildflyHttpContext> HTTP_CONTEXT_MANAGER = doPrivileged((PrivilegedAction<ContextManager<WildflyHttpContext>>) () -> {
        final ContextManager<WildflyHttpContext> contextManager = new ContextManager<>(WildflyHttpContext.class, "jboss-ejb-http-client.http-context");
        contextManager.setGlobalDefaultSupplierIfNotSet(ConfigurationHttpContextSupplier::new);
        return contextManager;
    });

    /**
     * TODO: figure out some way to remove these when all the connections are closed, it has the potential to be very racey
     */
    private final Map<URI, HttpTargetContext> uriConnectionPools = new ConcurrentHashMap<>();

    private final ConfigSection[] targets;

    private final int maxConnections;
    private final int maxStreamsPerConnection;
    private final long idleTimeout;
    private final boolean eagerlyAcquireAffinity;
    private final XnioWorker worker;
    private final ByteBufferPool pool;
    private final boolean enableHttp2;
    private final boolean tcpNoDelay;
    private final HttpConnectionPoolFactory httpConnectionPoolFactory;
    private final HttpMarshallerFactoryProvider httpMarshallerFactoryProvider;

    WildflyHttpContext(ConfigSection[] targets, int maxConnections, int maxStreamsPerConnection, long idleTimeout,
                       boolean eagerlyAcquireAffinity, XnioWorker worker, ByteBufferPool pool, boolean enableHttp2,
                       boolean tcpNoDelay, HttpConnectionPoolFactory httpConnectionPoolFactory,
                       HttpMarshallerFactoryProvider httpMarshallerFactoryProvider) {
        this.targets = targets;
        this.maxConnections = maxConnections;
        this.maxStreamsPerConnection = maxStreamsPerConnection;
        this.idleTimeout = idleTimeout;
        this.eagerlyAcquireAffinity = eagerlyAcquireAffinity;
        this.worker = worker;
        this.pool = pool;
        this.enableHttp2 = enableHttp2;
        this.tcpNoDelay = tcpNoDelay;
        this.httpConnectionPoolFactory = httpConnectionPoolFactory;
        this.httpMarshallerFactoryProvider = httpMarshallerFactoryProvider;
    }

    public static WildflyHttpContext getCurrent() {
        return HttpContextGetterHolder.SUPPLIER.get();
    }

    @Override
    public ContextManager<WildflyHttpContext> getInstanceContextManager() {
        return HTTP_CONTEXT_MANAGER;
    }

    public HttpTargetContext getTargetContext(final URI uri) {
        return getConnectionPoolForURI(uri);
    }

    private HttpTargetContext getConnectionPoolForURI(URI uri) {
        HttpTargetContext context = uriConnectionPools.get(uri);
        if (context != null) {
            context.init();
            return context;
        }
        for (ConfigSection target : targets) {
            if (target.getUri().equals(uri)) {
                target.getHttpTargetContext().init();
                uriConnectionPools.put(uri, target.getHttpTargetContext());
                return target.getHttpTargetContext();
            }
        }
        synchronized (this) {
            context = uriConnectionPools.get(uri);
            if (context != null) {
                return context;
            }
            HttpConnectionPool pool = httpConnectionPoolFactory.createHttpConnectionPool(
                    maxConnections, maxStreamsPerConnection, worker, this.pool,
                OptionMap.create(UndertowOptions.ENABLE_HTTP2, enableHttp2,
                    Options.TCP_NODELAY, tcpNoDelay), new HostPool(uri), idleTimeout);
            uriConnectionPools.put(uri, context = new HttpTargetContext(pool, eagerlyAcquireAffinity, uri, httpMarshallerFactoryProvider));
            context.init();
            return context;
        }
    }

    static class ConfigSection {
        private final HttpTargetContext httpTargetContext;
        private final URI uri;

        ConfigSection(HttpTargetContext httpTargetContext, URI uri) {
            this.httpTargetContext = httpTargetContext;
            this.uri = uri;
        }

        public HttpTargetContext getHttpTargetContext() {
            return httpTargetContext;
        }

        public URI getUri() {
            return uri;
        }
    }

    static class Builder {
        private InetSocketAddress defaultBindAddress;
        private long idleTimeout = 50000; //the server defaults to an idle timeout of 60 seconds, we default ours to 50 to prevent possible races
        private int maxConnections;
        private int maxStreamsPerConnection;
        private Boolean eagerlyAcquireSession;
        private final List<HttpConfigBuilder> targets = new ArrayList<>();
        private Boolean enableHttp2;
        private Boolean tcpNoDelay = false;

        private BufferBuilder bufferConfig;

        WildflyHttpContext build() {

            XnioWorker worker = XnioWorker.getContextManager().get();
            ByteBufferPool pool;
            if(bufferConfig == null) {
                pool = new DefaultByteBufferPool(true, 1024, 100, 0, LEAK_DETECTION); //TODO
            } else {
                pool = new DefaultByteBufferPool(bufferConfig.isDirect(), bufferConfig.getBufferSize(), bufferConfig.getMaxSize(), bufferConfig.getThreadLocalSize(), LEAK_DETECTION);
            }
            //TODO: ssl config
            ConfigSection[] connections = new ConfigSection[this.targets.size()];

            long idleTimout = this.idleTimeout > 0 ? this.idleTimeout : 60000;
            int maxConnections = this.maxConnections > 0 ? this.maxConnections : 10;
            int maxStreamsPerConnection = this.maxStreamsPerConnection > 0 ? this.maxStreamsPerConnection : 10;

            final HttpConnectionPoolFactory httpConnectionPoolFactory;
            final HttpMarshallerFactoryProvider httpMarshallerFactoryProvider;
            if (EENamespaceInteroperability.EE_NAMESPACE_INTEROPERABLE_MODE) {
                httpConnectionPoolFactory = EENamespaceInteroperability.getHttpConnectionPoolFactory();
                httpMarshallerFactoryProvider = EENamespaceInteroperability.getHttpMarshallerFactoryProvider();
            } else {
                httpConnectionPoolFactory = HttpConnectionPoolFactory.getDefault();
                httpMarshallerFactoryProvider = HttpMarshallerFactoryProvider.getDefaultHttpMarshallerFactoryProvider();
            }
            for (int i = 0; i < this.targets.size(); ++i) {
                HttpConfigBuilder sb = this.targets.get(i);
                HostPool hp = new HostPool(sb.getUri());
                boolean eager = this.eagerlyAcquireSession == null ? false : this.eagerlyAcquireSession;
                if (sb.getEagerlyAcquireSession() != null && sb.getEagerlyAcquireSession()) {
                    eager = true;
                }
                boolean http2 = this.enableHttp2 == null ? true : this.enableHttp2;
                if(sb.getEnableHttp2() != null) {
                    http2 = sb.getEnableHttp2();
                }
                ConfigSection connection = new ConfigSection(new HttpTargetContext(
                    httpConnectionPoolFactory.createHttpConnectionPool(sb.getMaxConnections() > 0 ? sb.getMaxConnections() : maxConnections, sb.getMaxStreamsPerConnection() > 0 ? sb.getMaxStreamsPerConnection() : maxStreamsPerConnection, worker, pool,
                        OptionMap.create(
                            UndertowOptions.ENABLE_HTTP2, http2,
                            Options.TCP_NODELAY, tcpNoDelay),
                        hp, sb.getIdleTimeout() > 0 ? sb.getIdleTimeout() : idleTimout), eager, sb.getUri(), httpMarshallerFactoryProvider),
                    sb.getUri());
                connections[i] = connection;
            }
            return new WildflyHttpContext(connections, maxConnections, maxStreamsPerConnection, idleTimeout,
                    eagerlyAcquireSession == null ? false : eagerlyAcquireSession, worker, pool,
                    enableHttp2 == null ? true : enableHttp2, tcpNoDelay,
                    httpConnectionPoolFactory, httpMarshallerFactoryProvider);
        }

        void setDefaultBindAddress(InetSocketAddress defaultBindAddress) {
            this.defaultBindAddress = defaultBindAddress;
        }

        InetSocketAddress getDefaultBindAddress() {
            return defaultBindAddress;
        }

        HttpConfigBuilder addConfig(URI uri) {
            HttpConfigBuilder builder = new HttpConfigBuilder(uri);
            targets.add(builder);
            return builder;
        }

        List<HttpConfigBuilder> getTargets() {
            return targets;
        }


        long getIdleTimeout() {
            return idleTimeout;
        }

        void setIdleTimeout(long idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        int getMaxConnections() {
            return maxConnections;
        }

        void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }

        int getMaxStreamsPerConnection() {
            return maxStreamsPerConnection;
        }

        void setMaxStreamsPerConnection(int maxStreamsPerConnection) {
            this.maxStreamsPerConnection = maxStreamsPerConnection;
        }

        Boolean getEagerlyAcquireSession() {
            return eagerlyAcquireSession;
        }

        void setEagerlyAcquireSession(Boolean eagerlyAcquireSession) {
            this.eagerlyAcquireSession = eagerlyAcquireSession;
        }

        public BufferBuilder getBufferConfig() {
            return bufferConfig;
        }

        public Builder setBufferConfig(BufferBuilder bufferConfig) {
            this.bufferConfig = bufferConfig;
            return this;
        }

        public void setEnableHttp2(Boolean enableHttp2) {
            this.enableHttp2 = enableHttp2;
        }

        public Boolean getEnableHttp2() {
            return enableHttp2;
        }

        public void setTcpNoDelay(Boolean tcpNoDelay) {
            this.tcpNoDelay = tcpNoDelay;
        }

        public Boolean getTcpNoDelay() {
            return tcpNoDelay;
        }

        class HttpConfigBuilder {
            final URI uri;
            private InetSocketAddress bindAddress;
            private long idleTimeout;
            private int maxConnections;
            private int maxStreamsPerConnection;
            private Boolean eagerlyAcquireSession;
            private Boolean enableHttp2;
            private Boolean tcpNoDelay = false;

            HttpConfigBuilder(URI uri) {
                this.uri = uri;
            }

            public URI getUri() {
                return uri;
            }

            void setBindAddress(InetSocketAddress bindAddress) {
                this.bindAddress = bindAddress;
            }

            InetSocketAddress getBindAddress() {
                return bindAddress;
            }

            long getIdleTimeout() {
                return idleTimeout;
            }

            void setIdleTimeout(long idleTimeout) {
                this.idleTimeout = idleTimeout;
            }

            int getMaxConnections() {
                return maxConnections;
            }

            void setMaxConnections(int maxConnections) {
                this.maxConnections = maxConnections;
            }

            int getMaxStreamsPerConnection() {
                return maxStreamsPerConnection;
            }

            void setMaxStreamsPerConnection(int maxStreamsPerConnection) {
                this.maxStreamsPerConnection = maxStreamsPerConnection;
            }

            Boolean getEagerlyAcquireSession() {
                return eagerlyAcquireSession;
            }

            void setEagerlyAcquireSession(Boolean eagerlyAcquireSession) {
                this.eagerlyAcquireSession = eagerlyAcquireSession;
            }

            public void setEnableHttp2(Boolean enableHttp2) {
                this.enableHttp2 = enableHttp2;
            }

            public Boolean getEnableHttp2() {
                return enableHttp2;
            }

            public void setTcpNoDelay(Boolean tcpNoDelay) {
                this.tcpNoDelay = tcpNoDelay;
            }

            public Boolean getTcpNoDelay() {
                return tcpNoDelay;
            }
        }
    }

    public static class BufferBuilder {
        private int bufferSize = 1024;
        private int maxSize = 100;
        private int threadLocalSize = 0;
        private boolean direct = true;

        public int getBufferSize() {
            return bufferSize;
        }

        public BufferBuilder setBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public BufferBuilder setMaxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public int getThreadLocalSize() {
            return threadLocalSize;
        }

        public BufferBuilder setThreadLocalSize(int threadLocalSize) {
            this.threadLocalSize = threadLocalSize;
            return this;
        }

        public boolean isDirect() {
            return direct;
        }

        public BufferBuilder setDirect(boolean direct) {
            this.direct = direct;
            return this;
        }
    }

}
