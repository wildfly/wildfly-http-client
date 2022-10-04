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

import io.undertow.client.ClientCallback;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.util.AbstractAttachable;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.channels.StreamSourceChannel;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.AccessController;
import java.security.GeneralSecurityException;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * Http target context used by client side.
 *
 * @author Stuart Douglas
 * @author <a href="mailto:rachmato@redhat.com">Richard Achmatowicz</a>
 */
public class HttpTargetContext extends AbstractAttachable {

    private static final AuthenticationContextConfigurationClient AUTH_CONTEXT_CLIENT;
    private static final String GENERAL_EXCEPTION_ON_FAILED_AUTH_PROPERTY = "org.wildfly.httpclient.io-exception-on-failed-auth";

    static {
        AUTH_CONTEXT_CLIENT = AccessController.doPrivileged((PrivilegedAction<AuthenticationContextConfigurationClient>) () -> new AuthenticationContextConfigurationClient());
    }

    private static final String EXCEPTION_TYPE = "application/x-wf-jbmar-exception";
    private static final HttpString BACKEND_HEADER = new HttpString("Backend");
    private static final String JSESSIONID = "JSESSIONID";

    private final HttpConnectionPool connectionPool;
    private final boolean eagerlyAcquireAffinity;
    private final URI uri;
    private final AuthenticationContext initAuthenticationContext;

    private final HttpMarshallerFactoryProvider httpMarshallerFactoryProvider;

    private static ClassLoader getContextClassLoader() {
        if(System.getSecurityManager() == null) {
            return Thread.currentThread().getContextClassLoader();
        } else {
            return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                @Override
                public ClassLoader run() {
                    return Thread.currentThread().getContextClassLoader();
                }
            });
        }
    }

    HttpTargetContext(HttpConnectionPool connectionPool, boolean eagerlyAcquireAffinity, URI uri, HttpMarshallerFactoryProvider provider) {
        this.connectionPool = connectionPool;
        this.eagerlyAcquireAffinity = eagerlyAcquireAffinity;
        this.uri = uri;
        this.initAuthenticationContext = AuthenticationContext.captureCurrent();
        this.httpMarshallerFactoryProvider = provider;
    }

    void init() {
        if (eagerlyAcquireAffinity) {
            // this is now a noop as we can't associate affinity to a single backend server with the target context
        }
    }

    public URI acquireBackendServer() throws Exception {
        // HttpClientMessages.MESSAGES.info("HtpTargetContext: acquireBackendServer()");
        return acquireBackendServer(AUTH_CONTEXT_CLIENT.getAuthenticationConfiguration(uri, AuthenticationContext.captureCurrent()));
    }

    private URI acquireBackendServer(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        ClientRequest clientRequest = new ClientRequest();
        clientRequest.setMethod(Methods.GET);
        clientRequest.setPath(uri.getPath() + "/common/v1/backend");
        AuthenticationContext context = AuthenticationContext.captureCurrent();
        SSLContext sslContext;
        try {
            sslContext = AUTH_CONTEXT_CLIENT.getSSLContext(uri, context);
        } catch(GeneralSecurityException e) {
            HttpClientMessages.MESSAGES.failedToAcquireBackendServer(e);
            return null;
        }

        // returns a URI of the form <scheme>://<host>:<port>?name=<jboss.node.name>
        // this permits having access to *both* the IP:port and the hostname identifiers for the server
        CompletableFuture<URI> result = new CompletableFuture<>();
        sendRequest(clientRequest, sslContext, authenticationConfiguration,
                null,
                null,
                ((resultStream, response, closeable) -> {
                    HeaderValues backends = response.getResponseHeaders().get(BACKEND_HEADER);
                    if (backends == null) {
                        result.completeExceptionally(HttpClientMessages.MESSAGES.failedToAcquireBackendServer(new Exception("Missing backend header on response")));
                    }
                    try {
                        String backendString = backends.getFirst();
                        URI backendURI = new URI(backendString);
                        result.complete(backendURI);
                    } catch(URISyntaxException use) {
                        result.completeExceptionally(HttpClientMessages.MESSAGES.failedToAcquireBackendServer(use));
                    } finally {
                        IoUtils.safeClose(closeable);
                    }
                }),
                result::completeExceptionally, null, null);
        return result.get();
    }

    public void sendRequest(ClientRequest request, SSLContext sslContext, AuthenticationConfiguration authenticationConfiguration, HttpMarshaller httpMarshaller, HttpStickinessHandler httpStickinessHandler, HttpResultHandler httpResultHandler, HttpFailureHandler failureHandler, ContentType expectedResponse, Runnable completedTask) {
        sendRequest(request, sslContext, authenticationConfiguration, httpMarshaller, httpStickinessHandler, httpResultHandler, failureHandler, expectedResponse, completedTask, false);
    }

    public void sendRequest(ClientRequest request, SSLContext sslContext, AuthenticationConfiguration authenticationConfiguration, HttpMarshaller httpMarshaller, HttpStickinessHandler httpStickinessHandler, HttpResultHandler httpResultHandler, HttpFailureHandler failureHandler, ContentType expectedResponse, Runnable completedTask, boolean allowNoContent) {
        final ClassLoader tccl = getContextClassLoader();
        connectionPool.getConnection(connection -> sendRequestInternal(connection, request, authenticationConfiguration, httpMarshaller, httpStickinessHandler, httpResultHandler, failureHandler, expectedResponse, completedTask, allowNoContent, false, sslContext, tccl), failureHandler::handleFailure, false, sslContext);
    }

    public void sendRequestInternal(final HttpConnectionPool.ConnectionHandle connection, ClientRequest request, AuthenticationConfiguration authenticationConfiguration, HttpMarshaller httpMarshaller, HttpStickinessHandler httpStickinessHandler, HttpResultHandler httpResultHandler, HttpFailureHandler failureHandler, ContentType expectedResponse, Runnable completedTask, boolean allowNoContent, boolean retry, SSLContext sslContext, ClassLoader classLoader) {
        try {
            final boolean authAdded = retry || connection.getAuthenticationContext().prepareRequest(connection.getUri(), request, authenticationConfiguration);

            if (!request.getRequestHeaders().contains(Headers.HOST)) {
                String host;
                int port = connection.getUri().getPort();
                if (port == -1) {
                    host = connection.getUri().getHost();
                } else {
                    host = connection.getUri().getHost() + ":" + port;
                }
                request.getRequestHeaders().put(Headers.HOST, host);
            }

            final SSLContext finalSslContext = (sslContext == null) ?
                AUTH_CONTEXT_CLIENT.getSSLContext(uri, initAuthenticationContext)
                : sslContext;
            final AuthenticationConfiguration finalAuthenticationConfiguration = (authenticationConfiguration == null) ?
                AUTH_CONTEXT_CLIENT.getAuthenticationConfiguration(uri, initAuthenticationContext)
                : authenticationConfiguration;

            if (request.getRequestHeaders().contains(Headers.CONTENT_TYPE)) {
                request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, Headers.CHUNKED.toString());
            }

            ClientSendCallback clientSendCallback = new ClientSendCallback(connection, request, httpMarshaller, httpStickinessHandler, httpResultHandler, failureHandler,
                    expectedResponse, completedTask, allowNoContent, authAdded, finalAuthenticationConfiguration, finalSslContext, classLoader);

            connection.sendRequest(request, clientSendCallback) ;
        } catch (Throwable e) {
            try {
                failureHandler.handleFailure(e);
            } finally {
                connection.done(true);
            }
        }
    }

    private static Map<String, Object> readAttachments(final ObjectInput input) throws IOException, ClassNotFoundException {
        final int numAttachments = input.readByte();
        if (numAttachments == 0) {
            return null;
        }
        final Map<String, Object> attachments = new HashMap<>(numAttachments);
        for (int i = 0; i < numAttachments; i++) {
            // read the key
            final String key = (String) input.readObject();
            // read the attachment value
            final Object val = input.readObject();
            attachments.put(key, val);
        }
        return attachments;
    }

    public HttpMarshallerFactory getHttpMarshallerFactory(ClientRequest clientRequest) {
        return this.httpMarshallerFactoryProvider.getMarshallerFactory(clientRequest);
    }

    public HttpConnectionPool getConnectionPool() {
        return connectionPool;
    }

    public URI getUri() {
        return uri;
    }

    private boolean isLegacyAuthenticationFailedException() {
        return AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
            @Override
            public Boolean run() {
                return Boolean.valueOf(System.getProperty(GENERAL_EXCEPTION_ON_FAILED_AUTH_PROPERTY, "false"));
            }
        });
    }

    public interface HttpMarshaller {
        void marshall(OutputStream output) throws Exception;
    }

    public interface HttpResultHandler {
        void handleResult(InputStream result, ClientResponse response, Closeable doneCallback);
    }

    public interface HttpFailureHandler {
        void handleFailure(Throwable throwable);
    }

    public interface HttpStickinessHandler {
        void prepareRequest(ClientRequest request);
        void processResponse(ClientExchange result);
    }

    /*
     * Callback used by ConnectionPool.sendRequest to handle either a successful or failed request send operation.
     */
    private class ClientSendCallback implements ClientCallback<ClientExchange> {
        private HttpConnectionPool.ConnectionHandle connection;
        private ClientRequest request;
        private HttpMarshaller httpMarshaller;
        private HttpStickinessHandler httpStickinessHandler;
        private HttpResultHandler httpResultHandler;
        private HttpFailureHandler failureHandler;
        private ContentType expectedResponse;
        private Runnable completedTask;
        private boolean allowNoContent;

        private boolean authAdded;
        private AuthenticationConfiguration finalAuthenticationConfiguration;
        private SSLContext finalSslContext;
        private ClassLoader classLoader;

        public ClientSendCallback(HttpConnectionPool.ConnectionHandle connection, ClientRequest request, HttpMarshaller httpMarshaller, HttpStickinessHandler httpStickinessHandler, HttpResultHandler httpResultHandler, HttpFailureHandler failureHandler, ContentType expectedResponse, Runnable completedTask, boolean allowNoContent, boolean authAdded, AuthenticationConfiguration finalAuthenticationConfiguration, SSLContext finalSslContext, ClassLoader classLoader) {
            this.connection = connection;
            this.request = request;
            this.httpMarshaller = httpMarshaller;
            this.httpStickinessHandler = httpStickinessHandler;
            this.httpResultHandler = httpResultHandler;
            this.failureHandler = failureHandler;
            this.expectedResponse = expectedResponse;
            this.completedTask = completedTask;
            this.allowNoContent = allowNoContent;
            this.authAdded = authAdded;
            this.finalAuthenticationConfiguration = finalAuthenticationConfiguration;
            this.finalSslContext = finalSslContext;
            this.classLoader = classLoader;
        }

        /**
         * Called upon successful send of an HTTP request.
         * @param result the resulting ClientExchange instance
         */
        @Override
        public void completed(ClientExchange result) {
            // set up the callback to process HTTP responses
            result.setResponseListener(new ClientReceiveCallback(connection, request, httpMarshaller, httpStickinessHandler, httpResultHandler, failureHandler,
                    expectedResponse, completedTask, allowNoContent, authAdded, finalAuthenticationConfiguration, finalSslContext, classLoader));

            // set up stickiness metadata for this request
            if (httpStickinessHandler != null) {
                httpStickinessHandler.prepareRequest(request);
            }

            if (httpMarshaller != null) {
                //marshalling is blocking, we need to delegate, otherwise we may need to buffer arbitrarily large requests
                connection.getConnection().getWorker().execute(() -> {
                    try (OutputStream outputStream = new WildflyClientOutputStream(result.getRequestChannel(), result.getConnection().getBufferPool())) {

                        // marshall the locator and method params
                         httpMarshaller.marshall(outputStream);

                    } catch (Exception e) {
                        try {
                            failureHandler.handleFailure(e);
                        } finally {
                            connection.done(true);
                        }
                    }
                });
            }
        }

        /**
         * Called upon failed send of an HTTP request.
         * @param e the IOException which caused the failure
         */
        @Override
        public void failed(IOException e) {
            try {
                failureHandler.handleFailure(e);
            } finally {
                connection.done(true);
            }
        }
    }

    /*
     * Callback used by ConnectionPool.sendRequest to handle either a successful or failed response receive operation.
     */
    private class ClientReceiveCallback implements ClientCallback<ClientExchange> {
        private HttpConnectionPool.ConnectionHandle connection;
        private ClientRequest request;
        private HttpMarshaller httpMarshaller;
        private HttpStickinessHandler httpStickinessHandler;
        private HttpResultHandler httpResultHandler;
        private HttpFailureHandler failureHandler;
        private ContentType expectedResponse;
        private Runnable completedTask;
        private boolean allowNoContent;

        private boolean authAdded;
        private AuthenticationConfiguration finalAuthenticationConfiguration;
        private SSLContext finalSslContext;
        private ClassLoader classLoader;

        public ClientReceiveCallback(HttpConnectionPool.ConnectionHandle connection, ClientRequest request,
                                     HttpMarshaller httpMarshaller, HttpStickinessHandler httpStickinessHandler, HttpResultHandler httpResultHandler, HttpFailureHandler failureHandler,
                                     ContentType expectedResponse, Runnable completedTask, boolean allowNoContent,
                                     boolean authAdded, AuthenticationConfiguration finalAuthenticationConfiguration, SSLContext finalSslContext, ClassLoader classLoader) {
            this.connection = connection;
            this.request = request;
            this.httpMarshaller = httpMarshaller;
            this.httpStickinessHandler = httpStickinessHandler;
            this.httpResultHandler = httpResultHandler;
            this.failureHandler = failureHandler;
            this.expectedResponse = expectedResponse;
            this.completedTask = completedTask;
            this.allowNoContent = allowNoContent;
            this.authAdded = authAdded;
            this.finalAuthenticationConfiguration = finalAuthenticationConfiguration;
            this.finalSslContext = finalSslContext;
            this.classLoader = classLoader;
        }

        /**
         * Called upon successful receipt of an HTTP rewponse.
         * @param result the resulting ClientExchange instance
         */
        @Override
        public void completed(ClientExchange result) {
            connection.getConnection().getWorker().execute(() -> {
                ClientResponse response = result.getResponse();
                if (!authAdded || connection.getAuthenticationContext().isStale(result)) {
                    if (connection.getAuthenticationContext().handleResponse(response)) {
                        URI uri = connection.getUri();
                        connection.done(false);
                        final AtomicBoolean done = new AtomicBoolean();
                        ChannelListener<StreamSourceChannel> listener = ChannelListeners.drainListener(Long.MAX_VALUE, channel -> {
                            done.set(true);
                            connectionPool.getConnection((connection) -> {
                                if (connection.getAuthenticationContext().prepareRequest(uri, request, finalAuthenticationConfiguration)) {
                                    //retry the invocation
                                    sendRequestInternal(connection, request, finalAuthenticationConfiguration, httpMarshaller, httpStickinessHandler, httpResultHandler, failureHandler, expectedResponse, completedTask, allowNoContent, true, finalSslContext, classLoader);
                                } else {
                                    failureHandler.handleFailure(HttpClientMessages.MESSAGES.authenticationFailed());
                                    connection.done(true);
                                }
                            }, failureHandler::handleFailure, false, finalSslContext);

                        }, (channel, exception) -> failureHandler.handleFailure(exception));
                        listener.handleEvent(result.getResponseChannel());
                        if(!done.get()) {
                            result.getResponseChannel().getReadSetter().set(listener);
                            result.getResponseChannel().resumeReads();
                        }
                        return;
                    }
                }

                ContentType type = ContentType.parse(response.getResponseHeaders().getFirst(Headers.CONTENT_TYPE));
                final boolean ok;
                final boolean isException;
                if (type == null) {
                    ok = expectedResponse == null || (allowNoContent && response.getResponseCode() == StatusCodes.NO_CONTENT);
                    isException = false;
                } else {
                    if (type.getType().equals(EXCEPTION_TYPE)) {
                        ok = true;
                        isException = true;
                    } else if (expectedResponse == null) {
                        ok = false;
                        isException = false;
                    } else {
                        ok = expectedResponse.getType().equals(type.getType()) && expectedResponse.getVersion() >= type.getVersion();
                        isException = false;
                    }
                }

                if (!ok) {
                    if (response.getResponseCode() == 401 && !isLegacyAuthenticationFailedException()) {
                        failureHandler.handleFailure(HttpClientMessages.MESSAGES.authenticationFailed(response));
                    } else if (response.getResponseCode() >= 400) {
                        failureHandler.handleFailure(HttpClientMessages.MESSAGES.invalidResponseCode(response.getResponseCode(), response));
                    } else {
                        failureHandler.handleFailure(HttpClientMessages.MESSAGES.invalidResponseType(type));
                    }
                    //close the connection to be safe
                    connection.done(true);
                    return;
                }
                try {
                    if (isException) {
                        final Unmarshaller unmarshaller = getHttpMarshallerFactory(request).createUnmarshaller(classLoader);
                        try (WildflyClientInputStream inputStream = new WildflyClientInputStream(result.getConnection().getBufferPool(), result.getResponseChannel())) {
                            InputStream in = inputStream;
                            String encoding = response.getResponseHeaders().getFirst(Headers.CONTENT_ENCODING);
                            if (encoding != null) {
                                String lowerEncoding = encoding.toLowerCase(Locale.ENGLISH);
                                if (Headers.GZIP.toString().equals(lowerEncoding)) {
                                    in = new GZIPInputStream(in);
                                } else if (!lowerEncoding.equals(Headers.IDENTITY.toString())) {
                                    throw HttpClientMessages.MESSAGES.invalidContentEncoding(encoding);
                                }
                            }
                            unmarshaller.start(new InputStreamByteInput(in));
                            Throwable exception = (Throwable) unmarshaller.readObject();
                            Map<String, Object> attachments = readAttachments(unmarshaller);
                            int read = in.read();
                            if (read != -1) {
                                HttpClientMessages.MESSAGES.debugf("Unexpected data when reading exception from %s", response);
                                connection.done(true);
                            } else {
                                IoUtils.safeClose(inputStream);
                                connection.done(false);
                            }
                            failureHandler.handleFailure(exception);
                        }
                    } else if (response.getResponseCode() >= 400) {
                        //unknown error
                        failureHandler.handleFailure(HttpClientMessages.MESSAGES.invalidResponseCode(response.getResponseCode(), response));
                        //close the connection to be safe
                        connection.done(true);

                    } else {

                        // set up stickiness metadata for this response
                        if (httpStickinessHandler != null) {
                            httpStickinessHandler.processResponse(result);
                        }

                        if (httpResultHandler != null) {
                            final InputStream in = new WildflyClientInputStream(result.getConnection().getBufferPool(), result.getResponseChannel());
                            InputStream inputStream = in;
                            Closeable doneCallback = () -> {
                                IoUtils.safeClose(in);
                                if (completedTask != null) {
                                    completedTask.run();
                                }
                                connection.done(false);
                            };
                            if (response.getResponseCode() == StatusCodes.NO_CONTENT) {
                                IoUtils.safeClose(in);
                                httpResultHandler.handleResult(null, response, doneCallback);
                            } else {
                                String encoding = response.getResponseHeaders().getFirst(Headers.CONTENT_ENCODING);
                                if (encoding != null) {
                                    String lowerEncoding = encoding.toLowerCase(Locale.ENGLISH);
                                    if (Headers.GZIP.toString().equals(lowerEncoding)) {
                                        inputStream = new GZIPInputStream(inputStream);
                                    } else if (!lowerEncoding.equals(Headers.IDENTITY.toString())) {
                                        throw HttpClientMessages.MESSAGES.invalidContentEncoding(encoding);
                                    }
                                }
                                httpResultHandler.handleResult(inputStream, response, doneCallback);
                            }
                        } else {
                            final InputStream in = new WildflyClientInputStream(result.getConnection().getBufferPool(), result.getResponseChannel());
                            IoUtils.safeClose(in);
                            if (completedTask != null) {
                                completedTask.run();
                            }
                            connection.done(false);
                        }
                    }

                } catch (Exception e) {
                    try {
                        failureHandler.handleFailure(e);
                    } finally {
                        connection.done(true);
                    }
                }
            });
        }

        /**
         * Called upon failed receipt of an HTTP response.
         * @param e the IOException which caused the failure
         */
        @Override
        public void failed(IOException e) {
            try {
                failureHandler.handleFailure(e);
            } finally {
                connection.done(true);
            }
        }
    }
}
