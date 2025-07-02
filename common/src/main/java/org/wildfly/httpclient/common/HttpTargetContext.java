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

import static io.undertow.util.StatusCodes.NO_CONTENT;
import static io.undertow.util.Headers.CHUNKED;
import static io.undertow.util.Headers.COOKIE;
import static io.undertow.util.Headers.CONTENT_ENCODING;
import static io.undertow.util.Headers.CONTENT_TYPE;
import static io.undertow.util.Headers.GZIP;
import static io.undertow.util.Headers.HOST;
import static io.undertow.util.Headers.IDENTITY;
import static io.undertow.util.Headers.SET_COOKIE;
import static io.undertow.util.Headers.TRANSFER_ENCODING;
import static org.wildfly.httpclient.common.ByteInputs.byteInputOf;
import static org.wildfly.httpclient.common.HeadersHelper.addRequestHeader;
import static org.wildfly.httpclient.common.HeadersHelper.containsRequestHeader;
import static org.wildfly.httpclient.common.HeadersHelper.getRequestHeader;
import static org.wildfly.httpclient.common.HeadersHelper.getResponseHeader;
import static org.wildfly.httpclient.common.HeadersHelper.getResponseHeaders;
import static org.wildfly.httpclient.common.HeadersHelper.putRequestHeader;

import io.undertow.client.ClientCallback;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.handlers.Cookie;
import io.undertow.util.AbstractAttachable;
import io.undertow.util.Cookies;
import io.undertow.util.HeaderValues;
import io.undertow.util.Methods;
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
import java.security.AccessController;
import java.security.GeneralSecurityException;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Http target context used by client side.
 *
 * @author Stuart Douglas
 */
public class HttpTargetContext extends AbstractAttachable {

    private static final AuthenticationContextConfigurationClient AUTH_CONTEXT_CLIENT;
    private static final String GENERAL_EXCEPTION_ON_FAILED_AUTH_PROPERTY = "org.wildfly.httpclient.io-exception-on-failed-auth";

    static {
        AUTH_CONTEXT_CLIENT = AccessController.doPrivileged((PrivilegedAction<AuthenticationContextConfigurationClient>) () -> new AuthenticationContextConfigurationClient());
    }


    private static final String EXCEPTION_TYPE = "application/x-wf-jbmar-exception";

    private static final String JSESSIONID = "JSESSIONID";

    private final HttpConnectionPool connectionPool;
    private final boolean eagerlyAcquireAffinity;
    private volatile CountDownLatch sessionAffinityLatch = new CountDownLatch(1);
    private volatile String sessionId;
    private final URI uri;
    private final AuthenticationContext initAuthenticationContext;

    private final AtomicBoolean affinityRequestSent = new AtomicBoolean();
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
            acquireAffinitiy(AUTH_CONTEXT_CLIENT.getAuthenticationConfiguration(uri, AuthenticationContext.captureCurrent()));
        }
    }

    /**
     * Returns the protocol version to be used by this target context.
     * @return the protocol version
     */
    public int getProtocolVersion() {
        return connectionPool.getProtocolVersion();
    }

    private void acquireAffinitiy(AuthenticationConfiguration authenticationConfiguration) {
        if (affinityRequestSent.compareAndSet(false, true)) {
            acquireSessionAffinity(sessionAffinityLatch, authenticationConfiguration);
        }
    }


    private void acquireSessionAffinity(CountDownLatch latch, AuthenticationConfiguration authenticationConfiguration) {
        ClientRequest request = new ClientRequest();
        request.setMethod(Methods.GET);
        request.setPath(uri.getPath() + "/common/v1/affinity");
        AuthenticationContext context = AuthenticationContext.captureCurrent();
        SSLContext sslContext;
        try {
            sslContext = AUTH_CONTEXT_CLIENT.getSSLContext(uri, context);
        } catch (GeneralSecurityException e) {
            latch.countDown();
            HttpClientMessages.MESSAGES.failedToAcquireSession(e);
            return;
        }
        sendRequest(request, sslContext, authenticationConfiguration, null, null, (e) -> {
            latch.countDown();
            HttpClientMessages.MESSAGES.failedToAcquireSession(e);
        }, null, latch::countDown);
    }

    public void sendRequest(ClientRequest request, SSLContext sslContext, AuthenticationConfiguration authenticationConfiguration, HttpMarshaller httpMarshaller, HttpResultHandler httpResultHandler, HttpFailureHandler failureHandler, ContentType expectedResponse, Runnable completedTask) {
        sendRequest(request, sslContext, authenticationConfiguration, httpMarshaller, httpResultHandler, failureHandler, expectedResponse, completedTask, false);
    }

    public void sendRequest(ClientRequest request, SSLContext sslContext, AuthenticationConfiguration authenticationConfiguration, HttpMarshaller httpMarshaller, HttpResultHandler httpResultHandler, HttpFailureHandler failureHandler, ContentType expectedResponse, Runnable completedTask, boolean allowNoContent) {
        final ClassLoader tccl = getContextClassLoader();
        connectionPool.getConnection(connection -> sendRequestInternal(connection, request, authenticationConfiguration, httpMarshaller, httpResultHandler, failureHandler, expectedResponse, completedTask, allowNoContent, false, sslContext, tccl), failureHandler::handleFailure, false, sslContext);
    }

    private void sendRequestInternal(final HttpConnectionPool.ConnectionHandle connection, final ClientRequest request, AuthenticationConfiguration authenticationConfiguration, HttpMarshaller httpMarshaller, HttpResultHandler httpResultHandler, HttpFailureHandler failureHandler, ContentType expectedResponse, Runnable completedTask, boolean allowNoContent, boolean retry, SSLContext sslContext, ClassLoader classLoader) {
        if (sessionId != null) {
            addRequestHeader(request, COOKIE, JSESSIONID + "=" + sessionId);
        }
        try {
            if (!containsRequestHeader(request, HOST)) {
                String host;
                int port = connection.getUri().getPort();
                if (port == -1) {
                    host = connection.getUri().getHost();
                } else {
                    host = connection.getUri().getHost() + ":" + port;
                }
                putRequestHeader(request, HOST, host);
            }

            final SSLContext finalSslContext = (sslContext == null) ?
                AUTH_CONTEXT_CLIENT.getSSLContext(uri, initAuthenticationContext)
                : sslContext;
            final AuthenticationConfiguration finalAuthenticationConfiguration = (authenticationConfiguration == null) ?
                AUTH_CONTEXT_CLIENT.getAuthenticationConfiguration(uri, initAuthenticationContext)
                : authenticationConfiguration;

            if (containsRequestHeader(request, CONTENT_TYPE)) {
                putRequestHeader(request, TRANSFER_ENCODING, CHUNKED);
            }
            final boolean authAdded = retry || connection.getAuthenticationContext().prepareRequest(connection.getUri(), request, authenticationConfiguration);
            connection.sendRequest(request, new ClientCallback<ClientExchange>() {
                @Override
                public void completed(ClientExchange result) {
                    result.setResponseListener(new ClientCallback<ClientExchange>() {
                        @Override
                        public void completed(ClientExchange result) {
                            connection.getConnection().getWorker().execute(() -> {
                                ClientResponse response = result.getResponse();
                                if (!authAdded || connection.getAuthenticationContext().isStale(result)) {
                                    handleSessionAffinity(request, response);
                                    if (connection.getAuthenticationContext().handleResponse(response)) {
                                        URI uri = connection.getUri();
                                        connection.done(false);
                                        final AtomicBoolean done = new AtomicBoolean();
                                        ChannelListener<StreamSourceChannel> listener = ChannelListeners.drainListener(Long.MAX_VALUE, channel -> {
                                            done.set(true);
                                            connectionPool.getConnection((connection) -> {
                                                if (connection.getAuthenticationContext().prepareRequest(uri, request, finalAuthenticationConfiguration)) {
                                                    //retry the invocation
                                                    sendRequestInternal(connection, request, finalAuthenticationConfiguration, httpMarshaller, httpResultHandler, failureHandler, expectedResponse, completedTask, allowNoContent, true, finalSslContext, classLoader);
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

                                ContentType type = ContentType.parse(getResponseHeader(response, CONTENT_TYPE));
                                final boolean ok;
                                final boolean isException;
                                if (type == null) {
                                    ok = expectedResponse == null || (allowNoContent && response.getResponseCode() == NO_CONTENT);
                                    isException = false;
                                } else {
                                    if (type.getType().equals(EXCEPTION_TYPE)) {
                                        ok = true;
                                        isException = true;
                                    } else if (expectedResponse == null) {
                                        ok = false;
                                        isException = false;
                                    } else {
                                        ok = expectedResponse.equals(type);
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
                                    handleSessionAffinity(request, response);

                                    if (isException) {
                                        final Unmarshaller unmarshaller = getHttpMarshallerFactory(request).createUnmarshaller(classLoader);
                                        try (WildflyClientInputStream inputStream = new WildflyClientInputStream(result.getConnection().getBufferPool(), result.getResponseChannel())) {
                                            InputStream in = inputStream;
                                            String encoding = getResponseHeader(response, CONTENT_ENCODING);
                                            if (encoding != null) {
                                                String lowerEncoding = encoding.toLowerCase(Locale.ENGLISH);
                                                if (GZIP.toString().equals(lowerEncoding)) {
                                                    in = new GZIPInputStream(in);
                                                } else if (!lowerEncoding.equals(IDENTITY.toString())) {
                                                    throw HttpClientMessages.MESSAGES.invalidContentEncoding(encoding);
                                                }
                                            }
                                            unmarshaller.start(byteInputOf(in));
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
                                            if (response.getResponseCode() == NO_CONTENT) {
                                                httpResultHandler.handleResult(null, response, doneCallback);
                                            } else {
                                                String encoding = getResponseHeader(response, CONTENT_ENCODING);
                                                if (encoding != null) {
                                                    String lowerEncoding = encoding.toLowerCase(Locale.ENGLISH);
                                                    if (GZIP.toString().equals(lowerEncoding)) {
                                                        inputStream = new GZIPInputStream(inputStream);
                                                    } else if (!lowerEncoding.equals(IDENTITY.toString())) {
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

                        @Override
                        public void failed(IOException e) {
                            try {
                                failureHandler.handleFailure(e);
                            } finally {
                                connection.done(true);
                            }
                        }
                    });

                    if (httpMarshaller != null) {
                        //marshalling is blocking, we need to delegate, otherwise we may need to buffer arbitrarily large requests
                        connection.getConnection().getWorker().execute(() -> {
                            try (OutputStream outputStream = new WildflyClientOutputStream(result.getRequestChannel(), result.getConnection().getBufferPool())) {

                                // marshall the locator and method params
                                // start the marshaller
                                boolean compress = false;
                                String encoding = getRequestHeader(request, CONTENT_ENCODING);
                                if (encoding != null) {
                                    String lowerEncoding = encoding.toLowerCase(Locale.ENGLISH);
                                    if (GZIP.toString().equals(lowerEncoding)) {
                                        compress = true;
                                    }
                                }

                                httpMarshaller.marshall(compress ? new GZIPOutputStream(outputStream) : outputStream);

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

                @Override
                public void failed(IOException e) {
                    try {
                        failureHandler.handleFailure(e);
                    } finally {
                        connection.done(true);
                    }
                }
            });
        } catch (Throwable e) {
            try {
                failureHandler.handleFailure(e);
            } finally {
                connection.done(true);
            }
        }
    }

    private void handleSessionAffinity(ClientRequest request, ClientResponse response) {
        //handle session affinity
        HeaderValues cookies = getResponseHeaders(response, SET_COOKIE);
        if (cookies != null) {
            for (String cookie : cookies) {
                Cookie c = Cookies.parseSetCookieHeader(cookie);
                if (c.getName().equals(JSESSIONID)) {
                    HttpClientMessages.MESSAGES.debugf("%s Cookie found in Set-Cookie header in the response. cookie name = [%s], cookie value = [%s], cookie path = [%s]", JSESSIONID, c.getName(), c.getValue(), c.getPath());
                    String path = c.getPath();
                    if (path == null || path.isEmpty() || request.getPath().startsWith(path)) {
                        HttpClientMessages.MESSAGES.debugf("Use sessionId %s as a request cookie for session affinity", c.getValue());
                        setSessionId(c.getValue());
                    }
                }
            }
        }
        if (getSessionId() != null) {
            putRequestHeader(request, COOKIE, JSESSIONID + "=" + getSessionId());
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

    public HttpMarshallerFactory getHttpMarshallerFactory(ClientRequest request) {
        return this.httpMarshallerFactoryProvider.getMarshallerFactory(request);
    }

    public HttpConnectionPool getConnectionPool() {
        return connectionPool;
    }

    public String getSessionId() {
        return sessionId;
    }

    void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public URI getUri() {
        return uri;
    }

    public void clearSessionId() {
        awaitSessionId(true, null); //to prevent a race make sure we have one before we clear it
        synchronized (this) {
            CountDownLatch old = sessionAffinityLatch;
            sessionAffinityLatch = new CountDownLatch(1);
            old.countDown();
            this.affinityRequestSent.set(false);
            this.sessionId = null;
        }
    }

    public String awaitSessionId(boolean required, AuthenticationConfiguration authConfig) {
        if (required) {
            acquireAffinitiy(authConfig);
        }
        if (affinityRequestSent.get()) {
            try {
                sessionAffinityLatch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return sessionId;
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
}
