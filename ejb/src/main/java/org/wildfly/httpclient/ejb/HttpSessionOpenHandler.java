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
import io.undertow.server.handlers.CookieImpl;
import io.undertow.server.session.SecureRandomSessionIdGenerator;
import io.undertow.server.session.SessionIdGenerator;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jboss.ejb.client.EJBIdentifier;
import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.server.Association;
import org.jboss.ejb.server.SessionOpenRequest;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.common.annotation.NotNull;
import org.wildfly.httpclient.common.ContentType;
import org.wildfly.httpclient.common.ElytronIdentityHandler;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpServerHelper;
import org.wildfly.httpclient.common.HttpServiceConfig;
import org.wildfly.httpclient.common.Version;
import org.wildfly.security.auth.server.SecurityIdentity;
import org.wildfly.transaction.client.ImportResult;
import org.wildfly.transaction.client.LocalTransaction;
import org.wildfly.transaction.client.LocalTransactionContext;

import javax.ejb.NoSuchEJBException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.xa.XAException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.Base64;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import static org.wildfly.httpclient.ejb.EjbConstants.JSESSIONID_COOKIE_NAME;
import static org.wildfly.httpclient.ejb.EjbConstants.SESSION_OPEN;

/**
 * A versioned Http handler for EJB client open session requests.
 *
 * @author Stuart Douglas
 * @author <a href="rachmato@redhat.com">Richard Achmatowicz</a>
 */
class HttpSessionOpenHandler extends RemoteHTTPHandler {

    private final Association association;
    private final ExecutorService executorService;
    private final SessionIdGenerator sessionIdGenerator = new SecureRandomSessionIdGenerator();
    private final LocalTransactionContext localTransactionContext;
    private final HttpServiceConfig httpServiceConfig;

    HttpSessionOpenHandler(Version version, Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext, HttpServiceConfig httpServiceConfig) {
        super(version, executorService);
        this.association = association;
        this.executorService = executorService;
        this.localTransactionContext = localTransactionContext;
        this.httpServiceConfig = httpServiceConfig;
    }

    @Override
    protected void handleInternal(HttpServerExchange exchange) throws Exception {

        // validate content type of payload
        String ct = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        ContentType contentType = ContentType.parse(ct);
        if (contentType == null || contentType.getVersion() != 1 || !SESSION_OPEN.getType().equals(contentType.getType())) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            EjbHttpClientMessages.MESSAGES.debugf("Bad content type %s", ct);
            return;
        }

        // parse request path
        String relativePath = exchange.getRelativePath();
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        String[] parts = relativePath.split("/");
        if (parts.length != 4) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            return;
        }
        final String app = handleDash(parts[0]);
        final String module = handleDash(parts[1]);
        final String distinct = handleDash(parts[2]);
        final String bean = parts[3];

        // process Cookies and Headers
        Cookie cookie = exchange.getRequestCookies().get(JSESSIONID_COOKIE_NAME);
        String sessionAffinity = null;
        if (cookie != null) {
            sessionAffinity = cookie.getValue();
        }

        final EJBIdentifier ejbIdentifier = new EJBIdentifier(app, module, bean, distinct);

        // process request
        exchange.dispatch(executorService, () -> {
            final ReceivedTransaction txConfig;
            try {
                final HttpMarshallerFactory httpUnmarshallerFactory = httpServiceConfig.getHttpUnmarshallerFactory(exchange);
                final Unmarshaller unmarshaller = httpUnmarshallerFactory.createUnmarshaller(HttpProtocolV1ObjectTable.INSTANCE);

                try (InputStream inputStream = exchange.getInputStream()) {
                    unmarshaller.start(new InputStreamByteInput(inputStream));
                    txConfig = readTransaction(unmarshaller);
                    unmarshaller.finish();
                }
            } catch (Exception e) {
                HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.INTERNAL_SERVER_ERROR, e);
                return;
            }
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

            association.receiveSessionOpenRequest(new SessionOpenRequest() {
                @Override
                public boolean hasTransaction() {
                    return txConfig != null;
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
                    HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.INTERNAL_SERVER_ERROR, exception);
                }

                @Override
                public void writeNoSuchEJB() {
                    HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.NOT_FOUND, new NoSuchEJBException());
                }

                @Override
                public void writeWrongViewType() {
                    HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.NOT_FOUND, EjbHttpClientMessages.MESSAGES.wrongViewType());
                }

                @Override
                public void writeCancelResponse() {
                    throw new RuntimeException("nyi");
                }

                @Override
                public void writeNotStateful() {
                    HttpServerHelper.sendException(exchange, httpServiceConfig, StatusCodes.INTERNAL_SERVER_ERROR, EjbHttpClientMessages.MESSAGES.notStateful());
                }

                @Override
                public void convertToStateful(@NotNull SessionID sessionId) throws IllegalArgumentException, IllegalStateException {

                    Cookie sessionCookie = exchange.getRequestCookies().get(JSESSIONID_COOKIE_NAME);
                    if (sessionCookie == null) {
                        String rootPath = exchange.getResolvedPath();
                        int ejbIndex = rootPath.lastIndexOf("/ejb");
                        if (ejbIndex > 0) {
                            rootPath = rootPath.substring(0, ejbIndex);
                        }

                        exchange.getResponseCookies().put(JSESSIONID_COOKIE_NAME, new CookieImpl(JSESSIONID_COOKIE_NAME, sessionIdGenerator.createSessionId()).setPath(rootPath));
                    }

                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, EjbConstants.EJB_RESPONSE_NEW_SESSION.toString());
                    exchange.getResponseHeaders().put(EjbConstants.EJB_SESSION_ID, Base64.getUrlEncoder().encodeToString(sessionId.getEncodedForm()));

                    exchange.setStatusCode(StatusCodes.NO_CONTENT);
                    exchange.endExchange();
                }

                @Override
                public <C> C getProviderInterface(Class<C> providerInterfaceType) {
                    return null;
                }
            });
        });
    }

    private static String handleDash(String s) {
        if (s.equals("-")) {
            return "";
        }
        return s;
    }
}
