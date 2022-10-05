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

package org.wildfly.httpclient.ejb;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.EJBClient;
import org.jboss.ejb.client.EJBHomeLocator;
import org.jboss.ejb.client.EJBIdentifier;
import org.jboss.ejb.client.EJBLocator;
import org.jboss.ejb.client.EJBMethodLocator;
import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.client.StatefulEJBLocator;
import org.jboss.ejb.client.StatelessEJBLocator;
import org.jboss.ejb.server.Association;
import org.jboss.ejb.server.CancelHandle;
import org.jboss.ejb.server.InvocationRequest;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.SimpleClassResolver;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.common.annotation.NotNull;
import org.wildfly.httpclient.common.ContentType;
import org.wildfly.httpclient.common.ElytronIdentityHandler;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpServerHelper;
import org.wildfly.httpclient.common.HttpServiceConfig;
import org.wildfly.httpclient.common.NoFlushByteOutput;
import org.wildfly.httpclient.common.Version;
import org.wildfly.security.auth.server.SecurityIdentity;
import org.wildfly.transaction.client.ImportResult;
import org.wildfly.transaction.client.LocalTransaction;
import org.wildfly.transaction.client.LocalTransactionContext;

import jakarta.ejb.EJBHome;
import jakarta.ejb.NoSuchEJBException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import javax.transaction.xa.XAException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import static org.wildfly.httpclient.ejb.EjbConstants.INVOCATION;
import static org.wildfly.httpclient.ejb.EjbConstants.JSESSIONID_COOKIE_NAME;

/**
 * A server-side handler for processing EJB client invocation requests.
 *
 * @author Stuart Douglas
 * @author Richard Achmatowicz
 */
class HttpInvocationHandler extends RemoteHTTPHandler {

    private final Association association;
    private final ExecutorService executorService;
    private final LocalTransactionContext localTransactionContext;
    private final Map<InvocationIdentifier, CancelHandle> cancellationFlags;
    private final Function<String, Boolean> classResolverFilter;
    private final HttpServiceConfig httpServiceConfig;

    HttpInvocationHandler(Version version, Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext,
                          Map<InvocationIdentifier, CancelHandle> cancellationFlags, Function<String, Boolean> classResolverFilter,
                          HttpServiceConfig httpServiceConfig) {
        super(version, executorService);
        this.association = association;
        this.executorService = executorService;
        this.localTransactionContext = localTransactionContext;
        this.cancellationFlags = cancellationFlags;
        this.classResolverFilter = classResolverFilter;
        this.httpServiceConfig = httpServiceConfig;
    }

    @Override
    protected void handleInternal(HttpServerExchange exchange) throws Exception {

        // validate content type of payload
        String ct = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        ContentType contentType = ContentType.parse(ct);
        if (contentType == null || contentType.getVersion() != 1 || !INVOCATION.getType().equals(contentType.getType())) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            EjbHttpClientMessages.MESSAGES.debugf("Bad content type %s", ct);
            return;
        }

        // parse request path
        String relativePath = exchange.getRelativePath();
        if(relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        String[] parts = relativePath.split("/");
        if(parts.length < 7) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            return;
        }
        final String app = handleDash(parts[0]);
        final String module = handleDash(parts[1]);
        final String distinct = handleDash(parts[2]);
        final String bean = parts[3];
        String originalSessionId = handleDash(parts[4]);
        final byte[] sessionID = originalSessionId.isEmpty() ? null : Base64.getUrlDecoder().decode(originalSessionId);
        String viewName = parts[5];
        String method = parts[6];
        String[] parameterTypeNames = new String[parts.length - 7];
        System.arraycopy(parts, 7, parameterTypeNames, 0, parameterTypeNames.length);

        // process Cookies and Headers
        Cookie cookie = exchange.getRequestCookies().get(JSESSIONID_COOKIE_NAME);
        final String sessionAffinity = cookie != null ? cookie.getValue() : null;
        final EJBIdentifier ejbIdentifier = new EJBIdentifier(app, module, bean, distinct);

        final String cancellationId = exchange.getRequestHeaders().getFirst(EjbConstants.INVOCATION_ID);
        final InvocationIdentifier identifier;
        if(cancellationId != null && sessionAffinity != null) {
            identifier = new InvocationIdentifier(cancellationId, sessionAffinity);
        } else {
            identifier = null;
        }

        // process request
        exchange.dispatch(executorService, () -> {
            CancelHandle handle = association.receiveInvocationRequest(new InvocationRequest() {

                @Override
                public SocketAddress getPeerAddress() {
                    return exchange.getSourceAddress();
                }

                @Override
                public SocketAddress getLocalAddress() {
                    return exchange.getDestinationAddress();
                }

                @Override
                public Resolved getRequestContent(final ClassLoader classLoader) throws IOException, ClassNotFoundException {

                    Object[] methodParams = new Object[parameterTypeNames.length];
                    final Class<?> view = Class.forName(viewName, false, classLoader);
                    final HttpMarshallerFactory unmarshallingFactory = httpServiceConfig.getHttpUnmarshallerFactory(exchange);
                    final Unmarshaller unmarshaller = unmarshallingFactory.createUnmarshaller(new FilteringClassResolver(classLoader, classResolverFilter), HttpProtocolV1ObjectTable.INSTANCE);

                    try (InputStream inputStream = exchange.getInputStream()) {
                        unmarshaller.start(new InputStreamByteInput(inputStream));
                        ReceivedTransaction txConfig = readTransaction(unmarshaller);


                        final Transaction transaction;
                        if (txConfig == null || localTransactionContext == null) { //the TX context may be null in unit tests
                            transaction = null;
                        } else {
                            try {
                                ImportResult<LocalTransaction> result = localTransactionContext.findOrImportTransaction(txConfig.getXid(), txConfig.getRemainingTime());
                                transaction = result.getTransaction();
                            } catch (XAException e) {
                                throw new IllegalStateException(e); //TODO: what to do here?
                            }
                        }
                        for (int i = 0; i < parameterTypeNames.length; ++i) {
                            methodParams[i] = unmarshaller.readObject();
                        }
                        final Map<String, Object> contextData;
                        final int attachmentCount = PackedInteger.readPackedInteger(unmarshaller);
                        if (attachmentCount > 0) {
                            contextData = new HashMap<>();
                            for (int i = 0; i < attachmentCount; ++i) {
                                Object o = unmarshaller.readObject();
                                String key = (String) o;
                                Object value = unmarshaller.readObject();
                                contextData.put(key, value);
                            }
                        } else {
                            contextData = new HashMap<>();
                        }
                        contextData.put(EJBClient.SOURCE_ADDRESS_KEY, exchange.getConnection().getPeerAddress());

                        unmarshaller.finish();

                        EJBLocator<?> locator;
                        if (EJBHome.class.isAssignableFrom(view)) {
                            locator = new EJBHomeLocator(view, app, module, bean, distinct, Affinity.LOCAL); //TODO: what is the correct affinity?
                        } else if (sessionID != null) {
                            locator = new StatefulEJBLocator<>(view, app, module, bean, distinct,
                                    SessionID.createSessionID(sessionID), Affinity.LOCAL);
                        } else {
                            locator = new StatelessEJBLocator<>(view, app, module, bean, distinct, Affinity.LOCAL);
                        }

                        final HttpMarshallerFactory marshallerFactory = httpServiceConfig.getHttpMarshallerFactory(exchange);
                        final Marshaller marshaller = marshallerFactory.createMarshaller(new FilteringClassResolver(classLoader, classResolverFilter), HttpProtocolV1ObjectTable.INSTANCE);
                        return new ResolvedInvocation(getVersion(), contextData, methodParams, locator, exchange, marshaller, sessionAffinity, transaction, identifier);
                    } catch (IOException | ClassNotFoundException e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new IOException(e);
                    }
                }

                @Override
                public EJBMethodLocator getMethodLocator() {
                    return new EJBMethodLocator(method, parameterTypeNames);
                }

                @Override
                public void writeNoSuchMethod() {
                    if(identifier != null) {
                        cancellationFlags.remove(identifier);
                    }
                    HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.NOT_FOUND, EjbHttpClientMessages.MESSAGES.noSuchMethod());
                }

                @Override
                public void writeSessionNotActive() {
                    if(identifier != null) {
                        cancellationFlags.remove(identifier);
                    }
                    HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.INTERNAL_SERVER_ERROR, EjbHttpClientMessages.MESSAGES.sessionNotActive());
                }

                @Override
                public void writeWrongViewType() {
                    if(identifier != null) {
                        cancellationFlags.remove(identifier);
                    }
                    HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.NOT_FOUND, EjbHttpClientMessages.MESSAGES.wrongViewType());
                }

                @Override
                public Executor getRequestExecutor() {
                    return executorService == null ? exchange.getIoThread().getWorker() : executorService;
                }

                @Override
                public String getProtocol() {
                    return exchange.getProtocol().toString();
                }

                @Override
                public boolean isBlockingCaller() {
                    return false;
                }

                @Override
                public EJBIdentifier getEJBIdentifier() {
                    return ejbIdentifier;
                }

//                @Override
                public SecurityIdentity getSecurityIdentity() {
                    return exchange.getAttachment(ElytronIdentityHandler.IDENTITY_KEY);
                }

                @Override
                public void writeException(@NotNull Exception exception) {
                    if(identifier != null) {
                        cancellationFlags.remove(identifier);
                    }
                    HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.INTERNAL_SERVER_ERROR, exception);
                }

                @Override
                public void writeNoSuchEJB() {
                    if(identifier != null) {
                        cancellationFlags.remove(identifier);
                    }
                    HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.NOT_FOUND, new NoSuchEJBException());
                }

                @Override
                public void writeCancelResponse() {
                    if(identifier != null) {
                        cancellationFlags.remove(identifier);
                    }
                    //we don't actually need to implement this method
                }

                @Override
                public void writeNotStateful() {
                    if(identifier != null) {
                        cancellationFlags.remove(identifier);
                    }
                    HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.INTERNAL_SERVER_ERROR, EjbHttpClientMessages.MESSAGES.notStateful());
                }

                @Override
                public void convertToStateful(@NotNull SessionID sessionId) throws IllegalArgumentException, IllegalStateException {
                    throw new RuntimeException("nyi");
                }
            });
            if(handle != null && identifier != null) {
                cancellationFlags.put(identifier, handle);
            }
        });
    }

    private static String handleDash(String s) {
        if (s.equals("-")) {
            return "";
        }
        return s;
    }

    class ResolvedInvocation implements InvocationRequest.Resolved {
        private final Version version;
        private final Map<String, Object> contextData;
        private final Object[] methodParams;
        private final EJBLocator<?> locator;
        private final HttpServerExchange exchange;
        private final Marshaller marshaller;
        private final String sessionAffinity;
        private final Transaction transaction;
        private final InvocationIdentifier identifier;

        public ResolvedInvocation(Version version, Map<String, Object> contextData, Object[] methodParams, EJBLocator<?> locator, HttpServerExchange exchange, Marshaller marshaller, String sessionAffinity, Transaction transaction, final InvocationIdentifier identifier) {
            this.version = version;
            this.contextData = contextData;
            this.methodParams = methodParams;
            this.locator = locator;
            this.exchange = exchange;
            this.marshaller = marshaller;
            this.sessionAffinity = sessionAffinity;
            this.transaction = transaction;
            this.identifier = identifier;
        }

        @Override
        public Map<String, Object> getAttachments() {
            return contextData;
        }

        @Override
        public Object[] getParameters() {
            return methodParams;
        }

        @Override
        public EJBLocator<?> getEJBLocator() {
            return locator;
        }

        @Override
        public boolean hasTransaction() {
            return transaction != null;
        }

        @Override
        public Transaction getTransaction() throws SystemException, IllegalStateException {
            return transaction;
        }

        String getSessionAffinity() {
            return sessionAffinity;
        }

        HttpServerExchange getExchange() {
            return exchange;
        }

        @Override
        public void writeInvocationResult(Object result) {
            if(identifier != null) {
                cancellationFlags.remove(identifier);
            }
            try {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, EjbConstants.EJB_RESPONSE.toString());
//                                    if (output.getSessionAffinity() != null) {
//                                        exchange.getResponseCookies().put("JSESSIONID", new CookieImpl("JSESSIONID", output.getSessionAffinity()).setPath(WILDFLY_SERVICES));
//                                    }
                OutputStream outputStream = exchange.getOutputStream();
                final ByteOutput byteOutput = new NoFlushByteOutput(Marshalling.createByteOutput(outputStream));
                // start the marshaller
                marshaller.start(byteOutput);
                marshaller.writeObject(result);
                // TODO: Do we really need to send this back?
                PackedInteger.writePackedInteger(marshaller, contextData.size());
                for(Map.Entry<String, Object> entry : contextData.entrySet()) {
                    marshaller.writeObject(entry.getKey());
                    marshaller.writeObject(entry.getValue());
                }
                marshaller.finish();
                marshaller.flush();
                exchange.endExchange();
            } catch (Exception e) {
                HttpServerHelper.sendException(exchange, httpServiceConfig, 500, e);
            }
        }
    }

    private static class FilteringClassResolver extends SimpleClassResolver {
        private final Function<String, Boolean> classResolverFilter;
        FilteringClassResolver(ClassLoader classLoader, Function<String, Boolean> classResolverFilter) {
            super(classLoader);
            this.classResolverFilter = classResolverFilter;
        }

        @Override
        public Class<?> resolveClass(Unmarshaller unmarshaller, String name, long serialVersionUID) throws IOException, ClassNotFoundException {
            checkFilter(name);
            return super.resolveClass(unmarshaller, name, serialVersionUID);
        }

        @Override
        public Class<?> resolveProxyClass(Unmarshaller unmarshaller, String[] interfaces) throws IOException, ClassNotFoundException {
            for (String name : interfaces) {
                checkFilter(name);
            }
            return super.resolveProxyClass(unmarshaller, interfaces);
        }

        private void checkFilter(String className) throws InvalidClassException {
            if (classResolverFilter != null && classResolverFilter.apply(className) != Boolean.TRUE) {
                throw EjbHttpClientMessages.MESSAGES.cannotResolveFilteredClass(className);
            }

        }
    }
}
