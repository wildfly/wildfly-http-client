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

import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.client.ContinueNotification;
import io.undertow.client.PushCallback;
import io.undertow.connector.ByteBufferPool;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.AbstractAttachable;
import io.undertow.util.AttachmentKey;
import io.undertow.util.AttachmentList;
import io.undertow.util.HttpString;
import org.wildfly.security.manager.WildFlySecurityManager;
import org.xnio.OptionMap;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.jboss.marshalling.ClassNameTransformer.JAVAEE_TO_JAKARTAEE;
import static org.wildfly.httpclient.common.HttpMarshallerFactory.DEFAULT_FACTORY;
import static org.wildfly.httpclient.common.Protocol.VERSION_ONE_PATH;
import static org.wildfly.httpclient.common.Protocol.VERSION_TWO_PATH;

/**
 * EE namespace interoperability implementation for allowing Jakarta EE namespace servers and clients communication with
 * Javax EE namespace endpoints.
 *
 * EE namespace interoperability must be enabled on all Jakarta servers and clients to make communication
 * among them possible.
 *
 * @author Flavia Rainone
 * @author Richard Opalka
 */
final class EENamespaceInteroperability {
    /**
     * Indicates if EE namespace interoperable mode is enabled.
     */
    static final boolean EE_NAMESPACE_INTEROPERABLE_MODE = Boolean.parseBoolean(
            WildFlySecurityManager.getPropertyPrivileged("org.wildfly.ee.namespace.interop", "false"));

    // header indicating the protocol version mode that is being used by the request/response sender
    private static final HttpString PROTOCOL_VERSION = new HttpString("x-wf-version");
    // value for PROTOCOL_VERSION header: used to handshake a higher version, only when both ends use EE jakarta namespace
    private static final String LATEST_VERSION = String.valueOf(Protocol.LATEST);
    // key used to attach http marshaller factory to a client request / server exchange
    private static final AttachmentKey<HttpMarshallerFactory> HTTP_MARSHALLER_FACTORY_KEY = AttachmentKey.create(HttpMarshallerFactory.class);
    // key used to attach an http unmarshaller factory to a server exchange
    private static final AttachmentKey<HttpMarshallerFactory> HTTP_UNMARSHALLER_FACTORY_KEY = AttachmentKey.create(HttpMarshallerFactory.class);
    // marshaller factory to be used when Javax<->Jakarta transformation is needed
    private static final HttpMarshallerFactory INTEROPERABLE_MARSHALLER_FACTORY = new HttpMarshallerFactory(JAVAEE_TO_JAKARTAEE);

    static {
        if (EE_NAMESPACE_INTEROPERABLE_MODE) {
            HttpClientMessages.MESSAGES.javaeeToJakartaeeBackwardCompatibilityLayerInstalled();
        }
    }

    private EENamespaceInteroperability() {}

    /**
     * Wraps the HTTP server handler into an EE namespace interoperable handler. Such handler implements the
     * EE namespace interoperability at the server side before delegating to the wrapped {@code httpHandler}
     *
     * @param httpHandler the handler to be wrapped
     * @return handler the ee namespace interoperability handler
     */
    static HttpHandler createInteroperabilityHandler(HttpHandler httpHandler) {
        return createProtocolVersionHttpHandler(new EENamespaceInteroperabilityHandler(httpHandler), new JakartaNamespaceHandler(httpHandler));
    }

    static HttpHandler createProtocolVersionHttpHandler(HttpHandler interoperabilityHandler, HttpHandler latestProtocolHandler) {
        final PathHandler versionPathHandler = new PathHandler();
        versionPathHandler.addPrefixPath(VERSION_ONE_PATH, interoperabilityHandler);
        versionPathHandler.addPrefixPath(VERSION_TWO_PATH, latestProtocolHandler);
        return versionPathHandler;
    }

    /**
     * Returns the HTTPMarshallerFactoryProvider instance responsible for taking care of marshalling
     * and unmarshalling according to the values negotiated by the ee namespace interoperability headers.
     *
     * @return the HTTPMarshallerFactoryProvider. All marshalling and unmarshalling done at both server
     * and client side have to be done through a factory provided by this object.
     */
    static HttpMarshallerFactoryProvider getHttpMarshallerFactoryProvider() {
        return new HttpMarshallerFactoryProvider() {
            @Override
            public HttpMarshallerFactory getMarshallerFactory(AbstractAttachable attachable) {
                return attachable.getAttachment(HTTP_MARSHALLER_FACTORY_KEY);
            }

            @Override
            public HttpMarshallerFactory getUnmarshallerFactory(AbstractAttachable attachable) {
                return attachable.getAttachment(HTTP_UNMARSHALLER_FACTORY_KEY);
            }
        };
    }

    /**
     * Returns the HTTP connection pool factory when EE namespace interoperability mode is on. This factory
     * creates EE namespace interoperable connections to the server.
     *
     * @return the {@link HttpConnectionPoolFactory}.
     */
    static HttpConnectionPoolFactory getHttpConnectionPoolFactory() {
        return (HttpConnectionPool::new);
    }

    /*
    Client side EE namespace interoperability
     */

    private static class HttpConnectionPool extends org.wildfly.httpclient.common.HttpConnectionPool {
        private volatile int protocolVersion = -1;

        protected HttpConnectionPool(int maxConnections, int maxStreamsPerConnection, XnioWorker worker, ByteBufferPool byteBufferPool, OptionMap options, HostPool hostPool, long connectionIdleTimeout) {
            super(maxConnections, maxStreamsPerConnection, worker, byteBufferPool, options, hostPool, connectionIdleTimeout);
        }

        @Override
        int getProtocolVersion() {
            return protocolVersion == -1? 1 : protocolVersion;
        }

        @Override
        protected org.wildfly.httpclient.common.HttpConnectionPool.ClientConnectionHolder createClientConnectionHolder(ClientConnection connection, URI uri, SSLContext sslContext) {
            return new ClientConnectionHolder(connection, uri, sslContext);
        }

        protected class ClientConnectionHolder extends org.wildfly.httpclient.common.HttpConnectionPool.ClientConnectionHolder {

            private ClientConnectionHolder(ClientConnection connection, URI uri, SSLContext sslContext) {
                super (connection, uri, sslContext);
            }

            @Override
            public void sendRequest(ClientRequest request, ClientCallback<ClientExchange> callback) {
                switch (protocolVersion) {
                    case -1:
                        // new connection pool: send the protocol version header once with LATEST_VERSION value to see what will be the response
                        request.getRequestHeaders().put(PROTOCOL_VERSION, LATEST_VERSION);
                        request.putAttachment(HTTP_MARSHALLER_FACTORY_KEY, INTEROPERABLE_MARSHALLER_FACTORY);
                        break;
                    case Protocol.JAVAEE_PROTOCOL_VERSION:
                        // connection is Javax EE, so we need to transform class names Javax<->Jakarta
                        request.putAttachment(HTTP_MARSHALLER_FACTORY_KEY, INTEROPERABLE_MARSHALLER_FACTORY);
                        break;
                    case org.wildfly.httpclient.common.Protocol.JAKARTAEE_PROTOCOL_VERSION:
                    default:
                        // connection already set as Jakarta namespace, default factory can be used for marshalling
                        // (no transformation needed)
                        request.getRequestHeaders().put(PROTOCOL_VERSION, LATEST_VERSION);
                        request.putAttachment(HTTP_MARSHALLER_FACTORY_KEY, DEFAULT_FACTORY);
                }
                super.sendRequest(request, new ClientCallback<ClientExchange>() {
                    @Override
                    public void completed(ClientExchange result) {
                        // wrap the exchange, to handle interoperability at the result (see below)
                        callback.completed(new EEInteroperableClientExchange(result));
                    }

                    @Override
                    public void failed(IOException e) {
                        callback.failed(e);
                    }
                });
            }

            private final class EEInteroperableClientExchange implements ClientExchange {

                private final ClientExchange wrappedExchange;

                public EEInteroperableClientExchange(ClientExchange clientExchange) {
                    this.wrappedExchange = clientExchange;
                }

                @Override
                public void setResponseListener(final ClientCallback<ClientExchange> responseListener) {
                    wrappedExchange.setResponseListener(new ClientCallback<ClientExchange>() {
                        @Override
                        public void completed(ClientExchange result) {
                            // this method adds the factory to the request instead of response, this is more efficient
                            // we prevent adding when jakartaEE is already true and creating a new entry in the response attachment map
                            final ClientResponse response = result.getResponse();
                            // if the response represents an UNAUTHORIZED (401) HTTP response, do not update the protocol version (WEJBHTTP-110)
                            if (protocolVersion == -1 && response.getResponseCode() != 401) {
                                // we need to check for protocol version header to define the protocol version of the pool
                                if (LATEST_VERSION.equals(response.getResponseHeaders().getFirst(PROTOCOL_VERSION))) {
                                    // this indicates this is the first response server sends, set the protocol to 2
                                    protocolVersion = Protocol.LATEST;
                                    // overwrite previous attachment, no transformation is needed for this connection any more
                                    result.getRequest().putAttachment(HTTP_MARSHALLER_FACTORY_KEY, DEFAULT_FACTORY);
                                } else {
                                    protocolVersion = Protocol.JAVAEE_PROTOCOL_VERSION;
                                    //regarding marsh. factory key, do nothing, the connection is not Jakarta and the marshalling factory provider is already interoperable
                                }

                            } // else: do nothing, request already contains the default marshalling factory
                            responseListener.completed(result);
                        }

                        @Override
                        public void failed(IOException e) {
                            responseListener.failed(e);
                        }
                    });
                }

                @Override
                public void setContinueHandler(ContinueNotification continueHandler) {
                    wrappedExchange.setContinueHandler(continueHandler);
                }

                @Override
                public void setPushHandler(PushCallback pushCallback) {
                    wrappedExchange.setPushHandler(pushCallback);
                }

                @Override
                public StreamSinkChannel getRequestChannel() {
                    return wrappedExchange.getRequestChannel();
                }

                @Override
                public StreamSourceChannel getResponseChannel() {
                    return wrappedExchange.getResponseChannel();
                }

                @Override
                public ClientRequest getRequest() {
                    return wrappedExchange.getRequest();
                }

                @Override
                public ClientResponse getResponse() {
                    return wrappedExchange.getResponse();
                }

                @Override
                public ClientResponse getContinueResponse() {
                    return wrappedExchange.getContinueResponse();
                }

                @Override
                public ClientConnection getConnection() {
                    return wrappedExchange.getConnection();
                }

                @Override
                public <T> T getAttachment(AttachmentKey<T> key) {
                    return wrappedExchange.getAttachment(key);
                }

                @Override
                public <T> List<T> getAttachmentList(AttachmentKey<? extends List<T>> key) {
                    return wrappedExchange.getAttachmentList(key);
                }

                @Override
                public <T> T putAttachment(AttachmentKey<T> key, T value) {
                    return wrappedExchange.putAttachment(key, value);
                }

                @Override
                public <T> T removeAttachment(AttachmentKey<T> key) {
                    return wrappedExchange.removeAttachment(key);
                }

                @Override
                public <T> void addToAttachmentList(AttachmentKey<AttachmentList<T>> key, T value) {
                    wrappedExchange.addToAttachmentList(key, value);
                }
            }
        }
    }

    /*
    Server side EE namespace interoperability
     */

    private static class EENamespaceInteroperabilityHandler implements HttpHandler {

        private final HttpHandler next;

        EENamespaceInteroperabilityHandler(HttpHandler next) {
            this.next = next;
        }

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            if (LATEST_VERSION.equals(exchange.getRequestHeaders().getFirst(PROTOCOL_VERSION))) {
                // respond that this end also supports version two
                exchange.getResponseHeaders().add(PROTOCOL_VERSION, LATEST_VERSION);
                // transformation is required for unmarshalling because client is on EE namespace interoperable mode
                exchange.putAttachment(HTTP_UNMARSHALLER_FACTORY_KEY, INTEROPERABLE_MARSHALLER_FACTORY);
                // no transformation required for marshalling, server is sending response in Jakarta
                exchange.putAttachment(HTTP_MARSHALLER_FACTORY_KEY, DEFAULT_FACTORY);
            } else {
                // transformation is required for unmarshalling request and marshalling response,
                // because server is interoperable mode and the lack of a header indicates this is
                // either a Javax EE client or a Jakarta EE client that is not interoperable
                // the latter case will lead to an error when unmarshalling at client side)
                exchange.putAttachment(HTTP_MARSHALLER_FACTORY_KEY, INTEROPERABLE_MARSHALLER_FACTORY);
                exchange.putAttachment(HTTP_UNMARSHALLER_FACTORY_KEY, INTEROPERABLE_MARSHALLER_FACTORY);
            }
            next.handleRequest(exchange);
        }
    }

    private static class JakartaNamespaceHandler implements HttpHandler {

        private final HttpHandler next;

        JakartaNamespaceHandler(HttpHandler next) {
            this.next = next;
        }

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            // no transformation required whatsoever, just make sure we have a factory set
            // or else we will see a NPE when trying to use those attachments
            exchange.putAttachment(HTTP_UNMARSHALLER_FACTORY_KEY, DEFAULT_FACTORY);
            exchange.putAttachment(HTTP_MARSHALLER_FACTORY_KEY, DEFAULT_FACTORY);
            next.handleRequest(exchange);
        }
    }
}
