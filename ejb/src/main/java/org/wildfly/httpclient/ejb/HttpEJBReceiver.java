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

import static java.security.AccessController.doPrivileged;
import static org.wildfly.httpclient.ejb.ClientHandlers.cancelInvocationResponseFunction;
import static org.wildfly.httpclient.ejb.ClientHandlers.invokeHttpBodyDecoder;
import static org.wildfly.httpclient.ejb.ClientHandlers.createSessionResponseFunction;
import static org.wildfly.httpclient.ejb.ClientHandlers.emptyHttpBodyDecoder;
import static org.wildfly.httpclient.ejb.ClientHandlers.invokeHttpBodyEncoder;
import static org.wildfly.httpclient.ejb.ClientHandlers.createSessionHttpBodyEncoder;
import static org.wildfly.httpclient.ejb.Constants.HTTPS_PORT;
import static org.wildfly.httpclient.ejb.Constants.HTTPS_SCHEME;
import static org.wildfly.httpclient.ejb.Constants.HTTP_PORT;
import static org.wildfly.httpclient.ejb.TransactionInfo.localTransaction;
import static org.wildfly.httpclient.ejb.TransactionInfo.nullTransaction;
import static org.wildfly.httpclient.ejb.TransactionInfo.remoteTransaction;

import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.util.AttachmentKey;
import org.jboss.ejb.client.AbstractInvocationContext;
import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBLocator;
import org.jboss.ejb.client.EJBReceiver;
import org.jboss.ejb.client.EJBReceiverInvocationContext;
import org.jboss.ejb.client.EJBReceiverSessionCreationContext;
import org.jboss.ejb.client.EJBSessionCreationInvocationContext;
import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.client.StatefulEJBLocator;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.httpclient.common.WildflyHttpContext;
import org.wildfly.httpclient.transaction.XidProvider;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;
import org.wildfly.transaction.client.AbstractTransaction;
import org.wildfly.transaction.client.ContextTransactionManager;
import org.wildfly.transaction.client.LocalTransaction;
import org.wildfly.transaction.client.RemoteTransaction;
import org.wildfly.transaction.client.RemoteTransactionContext;
import org.wildfly.transaction.client.XAOutflowHandle;

import jakarta.ejb.Asynchronous;
import javax.net.ssl.SSLContext;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.AccessController;
import java.security.GeneralSecurityException;
import java.security.PrivilegedAction;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EJB receiver for invocations over HTTP.
 *
 * @author Stuart Douglas
 */
class HttpEJBReceiver extends EJBReceiver {

    private static final AuthenticationContextConfigurationClient AUTH_CONTEXT_CLIENT;

    static {
        AUTH_CONTEXT_CLIENT = AccessController.doPrivileged((PrivilegedAction<AuthenticationContextConfigurationClient>) () -> new AuthenticationContextConfigurationClient());
    }

    private final AttachmentKey<EjbContextData> EJB_CONTEXT_DATA = AttachmentKey.create(EjbContextData.class);
    private final org.jboss.ejb.client.AttachmentKey<String> INVOCATION_ID = new org.jboss.ejb.client.AttachmentKey<>();
    private final RemoteTransactionContext transactionContext;
    private final org.jboss.ejb.client.AttachmentKey<ConcurrentMap<URI, String>> TXN_STRICT_STICKINESS_MAP = new org.jboss.ejb.client.AttachmentKey<>();
    private static final AtomicLong invocationIdGenerator = new AtomicLong();

    HttpEJBReceiver() {
        if(System.getSecurityManager() == null) {
            transactionContext = RemoteTransactionContext.getInstance();
        } else {
            transactionContext = AccessController.doPrivileged(new PrivilegedAction<RemoteTransactionContext>() {
                @Override
                public RemoteTransactionContext run() {
                    return RemoteTransactionContext.getInstance();
                }
            });
        }
    }

    @Override
    protected void processInvocation(EJBReceiverInvocationContext receiverContext) throws Exception {

        EJBClientInvocationContext clientInvocationContext = receiverContext.getClientInvocationContext();
        EJBLocator<?> locator = clientInvocationContext.getLocator();

        URI uri = clientInvocationContext.getDestination();
        HttpTargetContext targetContext = resolveTargetContext(clientInvocationContext, uri);
        if (targetContext == null) {
            throw EjbHttpClientMessages.MESSAGES.couldNotResolveTargetForLocator(locator);
        }
        if (targetContext.getAttachment(EJB_CONTEXT_DATA) == null) {
            synchronized (this) {
                if (targetContext.getAttachment(EJB_CONTEXT_DATA) == null) {
                    targetContext.putAttachment(EJB_CONTEXT_DATA, new EjbContextData());
                }
            }
        }

        EjbContextData ejbData = targetContext.getAttachment(EJB_CONTEXT_DATA);
        boolean compressResponse = receiverContext.getClientInvocationContext().isCompressResponse();
        boolean compressRequest = receiverContext.getClientInvocationContext().isCompressRequest();
        RequestBuilder builder = new RequestBuilder(targetContext, RequestType.INVOKE)
                .setCompressRequest(compressRequest)
                .setCompressResponse(compressResponse)
                .setLocator(locator)
                .setMethod(clientInvocationContext.getInvokedMethod())
                .setView(clientInvocationContext.getViewClass().getName());
        if (locator instanceof StatefulEJBLocator) {
            builder.setBeanId(Base64.getUrlEncoder().encodeToString(locator.asStateful().getSessionId().getEncodedForm()));
        }

        if (clientInvocationContext.getInvokedMethod().getReturnType() == Future.class) {
            receiverContext.proceedAsynchronously();
            // cancellation is only supported if we have affinity (InvocationIdentifier = invocationID + SessionAffinity)
            // TODO: check this logic, why only if affinity?
//            if (targetContext.getSessionId() != null) {
                long invocationId = invocationIdGenerator.incrementAndGet();
                String invocationIdString = Long.toString(invocationId);
                builder.setInvocationId(invocationIdString);
                clientInvocationContext.putAttachment(INVOCATION_ID, invocationIdString);
//            }
        } else if (clientInvocationContext.getInvokedMethod().getReturnType() == void.class) {
            if (clientInvocationContext.getInvokedMethod().isAnnotationPresent(Asynchronous.class)) {
                receiverContext.proceedAsynchronously();
            } else if (ejbData.asyncMethods.contains(clientInvocationContext.getInvokedMethod())) {
                receiverContext.proceedAsynchronously();
            }
        }
        ClientRequest request = builder.createRequest();
        final AuthenticationContext context = receiverContext.getAuthenticationContext();
        final AuthenticationContextConfigurationClient client = CLIENT;
        final int defaultPort = uri.getScheme().equals(HTTPS_SCHEME) ? HTTPS_PORT : HTTP_PORT;
        final AuthenticationConfiguration authenticationConfiguration = client.getAuthenticationConfiguration(uri, context, defaultPort, "jndi", "jboss");
        final SSLContext sslContext = client.getSSLContext(uri, context, "jndi", "jboss");
        Marshaller marshaller = createMarshaller(targetContext.getUri(), targetContext.getHttpMarshallerFactory());
        TransactionInfo transactionInfo = getTransactionInfo(clientInvocationContext.getTransaction(), targetContext.getUri());
        Object[] parameters = clientInvocationContext.getParameters();
        Map<String, Object> contextData = clientInvocationContext.getContextData();
        final Unmarshaller unmarshaller = createUnmarshaller(targetContext.getUri(), targetContext.getHttpMarshallerFactory());
        targetContext.sendRequest(request, sslContext, authenticationConfiguration, invokeHttpBodyEncoder(marshaller, transactionInfo, parameters, contextData),
                new InvocationStickinessHandler(receiverContext),
                invokeHttpBodyDecoder(unmarshaller, receiverContext, clientInvocationContext),
                (e) -> receiverContext.requestFailed(e instanceof Exception ? (Exception) e : new RuntimeException(e)), Constants.EJB_RESPONSE, null);
    }

    private static final AuthenticationContextConfigurationClient CLIENT = doPrivileged(AuthenticationContextConfigurationClient.ACTION);

    protected SessionID createSession(final EJBReceiverSessionCreationContext receiverContext) throws Exception {
        EJBSessionCreationInvocationContext sessionCreationInvocationContext = receiverContext.getClientInvocationContext();
        final EJBLocator<?> locator = receiverContext.getClientInvocationContext().getLocator();
        URI uri = sessionCreationInvocationContext.getDestination();

        final AuthenticationContext context = receiverContext.getAuthenticationContext();
        final AuthenticationContextConfigurationClient client = CLIENT;
        final int defaultPort = uri.getScheme().equals(HTTPS_SCHEME) ? HTTPS_PORT : HTTP_PORT;
        final AuthenticationConfiguration authenticationConfiguration = client.getAuthenticationConfiguration(uri, context, defaultPort, "jndi", "jboss");
        final SSLContext sslContext = client.getSSLContext(uri, context, "jndi", "jboss");

        HttpTargetContext targetContext = resolveTargetContext(sessionCreationInvocationContext, uri);
        if (targetContext == null) {
            throw EjbHttpClientMessages.MESSAGES.couldNotResolveTargetForLocator(locator);
        }
        if (targetContext.getAttachment(EJB_CONTEXT_DATA) == null) {
            synchronized (this) {
                if (targetContext.getAttachment(EJB_CONTEXT_DATA) == null) {
                    targetContext.putAttachment(EJB_CONTEXT_DATA, new EjbContextData());
                }
            }
        }

        CompletableFuture<SessionID> result = new CompletableFuture<>();

        URI backendURI = targetContext.acquireBackendServer();
        EjbHttpClientMessages.MESSAGES.infof("HttpEJBReceiver: Getting backend server URI: %s", backendURI);

        RequestBuilder builder = new RequestBuilder(targetContext, RequestType.CREATE_SESSION).setLocator(locator).setView(locator.getViewType().getName());
        ClientRequest request = builder.createRequest();
        TransactionInfo transactionInfo = getTransactionInfo(ContextTransactionManager.getInstance().getTransaction(), targetContext.getUri());
        Marshaller marshaller = createMarshaller(targetContext.getUri(), targetContext.getHttpMarshallerFactory());
        targetContext.sendRequest(request, sslContext, authenticationConfiguration,
                createSessionHttpBodyEncoder(marshaller, transactionInfo),
                new SessionCreationStickinessHandler(receiverContext),
                emptyHttpBodyDecoder(result, createSessionResponseFunction()),
                result::completeExceptionally, Constants.EJB_RESPONSE_NEW_SESSION, null);

        return result.get();
    }

    @Override
    protected boolean cancelInvocation(EJBReceiverInvocationContext receiverContext, boolean cancelIfRunning) {

        EJBClientInvocationContext clientInvocationContext = receiverContext.getClientInvocationContext();
        EJBLocator<?> locator = clientInvocationContext.getLocator();

        URI uri = clientInvocationContext.getDestination();
        final AuthenticationContext context = receiverContext.getAuthenticationContext();
        final AuthenticationContextConfigurationClient client = CLIENT;
        final int defaultPort = uri.getScheme().equals(HTTPS_SCHEME) ? HTTPS_PORT : HTTP_PORT;
        final AuthenticationConfiguration authenticationConfiguration = client.getAuthenticationConfiguration(uri, context, defaultPort, "jndi", "jboss");
        final SSLContext sslContext;
        try {
            sslContext = client.getSSLContext(uri, context, "jndi", "jboss");
        } catch (GeneralSecurityException e) {
            // ¯\_(ツ)_/¯
            return false;
        }

        HttpTargetContext targetContext;
        try {
            targetContext = resolveTargetContext(clientInvocationContext, uri);
            if (targetContext == null) {
                throw EjbHttpClientMessages.MESSAGES.couldNotResolveTargetForLocator(locator);
            }
        } catch (Exception e) {
            throw EjbHttpClientMessages.MESSAGES.couldNotResolveTargetForLocator(locator);
        }

        if (targetContext.getAttachment(EJB_CONTEXT_DATA) == null) {
            synchronized (this) {
                if (targetContext.getAttachment(EJB_CONTEXT_DATA) == null) {
                    targetContext.putAttachment(EJB_CONTEXT_DATA, new EjbContextData());
                }
            }
        }
        RequestBuilder builder = new RequestBuilder(targetContext, RequestType.CANCEL)
                .setLocator(locator)
                .setCancelIfRunning(cancelIfRunning)
                .setInvocationId(receiverContext.getClientInvocationContext().getAttachment(INVOCATION_ID));
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        ClientRequest request = builder.createRequest();
        targetContext.sendRequest(request, sslContext, authenticationConfiguration, null,
                null,
                emptyHttpBodyDecoder(result, cancelInvocationResponseFunction()),
                result::completeExceptionally, null, null);
        try {
            return result.get();
        } catch (InterruptedException | ExecutionException e) {
            return false;
        }
    }

    private Marshaller createMarshaller(URI uri, HttpMarshallerFactory httpMarshallerFactory) throws IOException {
        return httpMarshallerFactory.createMarshaller(new HttpProtocolV1ObjectResolver(uri), HttpProtocolV1ObjectTable.INSTANCE);
    }

    private Unmarshaller createUnmarshaller(URI uri, HttpMarshallerFactory httpMarshallerFactory) throws IOException {
        return httpMarshallerFactory.createUnmarshaller(new HttpProtocolV1ObjectResolver(uri), HttpProtocolV1ObjectTable.INSTANCE);
    }

    private TransactionInfo getTransactionInfo(final Transaction transaction, final URI uri) throws RollbackException, SystemException {
        if (transaction == null) {
            return nullTransaction();
        } else if (transaction instanceof RemoteTransaction) {
            final RemoteTransaction remoteTransaction = (RemoteTransaction) transaction;
            remoteTransaction.setLocation(uri);
            final XidProvider xidProvider = remoteTransaction.getProviderInterface(XidProvider.class);
            if (xidProvider == null) throw EjbHttpClientMessages.MESSAGES.cannotEnlistTx();
            return remoteTransaction(xidProvider.getXid());
        } else if (transaction instanceof LocalTransaction) {
            final LocalTransaction localTransaction = (LocalTransaction) transaction;
            final XAOutflowHandle outflowHandle = transactionContext.outflowTransaction(uri, localTransaction);
            return localTransaction(outflowHandle.getXid(), outflowHandle.getRemainingTime());
        } else {
            throw EjbHttpClientMessages.MESSAGES.cannotEnlistTx();
        }
    }

    private static class EjbContextData {
        final Set<Method> asyncMethods = Collections.newSetFromMap(new ConcurrentHashMap<>());
    }

    private class SessionCreationStickinessHandler implements HttpTargetContext.HttpStickinessHandler {
        private final EJBReceiverSessionCreationContext sessionCreationContext;

        public SessionCreationStickinessHandler(EJBReceiverSessionCreationContext sessionCreationContext) {
            this.sessionCreationContext = sessionCreationContext;
        }

        @Override
        public void prepareRequest(ClientRequest request) {
        }

        @Override
        public void processResponse(ClientExchange result) {
        }
    }

    private class InvocationStickinessHandler implements HttpTargetContext.HttpStickinessHandler {
        private final EJBReceiverInvocationContext invocationContext;

        public InvocationStickinessHandler(EJBReceiverInvocationContext invocationContext) {
            this.invocationContext = invocationContext;
        }

        @Override
        public void prepareRequest(ClientRequest request) {
        }

        @Override
        public void processResponse(ClientExchange result) {
        }
    }

    // -------------------------------------------------------

    private boolean inTransaction(AbstractInvocationContext context) {
        return context.getTransaction() != null;
    }

    private boolean inRemoteTransaction(AbstractInvocationContext context) {
        return context.getTransaction() != null && context.getTransaction() instanceof RemoteTransaction;
    }

    private boolean inLocalTransaction(AbstractInvocationContext context) {
        return context.getTransaction() != null && context.getTransaction() instanceof LocalTransaction;
    }

    /*
     * For a given URI, resolves the required HttpTargetContext used as a transport between client and server.
     * In particular, this method takes into account requirements for strict stickiness for invocations which are
     * in transaction scope. This is achieved by:
     * - resolving a URI pointing to a load balancer to one of that load balancer's backend servers
     * - consistently returning the same resolved URI across the lifetime of the transaction
     * In this way, URIs used in transactions, whether they be RemoteTransactions or LocalTransactions, will always
     * target only one fixed backend server.
     */
    private HttpTargetContext resolveTargetContext(final AbstractInvocationContext context, final URI uri) throws Exception {
        HttpTargetContext currentContext = null;

        // get the HttpTargetContext for the discovered URI
        WildflyHttpContext current = WildflyHttpContext.getCurrent();
        currentContext = current.getTargetContext(uri);
        if (currentContext == null) {
            throw EjbHttpClientMessages.MESSAGES.couldNotResolveTargetForLocator(context.getLocator());
        }

        // if we are in a transaction, get a reference to the transaction's URI map and its resolved URI
        if (inTransaction(context)) {
            ConcurrentMap<URI, String> map = getOrCreateTransactionURIMap(context.getTransaction());
            String backendNode = map.get(uri);
            // we need to update the map for this discovered URI with a backend node
            if (backendNode == null) {
                // acquire a randomly chosen backend node from this URI (in form http://<host>:<port>?node=<node>)
                URI backendURI = currentContext.acquireBackendServer();
                backendNode = parseURIQueryString(backendURI.getQuery());
                map.putIfAbsent(uri, backendNode);
            }
        }
        return currentContext;
    }

    /*
     * For a given transaction, returns the mapping of URIs which is used for the purpose of maintaining
     * strict stickiness semantics in transactions. Each URI (representing a load balancer) is mapped to
     * a fixed backend node.
     */
    private ConcurrentMap<URI, String> getOrCreateTransactionURIMap(AbstractTransaction transaction) throws Exception {
        Object resource = transaction.getResource(TXN_STRICT_STICKINESS_MAP);
        ConcurrentMap<URI, String> map = null;
        if (resource == null) {
            map = new ConcurrentHashMap<>();
            resource = transaction.putResourceIfAbsent(TXN_STRICT_STICKINESS_MAP, map);
        }
        return resource == null ? map : ConcurrentMap.class.cast(resource);
    }

    /*
     * Parse the node name out of the string http://<host>:<port>?node=<node>
     */
    private String parseURIQueryString(String queryString) {
        return queryString.substring("node=".length());
    }

    // -------------------------------------------------------
}
