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
import io.undertow.client.ClientResponse;
import io.undertow.server.session.SecureRandomSessionIdGenerator;
import io.undertow.server.session.SessionIdGenerator;
import io.undertow.util.AttachmentKey;
import org.jboss.ejb.client.AbstractInvocationContext;
import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBLocator;
import org.jboss.ejb.client.EJBReceiver;
import org.jboss.ejb.client.EJBReceiverInvocationContext;
import org.jboss.ejb.client.EJBReceiverSessionCreationContext;
import org.jboss.ejb.client.EJBSessionCreationInvocationContext;
import org.jboss.ejb.client.NodeAffinity;
import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.client.StatefulEJBLocator;
import org.jboss.ejb.client.URIAffinity;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpStickinessHelper;
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
    protected final ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionID = new ConcurrentHashMap<>();

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

        final EJBClientInvocationContext clientInvocationContext = receiverContext.getClientInvocationContext();
        EJBLocator<?> locator = clientInvocationContext.getLocator();

        final URI uri = clientInvocationContext.getDestination();
        final HttpTargetContext targetContext = resolveTargetContext(clientInvocationContext, uri);
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
                new InvocationStickinessHandler(receiverContext, node2SessionID),
                invokeHttpBodyDecoder(unmarshaller, receiverContext, clientInvocationContext),
                (e) -> receiverContext.requestFailed(e instanceof Exception ? (Exception) e : new RuntimeException(e)), Constants.EJB_RESPONSE, null);
    }

    private static final AuthenticationContextConfigurationClient CLIENT = doPrivileged(AuthenticationContextConfigurationClient.ACTION);

    protected SessionID createSession(final EJBReceiverSessionCreationContext receiverContext) throws Exception {
        final EJBSessionCreationInvocationContext sessionCreationInvocationContext = receiverContext.getClientInvocationContext();
        final EJBLocator<?> locator = receiverContext.getClientInvocationContext().getLocator();
        final URI uri = sessionCreationInvocationContext.getDestination();

        final AuthenticationContext context = receiverContext.getAuthenticationContext();
        final AuthenticationContextConfigurationClient client = CLIENT;
        final int defaultPort = uri.getScheme().equals(HTTPS_SCHEME) ? HTTPS_PORT : HTTP_PORT;
        final AuthenticationConfiguration authenticationConfiguration = client.getAuthenticationConfiguration(uri, context, defaultPort, "jndi", "jboss");
        final SSLContext sslContext = client.getSSLContext(uri, context, "jndi", "jboss");

        final HttpTargetContext targetContext = resolveTargetContext(sessionCreationInvocationContext, uri);
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

        RequestBuilder builder = new RequestBuilder(targetContext, RequestType.CREATE_SESSION).setLocator(locator).setView(locator.getViewType().getName());
        ClientRequest request = builder.createRequest();
        TransactionInfo transactionInfo = getTransactionInfo(ContextTransactionManager.getInstance().getTransaction(), targetContext.getUri());
        Marshaller marshaller = createMarshaller(targetContext.getUri(), targetContext.getHttpMarshallerFactory());
        targetContext.sendRequest(request, sslContext, authenticationConfiguration,
                createSessionHttpBodyEncoder(marshaller, transactionInfo),
                new SessionCreationStickinessHandler(receiverContext, node2SessionID),
                emptyHttpBodyDecoder(result, createSessionResponseFunction()),
                result::completeExceptionally, Constants.EJB_RESPONSE_NEW_SESSION, null);

        return result.get();
    }

    @Override
    protected boolean cancelInvocation(EJBReceiverInvocationContext receiverContext, boolean cancelIfRunning) {

        final EJBClientInvocationContext clientInvocationContext = receiverContext.getClientInvocationContext();
        final EJBLocator<?> locator = clientInvocationContext.getLocator();

        final URI uri = clientInvocationContext.getDestination();
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

        final HttpTargetContext targetContext;
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

    /*
     * This class manages the relationship between the proxy's strong and weak affinity and
     * the stickiness requirements of session beans resulting from session creation.
     *
     * Remember that for session creation operations:
     * - requests start off with SLSB locators identifying a bean for which the session is to be created
     * - responses are used to convert the SLSB locator into a SFSB locator with a SessionID
     *
     */
    private class SessionCreationStickinessHandler implements HttpTargetContext.HttpStickinessHandler {
        private final EJBReceiverSessionCreationContext receiverSessionCreationContext;
        private final ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionId;

        private final SessionIdGenerator sessionIdGenerator = new SecureRandomSessionIdGenerator();
        private final String clientSessionID = sessionIdGenerator.createSessionId();

        public SessionCreationStickinessHandler(EJBReceiverSessionCreationContext receiverSessionCreationContext, ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionId) {
            this.receiverSessionCreationContext = receiverSessionCreationContext;
            this.node2SessionId = node2SessionId;
        }

        @Override
        public void prepareRequest(ClientRequest request) throws Exception {
            EjbHttpClientMessages.MESSAGES.infof("Calling SessionCreationStickinessHandler.prepareRequest for request %s", request);
            EJBSessionCreationInvocationContext context = receiverSessionCreationContext.getClientInvocationContext();

            if (inTransaction(context)) {
                ConcurrentMap<URI, String> map = getOrCreateTransactionURIMap(context.getTransaction());
                String route = map.get(context.getDestination());
                if (route == null) {
                    throw EjbHttpClientMessages.MESSAGES.couldNotResolveRouteForTransactionScopedInvocation(context.getTransaction().toString());
                }

                HttpStickinessHelper.addEncodedSessionID(request, clientSessionID, route);
                HttpStickinessHelper.addStrictStickinessHost(request, route);
            }
        }

        @Override
        public void processResponse(ClientExchange result) throws Exception {
            EjbHttpClientMessages.MESSAGES.infof("Calling SessionCreationStickinessHandler.processResponse for response %s", result.getResponse());

            EJBSessionCreationInvocationContext clientInvocationContext = receiverSessionCreationContext.getClientInvocationContext();
            EJBLocator locator = clientInvocationContext.getLocator();
            URI uri = clientInvocationContext.getDestination();

            EjbHttpClientMessages.MESSAGES.infof("Calling SessionCreationStickinessHandler.processResponse for locator %s", locator);

            ClientResponse response = result.getResponse();

            if (!HttpStickinessHelper.hasEncodedSessionID(response)) {
                throw new Exception("SessionCreationStickinessHandler.processResponse(), SFSB session creation response is missing JSESSIONID Cookie");
            }

            String route = HttpStickinessHelper.updateNode2SessionIDMap(node2SessionId, uri, response);
            EjbHttpClientMessages.MESSAGES.infof("SessionCreationStickinessHandler.processResponse(), route = %s", route);

            boolean isSticky = false;

            if (HttpStickinessHelper.hasStrictStickinessResult(response)) {
                if (!HttpStickinessHelper.getStrictStickinessResult(response)) {
                    String host = HttpStickinessHelper.getStrictStickinessHost(response);
                    assert !host.equals(route);
                    throw new Exception("SessionCreationStickinessHandler.processResponse(): route and host do not match!: route = " + route + ",host = " + host);
                }
                isSticky = true;
            }

            Affinity weakAffinity = null;
            if (!inTransaction(clientInvocationContext)) {
                if (!isSticky) {
                    weakAffinity = new NodeAffinity(route);
                } else {
                    weakAffinity = new URIAffinity(HttpStickinessHelper.createURIAffinityValue(route));
                }
            } else {
                if (!isSticky) {
                    throw new Exception("Session creation response has no strict stickiness header");
                }
                weakAffinity = new URIAffinity(HttpStickinessHelper.createURIAffinityValue(route));
            }

            if (inTransaction(clientInvocationContext)) {
                EjbHttpClientMessages.MESSAGES.infof("SessionCreationStickinessHandler.processResponse() [txn] updating weak affinity to %s", weakAffinity);
            } else {
                EjbHttpClientMessages.MESSAGES.infof("SessionCreationStickinessHandler.processResponse() [non-txn] updating weak affinity to %s", weakAffinity);
            }

            clientInvocationContext.setWeakAffinity(weakAffinity);
        }
    }

    /*
     * This class manages the relationship between the proxy's strong and weak affinity and
     * the stickiness requirements of session beans resulting from invocation.
     */
    private class InvocationStickinessHandler implements HttpTargetContext.HttpStickinessHandler {
        private final EJBReceiverInvocationContext receiverInvocationContext;
        private final ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionId;

        public InvocationStickinessHandler(EJBReceiverInvocationContext receiverInvocationContext, ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionId) {
            this.receiverInvocationContext = receiverInvocationContext;
            this.node2SessionId = node2SessionId;
        }

        @Override
        public void prepareRequest(ClientRequest request) throws Exception {
            EjbHttpClientMessages.MESSAGES.infof("Calling InvocationStickinessHandler.prepareRequest for request %s", request);

            EJBClientInvocationContext context = receiverInvocationContext.getClientInvocationContext();
            EJBLocator locator = context.getLocator();
            URI uri = context.getDestination();
            Affinity weakAffinity = context.getWeakAffinity();

            EjbHttpClientMessages.MESSAGES.infof("Calling InvocationStickinessHandler().prepareRequest(), node2sessionID map: %s", node2SessionId);

            if (inTransaction(context)) {
                assert weakAffinity instanceof URIAffinity;
                String route = ((URIAffinity) weakAffinity).getUri().getHost();
                assert route != null;

                String nodeSessionID = HttpStickinessHelper.getSessionIDForNode(node2SessionId, uri, route);
                HttpStickinessHelper.addEncodedSessionID(request, nodeSessionID, route);
                HttpStickinessHelper.addStrictStickinessHost(request, route);

            } else if (locator instanceof StatefulEJBLocator) {
                if (weakAffinity instanceof NodeAffinity) {
                    String route = ((NodeAffinity) weakAffinity).getNodeName();
                    assert route != null;

                    EjbHttpClientMessages.MESSAGES.infof("Calling InvocationStickinessHandler.prepareRequest(), node2sessionID map: %s, uri = %s, route = %s", node2SessionId, uri, route);

                    String nodeSessionID = HttpStickinessHelper.getSessionIDForNode(node2SessionId, uri, route);
                    HttpStickinessHelper.addEncodedSessionID(request, nodeSessionID, route);

                } else if (weakAffinity instanceof URIAffinity) {
                    String route = ((URIAffinity) weakAffinity).getUri().getHost();
                    assert route != null;
                    String nodeSessionID = HttpStickinessHelper.getSessionIDForNode(node2SessionId, uri, route);
                    HttpStickinessHelper.addEncodedSessionID(request, nodeSessionID, route);
                    HttpStickinessHelper.addStrictStickinessHost(request, route);
                } else {
                    throw new Exception("InvocationStickinessHandler.prepareRequest(): bad weak affinity value!: weak affinity = " + weakAffinity.toString());
                }
            }
        }

        @Override
        public void processResponse(ClientExchange result) throws Exception {
            EjbHttpClientMessages.MESSAGES.infof("InvocationStickinessHandler.processResponse for response %s", result.getResponse());

            EJBClientInvocationContext context = receiverInvocationContext.getClientInvocationContext();
            EJBLocator locator = context.getLocator();
            URI uri = context.getDestination();
            Affinity weakAffinity = context.getWeakAffinity();

            ClientResponse response = result.getResponse();

            boolean isSticky = HttpStickinessHelper.getStrictStickinessResult(response);

            if (inTransaction(context)) {
                if (!isSticky) {
                    throw new Exception("Stickiness not respected for transaction-scoped invocation");
                }
            } else if (locator instanceof StatefulEJBLocator) {
                boolean hasEncodedSessionID = HttpStickinessHelper.hasEncodedSessionID(response);
                if (!hasEncodedSessionID) {
                    throw new Exception("SFSB response is missing its route");
                }
                String encodedSessionID = HttpStickinessHelper.getEncodedSessionID(response);
                String sessionID = HttpStickinessHelper.extractSessionIDFromEncodedSessionID(encodedSessionID);
                String route = HttpStickinessHelper.extractRouteFromEncodedSessionID(encodedSessionID);
                EjbHttpClientMessages.MESSAGES.infof("InvocationStickinessHandler.processResponse(), sessionID, sessionID = %s, route = %s", sessionID, route);

                if (!isSticky) {
                    context.setWeakAffinity(new NodeAffinity(route));
                }
            }
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
     * In addition to obtaining a valid HttpTargetContext, if the operation is in transaction scope,
     * this method will ensure that a randomly chosen backend server (if the target is a load balancer) will be
     * selected for this transaction and all operations in the scope of this transaction will be directed to that
     * backend node.
     */
    private HttpTargetContext resolveTargetContext(final AbstractInvocationContext context, final URI uri) throws Exception {
        HttpTargetContext currentContext = null;

        // get the HttpTargetContext for the discovered URI
        final WildflyHttpContext current = WildflyHttpContext.getCurrent();
        currentContext = current.getTargetContext(uri);
        if (currentContext == null) {
            throw EjbHttpClientMessages.MESSAGES.couldNotResolveTargetForLocator(context.getLocator());
        }

        // if we are in a transaction, get a reference to the transaction's URI map and make sure that a backend
        // node has been assigned for this transaction
        if (inTransaction(context)) {
            ConcurrentMap<URI, String> map = getOrCreateTransactionURIMap(context.getTransaction());
            String backendNode = map.get(uri);
            // we need to update the map for this discovered URI with a backend node
            if (backendNode == null) {
                // acquire a randomly chosen backend node from this URI (in form http://<host>:<port>?node=<node>)
                URI backendURI = currentContext.acquireBackendServer();
                // debugging
                EjbHttpClientMessages.MESSAGES.infof("HttpEJBReceiver: Got backend server URI: %s", backendURI);

                backendNode = parseURIQueryString(backendURI.getQuery());
                map.putIfAbsent(uri, backendNode);
            }
            // debugging
            EjbHttpClientMessages.MESSAGES.infof("HttpEJBReceiver: Using backend server: %s", backendNode);
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
