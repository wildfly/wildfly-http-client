/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2024 Red Hat, Inc., and individual contributors
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

import static io.undertow.util.Headers.CONTENT_TYPE;
import static io.undertow.util.StatusCodes.BAD_REQUEST;
import static io.undertow.util.StatusCodes.INTERNAL_SERVER_ERROR;
import static io.undertow.util.StatusCodes.NO_CONTENT;
import static io.undertow.util.StatusCodes.NOT_FOUND;
import static org.wildfly.httpclient.common.ByteInputs.byteInputOf;
import static org.wildfly.httpclient.common.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.common.HeadersHelper.getRequestHeader;
import static org.wildfly.httpclient.common.HeadersHelper.putResponseHeader;
import static org.wildfly.httpclient.ejb.Constants.EJB_DISCOVERY_RESPONSE;
import static org.wildfly.httpclient.ejb.Constants.EJB_RESPONSE_NEW_SESSION;
import static org.wildfly.httpclient.ejb.Constants.EJB_SESSION_ID;
import static org.wildfly.httpclient.ejb.Constants.INVOCATION;
import static org.wildfly.httpclient.ejb.Constants.JSESSIONID_COOKIE_NAME;
import static org.wildfly.httpclient.ejb.Constants.SESSION_OPEN;
import static org.wildfly.httpclient.ejb.Serializer.deserializeMap;
import static org.wildfly.httpclient.ejb.Serializer.deserializeObjectArray;
import static org.wildfly.httpclient.ejb.Serializer.deserializeTransaction;
import static org.wildfly.httpclient.ejb.Serializer.serializeObject;
import static org.wildfly.httpclient.ejb.Serializer.serializeMap;
import static org.wildfly.httpclient.ejb.Serializer.serializeSet;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.server.session.SecureRandomSessionIdGenerator;
import io.undertow.server.session.SessionIdGenerator;
import io.undertow.util.AttachmentKey;
import jakarta.ejb.EJBHome;
import jakarta.ejb.NoSuchEJBException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.EJBClient;
import org.jboss.ejb.client.EJBIdentifier;
import org.jboss.ejb.client.EJBHomeLocator;
import org.jboss.ejb.client.EJBLocator;
import org.jboss.ejb.client.EJBMethodLocator;
import org.jboss.ejb.client.EJBModuleIdentifier;
import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.client.StatefulEJBLocator;
import org.jboss.ejb.client.StatelessEJBLocator;
import org.jboss.ejb.server.Association;
import org.jboss.ejb.server.CancelHandle;
import org.jboss.ejb.server.InvocationRequest;
import org.jboss.ejb.server.ModuleAvailabilityListener;
import org.jboss.ejb.server.SessionOpenRequest;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.SimpleClassResolver;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.AbstractServerHttpHandler;
import org.wildfly.httpclient.common.ContentType;
import org.wildfly.httpclient.common.ElytronIdentityHandler;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpServiceConfig;
import org.wildfly.common.annotation.NotNull;
import org.wildfly.security.auth.server.SecurityIdentity;
import org.wildfly.transaction.client.ImportResult;
import org.wildfly.transaction.client.LocalTransaction;
import org.wildfly.transaction.client.LocalTransactionContext;

import javax.transaction.xa.XAException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * Utility class providing factory methods for creating server-side handlers of Remote EJB over HTTP protocol.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ServerHandlers {

    private final HttpServiceConfig config;
    private final Association association;
    private final ExecutorService executorService;
    private final LocalTransactionContext ctx;
    private final Function<String, Boolean> classFilter;
    private final Map<InvocationIdentifier, CancelHandle> cancellationFlags = new ConcurrentHashMap<>();

    private ServerHandlers(final HttpServiceConfig config, final Association association, final ExecutorService executorService, final LocalTransactionContext ctx,
                           final Function<String, Boolean> classFilter) {
        this.config = config;
        this.association = association;
        this.executorService = executorService;
        this.ctx = ctx;
        this.classFilter = classFilter;
    }

    static ServerHandlers newInstance(final HttpServiceConfig config, final Association association, final ExecutorService executorService, final LocalTransactionContext ctx,
                                      final Function<String, Boolean> classFilter) {
        return new ServerHandlers(config, association, executorService, ctx, classFilter);
    }

    HttpHandler handlerOf(final RequestType requestType) {
        switch (requestType) {
            case INVOKE:
                return new HttpInvocationHandler(config, association, executorService, ctx, cancellationFlags, classFilter);
            case CANCEL :
                return new HttpCancelHandler(config, executorService, cancellationFlags);
            case CREATE_SESSION:
                return new HttpSessionOpenHandler(config, association, executorService, ctx);
            case DISCOVER:
                return new HttpDiscoveryHandler(config, executorService, association);
            default:
                throw new IllegalStateException();
        }
    }

    static final class HttpInvocationHandler extends AbstractEjbHandler {
        private final Association association;
        private final ExecutorService executorService;
        private final LocalTransactionContext localTransactionContext;
        private final Map<InvocationIdentifier, CancelHandle> cancellationFlags;
        private final Function<String, Boolean> classResolverFilter;

        HttpInvocationHandler(HttpServiceConfig config, Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext,
                              Map<InvocationIdentifier, CancelHandle> cancellationFlags, Function<String, Boolean> classResolverFilter) {
            super(config, executorService);
            this.association = association;
            this.executorService = executorService;
            this.localTransactionContext = localTransactionContext;
            this.cancellationFlags = cancellationFlags;
            this.classResolverFilter = classResolverFilter;
        }

        @Override
        protected boolean isValidRequest(final HttpServerExchange exchange) {
            String ct = getRequestHeader(exchange, CONTENT_TYPE);
            ContentType contentType = ContentType.parse(ct);
            if (contentType == null || contentType.getVersion() != 1 || !INVOCATION.getType().equals(contentType.getType())) {
                exchange.setStatusCode(BAD_REQUEST);
                EjbHttpClientMessages.MESSAGES.debugf("Bad content type %s", ct);
                return false;
            }
            return true;
        }

        @Override
        protected void handleInternal(final HttpServerExchange exchange) throws Exception {
            String relativePath = exchange.getRelativePath();
            if(relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            String[] parts = relativePath.split("/");
            if(parts.length < 7) {
                exchange.setStatusCode(NOT_FOUND);
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
            Cookie cookie = exchange.getRequestCookie(JSESSIONID_COOKIE_NAME);
            final String sessionAffinity = cookie != null ? cookie.getValue() : null;
            final EJBIdentifier ejbIdentifier = new EJBIdentifier(app, module, bean, distinct);

            final String cancellationId = getRequestHeader(exchange, Constants.INVOCATION_ID);
            final InvocationIdentifier identifier;
            if(cancellationId != null && sessionAffinity != null) {
                identifier = new InvocationIdentifier(cancellationId, sessionAffinity);
            } else {
                identifier = null;
            }

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
                        final Class<?> view = Class.forName(viewName, false, classLoader);
                        final HttpMarshallerFactory unmarshallingFactory = config.getHttpUnmarshallerFactory(exchange);
                        final Unmarshaller unmarshaller = unmarshallingFactory.createUnmarshaller(new FilteringClassResolver(classLoader, classResolverFilter), HttpProtocolV1ObjectTable.INSTANCE);

                        try (InputStream is = exchange.getInputStream()) {
                            unmarshaller.start(byteInputOf(is));
                            final TransactionInfo txnInfo = deserializeTransaction(unmarshaller);
                            final Object[] methodParams = new Object[parameterTypeNames.length];
                            deserializeObjectArray(unmarshaller, methodParams);
                            final Map<String, Object> contextData = deserializeMap(unmarshaller);
                            unmarshaller.finish();

                            contextData.put(EJBClient.SOURCE_ADDRESS_KEY, exchange.getConnection().getPeerAddress());
                            EJBLocator<?> locator;
                            if (EJBHome.class.isAssignableFrom(view)) {
                                locator = new EJBHomeLocator(view, app, module, bean, distinct, Affinity.LOCAL); //TODO: what is the correct affinity?
                            } else if (sessionID != null) {
                                locator = new StatefulEJBLocator<>(view, app, module, bean, distinct,
                                        SessionID.createSessionID(sessionID), Affinity.LOCAL);
                            } else {
                                locator = new StatelessEJBLocator<>(view, app, module, bean, distinct, Affinity.LOCAL);
                            }

                            final HttpMarshallerFactory marshallerFactory = config.getHttpMarshallerFactory(exchange);
                            final Marshaller marshaller = marshallerFactory.createMarshaller(new FilteringClassResolver(classLoader, classResolverFilter), HttpProtocolV1ObjectTable.INSTANCE);
                            final Transaction transaction;
                            if ((txnInfo.getType() == TransactionInfo.NULL_TRANSACTION) || localTransactionContext == null) { //the TX context may be null in unit tests
                                transaction = null;
                            } else {
                                try {
                                    ImportResult<LocalTransaction> result = localTransactionContext.findOrImportTransaction(txnInfo.getXid(), txnInfo.getRemainingTime());
                                    transaction = result.getTransaction();
                                } catch (XAException e) {
                                    throw new IllegalStateException(e); //TODO: what to do here?
                                }
                            }
                            return new ResolvedInvocation(contextData, methodParams, locator, exchange, marshaller, sessionAffinity, transaction, identifier);
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
                        sendException(exchange, NOT_FOUND, EjbHttpClientMessages.MESSAGES.noSuchMethod());
                    }

                    @Override
                    public void writeSessionNotActive() {
                        if(identifier != null) {
                            cancellationFlags.remove(identifier);
                        }
                        sendException(exchange, INTERNAL_SERVER_ERROR, EjbHttpClientMessages.MESSAGES.sessionNotActive());
                    }

                    @Override
                    public void writeWrongViewType() {
                        if(identifier != null) {
                            cancellationFlags.remove(identifier);
                        }
                        sendException(exchange, NOT_FOUND, EjbHttpClientMessages.MESSAGES.wrongViewType());
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
                        sendException(exchange, INTERNAL_SERVER_ERROR, exception);
                    }

                    @Override
                    public void writeNoSuchEJB() {
                        if(identifier != null) {
                            cancellationFlags.remove(identifier);
                        }
                        sendException(exchange, NOT_FOUND, new NoSuchEJBException());
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
                        sendException(exchange, INTERNAL_SERVER_ERROR, EjbHttpClientMessages.MESSAGES.notStateful());
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

        class ResolvedInvocation implements InvocationRequest.Resolved {
            private final Map<String, Object> contextData;
            private final Object[] methodParams;
            private final EJBLocator<?> locator;
            private final HttpServerExchange exchange;
            private final Marshaller marshaller;
            private final String sessionAffinity;
            private final Transaction transaction;
            private final InvocationIdentifier identifier;

            public ResolvedInvocation(Map<String, Object> contextData, Object[] methodParams, EJBLocator<?> locator, HttpServerExchange exchange, Marshaller marshaller, String sessionAffinity, Transaction transaction, final InvocationIdentifier identifier) {
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
                    putResponseHeader(exchange, CONTENT_TYPE, Constants.EJB_RESPONSE);
    //                                    if (output.getSessionAffinity() != null) {
    //                                        exchange.setResponseCookie(new CookieImpl("JSESSIONID", output.getSessionAffinity()).setPath(WILDFLY_SERVICES));
    //                                    }
                    try (final ByteOutput out = byteOutputOf(exchange.getOutputStream())) {
                        marshaller.start(out);
                        serializeObject(marshaller, result);
                        serializeMap(marshaller, contextData);
                        marshaller.finish();
                    }
                    exchange.endExchange();
                } catch (Exception e) {
                    sendException(exchange, INTERNAL_SERVER_ERROR, e);
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

    private static final class HttpCancelHandler extends AbstractEjbHandler {

        private final Map<InvocationIdentifier, CancelHandle> cancellationFlags;

        HttpCancelHandler(HttpServiceConfig config, ExecutorService executorService, Map<InvocationIdentifier, CancelHandle> cancellationFlags) {
            super(config, executorService);
            this.cancellationFlags = cancellationFlags;
        }

        @Override
        protected boolean isValidRequest(HttpServerExchange exchange) {
            String ct = getRequestHeader(exchange, CONTENT_TYPE);
            ContentType contentType = ContentType.parse(ct);
            if (contentType != null) {
                exchange.setStatusCode(BAD_REQUEST);
                EjbHttpClientMessages.MESSAGES.debugf("Bad content type %s", ct);
                return false;
            }
            return true;
        }

        @Override
        protected void handleInternal(HttpServerExchange exchange) throws Exception {
            String relativePath = exchange.getRelativePath();
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            String[] parts = relativePath.split("/");
            if (parts.length != 6) {
                exchange.setStatusCode(NOT_FOUND);
                return;
            }
            final String app = handleDash(parts[0]);
            final String module = handleDash(parts[1]);
            final String distinct = handleDash(parts[2]);
            final String bean = parts[3];
            String invocationId = parts[4];
            boolean cancelIdRunning = Boolean.parseBoolean(parts[5]);
            Cookie cookie = exchange.getRequestCookie(JSESSIONID_COOKIE_NAME);
            final String sessionAffinity = cookie != null ? cookie.getValue() : null;
            final InvocationIdentifier identifier;
            if (invocationId != null && sessionAffinity != null) {
                identifier = new InvocationIdentifier(invocationId, sessionAffinity);
            } else {
                exchange.setStatusCode(BAD_REQUEST);
                EjbHttpClientMessages.MESSAGES.debugf("Exchange %s did not include both session id and invocation id in cancel request", exchange);
                return;
            }
            CancelHandle handle = cancellationFlags.remove(identifier);
            if (handle != null) {
                handle.cancel(cancelIdRunning);
            }
        }
    }

    private static final class HttpSessionOpenHandler extends AbstractEjbHandler {
        private final Association association;
        private final ExecutorService executorService;
        private final SessionIdGenerator sessionIdGenerator = new SecureRandomSessionIdGenerator();
        private final LocalTransactionContext localTransactionContext;

        HttpSessionOpenHandler(HttpServiceConfig config, Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext) {
            super(config, executorService);
            this.association = association;
            this.executorService = executorService;
            this.localTransactionContext = localTransactionContext;
        }

        @Override
        protected boolean isValidRequest(HttpServerExchange exchange) {
            String ct = getRequestHeader(exchange, CONTENT_TYPE);
            ContentType contentType = ContentType.parse(ct);
            if (contentType == null || contentType.getVersion() != 1 || !SESSION_OPEN.getType().equals(contentType.getType())) {
                exchange.setStatusCode(BAD_REQUEST);
                EjbHttpClientMessages.MESSAGES.debugf("Bad content type %s", ct);
                return false;
            }
            return true;
        }

        @Override
        protected void handleInternal(HttpServerExchange exchange) throws Exception {
            String relativePath = exchange.getRelativePath();
            if(relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            String[] parts = relativePath.split("/");
            if(parts.length != 4) {
                exchange.setStatusCode(NOT_FOUND);
                return;
            }
            final String app = handleDash(parts[0]);
            final String module = handleDash(parts[1]);
            final String distinct = handleDash(parts[2]);
            final String bean = parts[3];

            Cookie cookie = exchange.getRequestCookie(JSESSIONID_COOKIE_NAME);
            String sessionAffinity = null;
            if (cookie != null) {
                sessionAffinity = cookie.getValue();
            }

            final EJBIdentifier ejbIdentifier = new EJBIdentifier(app, module, bean, distinct);
            exchange.dispatch(executorService, () -> {
                final TransactionInfo txnInfo;
                try {
                    final HttpMarshallerFactory httpUnmarshallerFactory = config.getHttpUnmarshallerFactory(exchange);
                    final Unmarshaller unmarshaller = httpUnmarshallerFactory.createUnmarshaller(HttpProtocolV1ObjectTable.INSTANCE);

                    try (InputStream is = exchange.getInputStream()) {
                        unmarshaller.start(byteInputOf(is));
                        txnInfo = deserializeTransaction(unmarshaller);
                        unmarshaller.finish();
                    }
                } catch (Exception e) {
                    sendException(exchange, INTERNAL_SERVER_ERROR, e);
                    return;
                }
                final Transaction transaction;
                if (txnInfo.getType() == TransactionInfo.NULL_TRANSACTION || localTransactionContext == null) { //the TX context may be null in unit tests
                    transaction = null;
                } else {
                    try {
                        ImportResult<LocalTransaction> result = localTransactionContext.findOrImportTransaction(txnInfo.getXid(), txnInfo.getRemainingTime());
                        transaction = result.getTransaction();
                    } catch (XAException e) {
                        throw new IllegalStateException(e); //TODO: what to do here?
                    }
                }

                association.receiveSessionOpenRequest(new SessionOpenRequest() {
                    @Override
                    public boolean hasTransaction() {
                        return txnInfo != null;
                    }

                    @Override
                    public Transaction getTransaction() throws SystemException, IllegalStateException {
                        return transaction;
                    }

                    @Override
                    public SocketAddress getPeerAddress() {
                        return exchange.getSourceAddress();
                    }

                    @Override
                    public SocketAddress getLocalAddress() {
                        return exchange.getDestinationAddress();
                    }

                    @Override
                    public Executor getRequestExecutor() {
                        return executorService != null ? executorService : exchange.getIoThread().getWorker();
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
                        sendException(exchange, INTERNAL_SERVER_ERROR, exception);
                    }

                    @Override
                    public void writeNoSuchEJB() {
                        sendException(exchange, NOT_FOUND, new NoSuchEJBException());
                    }

                    @Override
                    public void writeWrongViewType() {
                        sendException(exchange, NOT_FOUND, EjbHttpClientMessages.MESSAGES.wrongViewType());
                    }

                    @Override
                    public void writeCancelResponse() {
                        throw new RuntimeException("nyi");
                    }

                    @Override
                    public void writeNotStateful() {
                        sendException(exchange, INTERNAL_SERVER_ERROR, EjbHttpClientMessages.MESSAGES.notStateful());
                    }

                    @Override
                    public void convertToStateful(@NotNull SessionID sessionId) throws IllegalArgumentException, IllegalStateException {

                        Cookie sessionCookie = exchange.getRequestCookie(JSESSIONID_COOKIE_NAME);
                        if (sessionCookie == null) {
                            String rootPath = exchange.getResolvedPath();
                            int ejbIndex = rootPath.lastIndexOf("/ejb");
                            if (ejbIndex > 0) {
                                rootPath = rootPath.substring(0, ejbIndex);
                            }
                            exchange.setResponseCookie(new CookieImpl(JSESSIONID_COOKIE_NAME, sessionIdGenerator.createSessionId()).setPath(rootPath));
                        }

                        putResponseHeader(exchange, CONTENT_TYPE, EJB_RESPONSE_NEW_SESSION);
                        putResponseHeader(exchange, EJB_SESSION_ID, Base64.getUrlEncoder().encodeToString(sessionId.getEncodedForm()));

                        exchange.setStatusCode(NO_CONTENT);
                        exchange.endExchange();
                    }

                    @Override
                    public <C> C getProviderInterface(Class<C> providerInterfaceType) {
                        return null;
                    }
                });
            });
        }
    }

    private static final class HttpDiscoveryHandler extends AbstractEjbHandler {
        private final Set<EJBModuleIdentifier> availableModules = new HashSet<>();

        public HttpDiscoveryHandler(HttpServiceConfig config, ExecutorService executorService, Association association) {
            super(config, executorService);
            association.registerModuleAvailabilityListener(new ModuleAvailabilityListener() {
                @Override
                public void moduleAvailable(List<EJBModuleIdentifier> modules) {
                    availableModules.addAll(modules);
                }

                @Override
                public void moduleUnavailable(List<EJBModuleIdentifier> modules) {
                    availableModules.removeAll(modules);
                }
            });
        }

        @Override
        protected void handleInternal(HttpServerExchange exchange) throws Exception {
            putResponseHeader(exchange, CONTENT_TYPE, EJB_DISCOVERY_RESPONSE);
            byte[] data;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            Marshaller marshaller = config.getHttpMarshallerFactory(exchange)
                    .createMarshaller(HttpProtocolV1ObjectTable.INSTANCE);
            ByteOutput byteOutput = byteOutputOf(out);
            try (byteOutput) {
                marshaller.start(byteOutput);
                serializeSet(marshaller, availableModules);
                marshaller.finish();
                data = out.toByteArray();
            }
            exchange.getResponseSender().send(ByteBuffer.wrap(data));
        }
    }

    private abstract static class AbstractEjbHandler extends AbstractServerHttpHandler {
        private final ExecutorService executorService;

        private static final AttachmentKey<ExecutorService> EXECUTOR = AttachmentKey.create(ExecutorService.class);

        public AbstractEjbHandler(final HttpServiceConfig config, final ExecutorService executorService) {
            super(config);
            this.executorService = executorService;
        }

        @Override
        public final void processRequest(HttpServerExchange exchange) throws Exception {
            if (exchange.isInIoThread()) {
                if (executorService == null) {
                    exchange.dispatch(this);
                } else {
                    exchange.putAttachment(EXECUTOR, executorService);
                    exchange.dispatch(executorService, this);
                }
                return;
            } else if (executorService != null && exchange.getAttachment(EXECUTOR) == null) {
                exchange.putAttachment(EXECUTOR, executorService);
                exchange.dispatch(executorService, this);
                return;
            }
            exchange.startBlocking();
            handleInternal(exchange);
        }

        protected abstract void handleInternal(HttpServerExchange exchange) throws Exception;

        protected static String handleDash(final String s) {
            return "-".equals(s) ? "" : s;
        }
    }
}
