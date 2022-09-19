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
import io.undertow.util.AbstractAttachable;
import io.undertow.util.AttachmentKey;
import io.undertow.util.AttachmentList;
import io.undertow.util.HeaderValues;
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

/**
 * EE interoperability implementation for allowing Jakarta servers and clients communication with
 * Javax EE endpoints.
 *
 * EE interoperability must be enabled on all Jakarta servers and clients to make communication
 * among them possible.
 *
 * @author Flavia Rainone
 * @author Richard Opalka
 */
class EEInteroperability {
    // Batavia transformer sensible constant - it can start with either "javax." or "jakarta." if transformation was performed
    private static final String VARIABLE_CONSTANT = "javax.ejb.FAKE_STRING";
    private static final boolean JAKARTAEE_ENVIRONMENT = VARIABLE_CONSTANT.startsWith("jakarta");

    /**
     * Indicates if EE interoperable mode is enabled.
     */
    public static final boolean EE_INTEROPERABLE_MODE = Boolean.parseBoolean(
            WildFlySecurityManager.getPropertyPrivileged("org.wildfly.ee.interoperable", "false"))
            && JAKARTAEE_ENVIRONMENT;

    // header indicating the ee-api mode that is being used by the request/response sender
    private static final HttpString EE_API = new HttpString("x-wf-ee-api");
    // value for EE_API header: used only when both ends support Jakarta
    private static final String JAKARTA_EE = "jakarta";
    // value for EE_API header: used to indicate that client supports Jakarta and is on EE interoperability mode
    private static final String EE_INTEROP = "interop";
    // key used to attach http marshaller factory to a client request / server exchange
    private static final AttachmentKey<HttpMarshallerFactory> HTTP_MARSHALLER_FACTORY_KEY = AttachmentKey.create(HttpMarshallerFactory.class);
    // key used to attach an http unmarshaller factory to a server exchange
    private static final AttachmentKey<HttpMarshallerFactory> HTTP_UNMARSHALLER_FACTORY_KEY = AttachmentKey.create(HttpMarshallerFactory.class);
    // marshaller factory to be used when Javax<->Jakarta transformation is needed
    private static final HttpMarshallerFactory INTEROPERABLE_MARSHALLER_FACTORY = new HttpMarshallerFactory(JAVAEE_TO_JAKARTAEE);

    static {
        if (EE_INTEROPERABLE_MODE) {
            HttpClientMessages.MESSAGES.javaeeToJakartaeeBackwardCompatibilityLayerInstalled();
        }
    }

    /**
     * Wraps the HTTP server handler into an EE interoperable handler. Such handler implements the
     * EE interoperability at the server side before delegating to the wrapped {@code httpHandler}
     *
     * @param httpHandler the handler to be wrapped
     * @return handler the ee interoperability handler
     */
    public static HttpHandler wrap(HttpHandler httpHandler) {
        return new EEInteroperabilityHandler(httpHandler);
    }

    /**
     * Returns the HTTPMarshallerFactoryProvider instance responsible for taking care of marshalling
     * and unmarshalling according to the values negotiated by the ee interoperability headers.
     *
     * @return the HTTPMarshallerFactoryProvider. All marshalling and unmarshalling done at both server
     * and client side have to be done through a factory provided by this object.
     */
    public static HttpMarshallerFactoryProvider getHttpMarshallerFactoryProvider() {
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
     * Returns the HTTP connection pool factory when EE interoperability mode is on. This factory
     * creates EE interoperable connections to the server.
     *
     * @return the {@link HttpConnectionPoolFactory}.
     */
    public static HttpConnectionPoolFactory getHttpConnectionPoolFactory() {
        return ((maxConnections, maxStreamsPerConnection, worker, byteBufferPool, options, hostPool, connectionIdleTimeout) -> {
            return new HttpConnectionPool(maxConnections, maxStreamsPerConnection, worker, byteBufferPool, options, hostPool, connectionIdleTimeout);
        });
    }

    /*
    Client side EE interoperability
     */

    private static class HttpConnectionPool extends org.wildfly.httpclient.common.HttpConnectionPool {

        protected HttpConnectionPool(int maxConnections, int maxStreamsPerConnection, XnioWorker worker, ByteBufferPool byteBufferPool, OptionMap options, HostPool hostPool, long connectionIdleTimeout) {
            super(maxConnections, maxStreamsPerConnection, worker, byteBufferPool, options, hostPool, connectionIdleTimeout);
        }

        @Override
        protected org.wildfly.httpclient.common.HttpConnectionPool.ClientConnectionHolder createClientConnectionHolder(ClientConnection connection, URI uri, SSLContext sslContext) {
            return new ClientConnectionHolder(connection, uri, sslContext);
        }

        protected class ClientConnectionHolder extends org.wildfly.httpclient.common.HttpConnectionPool.ClientConnectionHolder {
            // keep track if the connection is new
            private boolean newConnection = true;
            // indicates this connection is Jakarta EE and, hence, no class name transformation is needed
            private boolean jakartaEE;

            private ClientConnectionHolder(ClientConnection connection, URI uri, SSLContext sslContext) {
                super (connection, uri, sslContext);
            }

            @Override
            public void sendRequest(ClientRequest request, ClientCallback<ClientExchange> callback) {
                if (newConnection) {
                    // new connection: send the ee-api header once with interop value to see what will be the response
                    request.getRequestHeaders().put(EE_API, EE_INTEROP);
                    request.putAttachment(HTTP_MARSHALLER_FACTORY_KEY, INTEROPERABLE_MARSHALLER_FACTORY);
                    newConnection = false;
                } else if (jakartaEE) {
                    // connection already set as Jakarta, default factory can be used for marshalling
                    // (no transformation needed)
                    request.getRequestHeaders().put(EE_API, JAKARTA_EE);
                    request.putAttachment(HTTP_MARSHALLER_FACTORY_KEY, DEFAULT_FACTORY);
                } else {
                    // connection is Javax EE, the only remaining possibility here
                    // we need to transform class names Javax<->Jakarta
                    request.putAttachment(HTTP_MARSHALLER_FACTORY_KEY, INTEROPERABLE_MARSHALLER_FACTORY);
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
                            if (jakartaEE) {
                                // do nothing, request already contains the default marshalling factory
                            } else {
                                // we need to check for ee-api header for each non-Jakarta response
                                final HeaderValues serverEEApi = response.getResponseHeaders().get(EE_API);
                                if (serverEEApi != null && serverEEApi.contains(JAKARTA_EE)) {
                                    // this indicates this is the first response server sends, mark the connection
                                    // as jakarta and be done with it
                                    jakartaEE = true;
                                    // overwrite previous attachment, no transformation is needed for this connection any more
                                    result.getRequest().putAttachment(HTTP_MARSHALLER_FACTORY_KEY, DEFAULT_FACTORY);
                                } else {
                                    // do nothing, the connection is not Jakarta and the
                                    // marshalling factory provider is already interoperable
                                }
                            }
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
    Server side EE interoperability
     */

    private static class EEInteroperabilityHandler implements HttpHandler {

        private final HttpHandler next;

        EEInteroperabilityHandler(HttpHandler next) {
            this.next = next;
        }

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            if (exchange.getRequestHeaders().contains(EE_API)) {
                switch (exchange.getRequestHeaders().getFirst(EE_API)) {
                    case EE_INTEROP:
                        exchange.getResponseHeaders().add(EE_API, JAKARTA_EE);
                        // transformation is required for unmarshalling because client is on EE interoperable mode
                        exchange.putAttachment(EEInteroperability.HTTP_UNMARSHALLER_FACTORY_KEY, EEInteroperability.INTEROPERABLE_MARSHALLER_FACTORY);
                        // no transformation required for marshalling, server is sending response in Jakarta
                        exchange.putAttachment(EEInteroperability.HTTP_MARSHALLER_FACTORY_KEY, HttpMarshallerFactory.DEFAULT_FACTORY);
                        break;
                    case JAKARTA_EE:
                        // no transformation is needed, this is a Jakarta connection at both ends
                        exchange.putAttachment(EEInteroperability.HTTP_MARSHALLER_FACTORY_KEY, HttpMarshallerFactory.DEFAULT_FACTORY);
                        exchange.putAttachment(EEInteroperability.HTTP_UNMARSHALLER_FACTORY_KEY, HttpMarshallerFactory.DEFAULT_FACTORY);
                        break;
                }
            } else {
                // transformation is required for unmarshalling request and marshalling response,
                // because server is interoperable mode and the lack of a header indicates this is
                // either a Javax EE client or a Jakarta EE client that is not interoperable
                // the latter case will lead to an error whhen unmarshalling at client side)
                exchange.putAttachment(EEInteroperability.HTTP_MARSHALLER_FACTORY_KEY, EEInteroperability.INTEROPERABLE_MARSHALLER_FACTORY);
                exchange.putAttachment(EEInteroperability.HTTP_UNMARSHALLER_FACTORY_KEY, EEInteroperability.INTEROPERABLE_MARSHALLER_FACTORY);
            }
            next.handleRequest(exchange);
        }
    }
}