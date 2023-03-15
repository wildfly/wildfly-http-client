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

import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.session.SecureRandomSessionIdGenerator;
import io.undertow.server.session.SessionIdGenerator;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;
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
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
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
import org.xnio.IoUtils;

import jakarta.ejb.Asynchronous;
import javax.net.ssl.SSLContext;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import javax.transaction.xa.Xid;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.AccessController;
import java.security.GeneralSecurityException;
import java.security.PrivilegedAction;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static java.security.AccessController.doPrivileged;
import static org.wildfly.httpclient.ejb.EjbConstants.HTTPS_PORT;
import static org.wildfly.httpclient.ejb.EjbConstants.HTTPS_SCHEME;
import static org.wildfly.httpclient.ejb.EjbConstants.HTTP_PORT;

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

    private final HttpString STRICT_STICKINESS_HOST = new HttpString("StrictStickinessHost");
    private final HttpString STRICT_STICKINESS_RESULT = new HttpString("StrictStickinessResult");
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
        HttpEJBInvocationBuilder builder = new HttpEJBInvocationBuilder()
                .setInvocationType(HttpEJBInvocationBuilder.InvocationType.METHOD_INVOCATION)
                .setMethod(clientInvocationContext.getInvokedMethod())
                .setAppName(locator.getAppName())
                .setModuleName(locator.getModuleName())
                .setDistinctName(locator.getDistinctName())
                .setView(clientInvocationContext.getViewClass().getName())
                .setBeanName(locator.getBeanName());
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
        boolean compressResponse = receiverContext.getClientInvocationContext().isCompressResponse();
        builder.setVersion(targetContext.getProtocolVersion());
        ClientRequest request = builder.createRequest(targetContext.getUri().getPath());
        if (compressResponse) {
            request.getRequestHeaders().put(Headers.ACCEPT_ENCODING, Headers.GZIP.toString());
        }
        request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, Headers.CHUNKED.toString());
        final boolean compressRequest = receiverContext.getClientInvocationContext().isCompressRequest();
        if (compressRequest) {
            request.getRequestHeaders().put(Headers.CONTENT_ENCODING, Headers.GZIP.toString());
        }
        final AuthenticationContext context = receiverContext.getAuthenticationContext();
        final AuthenticationContextConfigurationClient client = CLIENT;
        final int defaultPort = uri.getScheme().equals(HTTPS_SCHEME) ? HTTPS_PORT : HTTP_PORT;
        final AuthenticationConfiguration authenticationConfiguration = client.getAuthenticationConfiguration(uri, context, defaultPort, "jndi", "jboss");
        final SSLContext sslContext = client.getSSLContext(uri, context, "jndi", "jboss");
        targetContext.sendRequest(request, sslContext, authenticationConfiguration,
                (output -> {
                    OutputStream data = output;
                    if (compressRequest) {
                        data = new GZIPOutputStream(data);
                    }
                    try {
                        marshalEJBRequest(Marshalling.createByteOutput(data), clientInvocationContext, targetContext, request);
                    } finally {
                        IoUtils.safeClose(data);
                    }
                }),
                new InvocationStickinessHandler(receiverContext, node2SessionID),
                ((input, response, closeable) -> {
                        if (response.getResponseCode() == StatusCodes.ACCEPTED && clientInvocationContext.getInvokedMethod().getReturnType() == void.class) {
                            ejbData.asyncMethods.add(clientInvocationContext.getInvokedMethod());
                        }
                        receiverContext.resultReady(new EJBReceiverInvocationContext.ResultProducer() {
                            @Override
                            public Object getResult() throws Exception {

                                Exception exception = null;
                                Object returned = null;
                                try {

                                    final Unmarshaller unmarshaller = createUnmarshaller(targetContext.getUri(), targetContext.getHttpMarshallerFactory(request));

                                    unmarshaller.start(new InputStreamByteInput(input));
                                    returned = unmarshaller.readObject();
                                    // read the attachments
                                    final Map<String, Object> attachments = readAttachments(unmarshaller);
                                    // finish unmarshalling
                                    if (unmarshaller.read() != -1) {
                                        exception = EjbHttpClientMessages.MESSAGES.unexpectedDataInResponse();
                                    }
                                    unmarshaller.finish();

                                    // WEJBHTTP-83 - remove jboss.returned.keys values from the local context data, so that after unmarshalling the response, we have the correct ContextData
                                    Set<String> returnedContextDataKeys = (Set<String>) clientInvocationContext.getContextData().get(EJBClientInvocationContext.RETURNED_CONTEXT_DATA_KEY);
                                    if(returnedContextDataKeys != null) {
                                        clientInvocationContext.getContextData().keySet().removeAll(returnedContextDataKeys);
                                    }

                                    // If there are any attachments, add them to the client invocation's context data
                                    if (attachments != null) {
                                        for (Map.Entry<String, Object> entry : attachments.entrySet()) {
                                            if (entry.getValue() != null) {
                                                clientInvocationContext.getContextData().put(entry.getKey(), entry.getValue());
                                            }
                                        }
                                    }

                                    if (response.getResponseCode() >= 400) {
                                        throw (Exception) returned;
                                    }
                                } catch (Exception e) {
                                    exception = e;
                                } finally {
                                    IoUtils.safeClose(closeable);
                                }
                                if (exception != null) {
                                    throw exception;
                                } else {
                                    return returned;
                                }
                            }

                            @Override
                            public void discardResult() {
                                IoUtils.safeClose(closeable);
                            }
                        });
                }),
                (e) -> receiverContext.requestFailed(e instanceof Exception ? (Exception) e : new RuntimeException(e)),
                EjbConstants.EJB_RESPONSE, null);
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

        HttpEJBInvocationBuilder builder = new HttpEJBInvocationBuilder()
                .setInvocationType(HttpEJBInvocationBuilder.InvocationType.STATEFUL_CREATE)
                .setAppName(locator.getAppName())
                .setModuleName(locator.getModuleName())
                .setDistinctName(locator.getDistinctName())
                .setView(locator.getViewType().getName())
                .setBeanName(locator.getBeanName());
        builder.setVersion(targetContext.getProtocolVersion());
        ClientRequest request = builder.createRequest(targetContext.getUri().getPath());
        targetContext.sendRequest(request, sslContext, authenticationConfiguration,
                output -> {
                    Marshaller marshaller = createMarshaller(targetContext.getUri(), targetContext.getHttpMarshallerFactory(request));
                    marshaller.start(Marshalling.createByteOutput(output));
                    writeTransaction(ContextTransactionManager.getInstance().getTransaction(), marshaller, targetContext.getUri());
                    marshaller.finish();
                },
                new SessionCreationStickinessHandler(receiverContext, node2SessionID),
                ((unmarshaller, response, c) -> {
                    try {
                        String sessionId = response.getResponseHeaders().getFirst(EjbConstants.EJB_SESSION_ID);
                        if (sessionId == null) {
                            result.completeExceptionally(EjbHttpClientMessages.MESSAGES.noSessionIdInResponse());
                        } else {
                            SessionID sessionID = SessionID.createSessionID(Base64.getUrlDecoder().decode(sessionId));
                            result.complete(sessionID);
                        }
                    } finally {
                        IoUtils.safeClose(c);
                    }
                }),
                result::completeExceptionally,
                EjbConstants.EJB_RESPONSE_NEW_SESSION, null);

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
        } catch(Exception e) {
            throw EjbHttpClientMessages.MESSAGES.couldNotResolveTargetForLocator(locator);
        }

        if (targetContext.getAttachment(EJB_CONTEXT_DATA) == null) {
            synchronized (this) {
                if (targetContext.getAttachment(EJB_CONTEXT_DATA) == null) {
                    targetContext.putAttachment(EJB_CONTEXT_DATA, new EjbContextData());
                }
            }
        }

        HttpEJBInvocationBuilder builder = new HttpEJBInvocationBuilder()
                .setInvocationType(HttpEJBInvocationBuilder.InvocationType.CANCEL)
                .setAppName(locator.getAppName())
                .setModuleName(locator.getModuleName())
                .setDistinctName(locator.getDistinctName())
                .setCancelIfRunning(cancelIfRunning)
                .setInvocationId(receiverContext.getClientInvocationContext().getAttachment(INVOCATION_ID))
                .setBeanName(locator.getBeanName());
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        targetContext.sendRequest(builder.createRequest(targetContext.getUri().getPath()), sslContext, authenticationConfiguration,
                null,
                null,
                (stream, response, closeable) -> {
                    try {
                        result.complete(true);
                        IoUtils.safeClose(stream);
                    } finally {
                        IoUtils.safeClose(closeable);
                    }
                },
                throwable -> result.complete(false),
                null, null);
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

    private void marshalEJBRequest(ByteOutput byteOutput, EJBClientInvocationContext clientInvocationContext, HttpTargetContext targetContext, ClientRequest clientRequest) throws IOException, RollbackException, SystemException {
        Marshaller marshaller = createMarshaller(targetContext.getUri(), targetContext.getHttpMarshallerFactory(clientRequest));
        marshaller.start(byteOutput);
        writeTransaction(clientInvocationContext.getTransaction(), marshaller, targetContext.getUri());


        Object[] methodParams = clientInvocationContext.getParameters();
        if (methodParams != null && methodParams.length > 0) {
            for (final Object methodParam : methodParams) {
                marshaller.writeObject(methodParam);
            }
        }
        // write out the context data
        final Map<String, Object> contextData = clientInvocationContext.getContextData();
        // no private or public data to write out
        if (contextData == null) {
            marshaller.writeByte(0);
        } else {
            final int totalAttachments = contextData.size();
            PackedInteger.writePackedInteger(marshaller, totalAttachments);
            // write out public (application specific) context data
            for (Map.Entry<String, Object> invocationContextData : contextData.entrySet()) {
                marshaller.writeObject(invocationContextData.getKey());
                marshaller.writeObject(invocationContextData.getValue());
            }
        }
        // finish marshalling
        marshaller.finish();
    }


    private XAOutflowHandle writeTransaction(final Transaction transaction, final DataOutput dataOutput, URI uri) throws IOException, RollbackException, SystemException {

        if (transaction == null) {
            dataOutput.writeByte(0);
            return null;
        } else if (transaction instanceof RemoteTransaction) {
            final RemoteTransaction remoteTransaction = (RemoteTransaction) transaction;
            remoteTransaction.setLocation(uri);
            final XidProvider ir = remoteTransaction.getProviderInterface(XidProvider.class);
            if (ir == null) throw EjbHttpClientMessages.MESSAGES.cannotEnlistTx();
            Xid xid = ir.getXid();
            dataOutput.writeByte(1);
            dataOutput.writeInt(xid.getFormatId());
            final byte[] gtid = xid.getGlobalTransactionId();
            dataOutput.writeInt(gtid.length);
            dataOutput.write(gtid);
            final byte[] bq = xid.getBranchQualifier();
            dataOutput.writeInt(bq.length);
            dataOutput.write(bq);
            return null;
        } else if (transaction instanceof LocalTransaction) {
            final LocalTransaction localTransaction = (LocalTransaction) transaction;
            final XAOutflowHandle outflowHandle = transactionContext.outflowTransaction(uri, localTransaction);
            final Xid xid = outflowHandle.getXid();
            dataOutput.writeByte(2);
            dataOutput.writeInt(xid.getFormatId());
            final byte[] gtid = xid.getGlobalTransactionId();
            dataOutput.writeInt(gtid.length);
            dataOutput.write(gtid);
            final byte[] bq = xid.getBranchQualifier();
            dataOutput.writeInt(bq.length);
            dataOutput.write(bq);
            dataOutput.writeInt(outflowHandle.getRemainingTime());
            return outflowHandle;
        } else {
            throw EjbHttpClientMessages.MESSAGES.cannotEnlistTx();
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

    private static Map<String, Object> readAttachments(final ObjectInput input) throws IOException, ClassNotFoundException {
        final int numAttachments = PackedInteger.readPackedInteger(input);
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

    private static class StaticResultProducer implements EJBReceiverInvocationContext.ResultProducer {
        private final Object ret;

        public StaticResultProducer(Object ret) {
            this.ret = ret;
        }

        @Override
        public Object getResult() throws Exception {
            return ret;
        }

        @Override
        public void discardResult() {

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
     * - responses are used to convert the SLSB locator ito a SFSB locator with a SessionID
     *
     */
    private class SessionCreationStickinessHandler implements HttpTargetContext.HttpStickinessHandler {
        private final EJBReceiverSessionCreationContext receiverSessionCreationContext;
        private final ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionId;

        // need a fixed sessionID for this client
        private final SessionIdGenerator sessionIdGenerator = new SecureRandomSessionIdGenerator();
        private final String clientSessionID =sessionIdGenerator.createSessionId();

        public SessionCreationStickinessHandler(EJBReceiverSessionCreationContext receiverSessionCreationContext, ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionId) {
            this.receiverSessionCreationContext = receiverSessionCreationContext;
            this.node2SessionId = node2SessionId;
        }

        /*
         * In the case of SFSB session creation requests, we want the following conditions to hold:
         * - if request not in transaction scope:
         *   - no JSESSIONID Cookie to permit the load balancer to select backend node
         *   - no additional Headers
         * - if request in transaction scope:
         *   - add JSESSIONID Cookie with fixed backend node for the enclosing transaction
         *   - add STRICT_STICKINESS_HOST header with stickiness node based on transaction map
         */
        @Override
        public void prepareRequest(ClientRequest request) throws Exception {
            EjbHttpClientMessages.MESSAGES.infof("Calling SessionCreationStickinessHandler.prepareRequest for request %s", request);
            EJBSessionCreationInvocationContext context = receiverSessionCreationContext.getClientInvocationContext();

            if (inTransaction(context)) {
                // get the backend node from the transaction's map
                ConcurrentMap<URI, String> map = getOrCreateTransactionURIMap(context.getTransaction());
                String route = map.get(context.getDestination());
                if (route == null) {
                    throw EjbHttpClientMessages.MESSAGES.couldNotResolveRouteForTransactionScopedInvocation(context.getTransaction().toString());
                }

                // add JSESSIONID Cookie to request for routing
                HttpStickinessHelper.addEncodedSessionID(request, clientSessionID, route);

                // indicate strict stickiness
                HttpStickinessHelper.addStrictStickinessHost(request, route);
            }
        }

        /*
         * In the case of SFSB session creation responses, we want the following conditions to hold:
         * - if request not in transaction scope:
         *   - expect JSESSIONID Cookie, extract route
         *   - check for STRICT_AFFINITY_NODE=<node>
         *     - if no STRICT_AFFINITY_NODE header present, update weak affinity of proxy to NodeAffinity(route)
         *     - if STRICT_AFFINITY_NODE header present, assert equals route, update weak affinity of proxy to URIAffinity(node)
         * - if request in transaction scope:
         *   - expect JSESSIONID Cookie, extract route
         *   - check for STRICT_AFFINITY_NODE=<node>
         *     - if STRICT_AFFINITY_NODE header present, assert equals route, update weak affinity of proxy to URIAffinity(node)
         *     - if no STRICT_AFFINITY_NODE header present, throw exception
         */
        @Override
        public void processResponse(ClientExchange result) throws Exception {
            EjbHttpClientMessages.MESSAGES.infof("Calling SessionCreationStickinessHandler.processResponse for response %s", result.getResponse());

            EJBSessionCreationInvocationContext clientInvocationContext = receiverSessionCreationContext.getClientInvocationContext();
            EJBLocator locator = clientInvocationContext.getLocator();
            URI uri = clientInvocationContext.getDestination();

            // locator of request is StatelessLocator, but we should not see Responses with no Cookie coming through here
            // need to modify the test suite to return a route?
            EjbHttpClientMessages.MESSAGES.infof("Calling SessionCreationStickinessHandler.processResponse for locator %s", locator);

            ClientResponse response = result.getResponse();

            // extract route from Cookie and update sessionID map
            if (!HttpStickinessHelper.hasEncodedSessionID(response)) {
                throw new Exception("SessionCreationStickinessHandler.processResponse(), SFSB session creation response is missing JSESSIONID Cookie");
            }

            String route = HttpStickinessHelper.updateNode2SessionIDMap(node2SessionId, uri, response);
            EjbHttpClientMessages.MESSAGES.infof("SessionCreationStickinessHandler.processResponse(), route = %s", route);

            // check for strict stickiness requirement; throw exception if violated
            // NOTE: if state is replicated, there will be no STRICT_STICKINESS_HOST, and we use the route instead
            boolean isSticky = false;

            if (HttpStickinessHelper.hasStrictStickinessResult(response)) {
                if (!HttpStickinessHelper.getStrictStickinessResult(response)) {
                    String host = HttpStickinessHelper.getStrictStickinessHost(response) ;
                    // actual route and stickiness host should not match
                    assert !host.equals(route);
                    throw new Exception("SessionCreationStickinessHandler.processResponse(): route and host do not match!: route = " + route + ",host = " + host);
                }
                isSticky = true;
            }

            Affinity weakAffinity = null;
            if (!inTransaction(clientInvocationContext)) {
                // non-transactional case:
                // - update the proxy's weak affinity based on the route and stickiness values received
                if (!isSticky) {
                    weakAffinity = new NodeAffinity(route);
                } else {
                    weakAffinity = new URIAffinity(HttpStickinessHelper.createURIAffinityValue(route));
                }
            } else {
                // transactional case:
                // - if no STRICT_AFFINITY_NODE present, throw exception
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

            // update the weak affinity in the proxy
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

        public InvocationStickinessHandler(EJBReceiverInvocationContext receiverInvocationContext,ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionId ) {
            this.receiverInvocationContext = receiverInvocationContext;
            this.node2SessionId = node2SessionId;
        }

        /*
         * In the case of SLSB invocation requests, we want the following conditions to hold:
         * - if request not in transaction scope:
         *   - no additional conditions as SLSB requests are free to roam
         * - if request in transaction scope:
         *   - assert weak affinity of proxy is URIAffinity
         *     - get route from URIAffinity
         *     - add JSESSIONID Cookie with route to request
         *     - add Header STRICT_AFFINITY_NODE=<route>         *
         * NOTE: a SLSB can have its string affinity changed to ClusterAffinity on the server side, therefore when
         * the SLSB is in transaction scope, we do not want it to roam freely
         *
         * In the case of SFSB invocation requests, we want the following conditions to hold:
         * - if request not in transaction scope:
         *   - if weak affinity of proxy is NodeAffinity:
         *     - get route from NodeAffinity
         *     - add JSESSIONID Cookie with route to request
         *     - no additional Headers
         *   - if weak affinity of proxy is URIAffinity:
         *     - get route from URIAffinity
         *     - add JSESSIONID Cookie with route to request
         *     - add Header STRICT_AFFINITY_NODE=<route>
         * - if request in transaction scope:
         *   - assert weak affinity is URIAffinity
         *   - get route from URIAffinity
         *   - add JSESSIONID Cookie with node to request
         *   - add Header STRICT_AFFINITY_NODE=<route>  -
         */
        @Override
        public void prepareRequest(ClientRequest request) throws Exception {
            EjbHttpClientMessages.MESSAGES.infof("Calling InvocationStickinessHandler.prepareRequest for request %s", request);

            EJBClientInvocationContext context = receiverInvocationContext.getClientInvocationContext();
            EJBLocator locator = context.getLocator();
            URI uri = context.getDestination();
            Affinity weakAffinity = context.getWeakAffinity();

            EjbHttpClientMessages.MESSAGES.infof("Calling InvocationStickinessHandler().prepareRequest(), node2sessionID map: %s", node2SessionId);

            if (inTransaction(context)) {
                // process transaction case
                assert weakAffinity instanceof URIAffinity;
                String route = ((URIAffinity)weakAffinity).getUri().getHost();
                assert route != null;

                // get sessionID from map
                String nodeSessionID = HttpStickinessHelper.getSessionIDForNode(node2SessionID, uri, route);

                // add the JSESSIONID Cookie to the request
                HttpStickinessHelper.addEncodedSessionID(request, nodeSessionID, route);

                // add a stickiness header with the node
                HttpStickinessHelper.addStrictStickinessHost(request, route);

            } else if (locator instanceof StatefulEJBLocator) {
                // process SFSB cases
                if (weakAffinity instanceof NodeAffinity) {
                    String route = ((NodeAffinity)weakAffinity).getNodeName();
                    assert route != null;

                    EjbHttpClientMessages.MESSAGES.infof("Calling InvocationStickinessHandler.prepareRequest(), node2sessionID map: %s, uri = %s, route = %s", node2SessionId, uri, route);

                    // get sessionID from map
                    String nodeSessionID = HttpStickinessHelper.getSessionIDForNode(node2SessionID, uri, route);

                    // add the JSESSIONID Cookie to the request
                    HttpStickinessHelper.addEncodedSessionID(request, nodeSessionID, route);

                } else if (weakAffinity instanceof URIAffinity) {
                    String route = ((URIAffinity)weakAffinity).getUri().getHost();
                    assert route != null;
                    // get sessionID from map
                    String nodeSessionID = HttpStickinessHelper.getSessionIDForNode(node2SessionID, uri, route);

                    // add the JSESSIONID Cookie to the request
                    HttpStickinessHelper.addEncodedSessionID(request, nodeSessionID, route);

                    // add a stickiness header with the node
                    HttpStickinessHelper.addStrictStickinessHost(request, route);
                } else {
                    // bad weak affinity value
                    throw new Exception("InvocationStickinessHandler.prepareRequest(): bad weak affinity value!: weak affinity = " + weakAffinity.toString());
                }
            }
        }

        /*
         * In the case of SLSB invocation responses, we want the following conditions to hold:
         * - if request not in transaction scope:
         *   - no additional conditions as SLSB requests are free to roam
         * - if request is in transaction scope:
         *   - expect JSESSIONID Cookie, extract route
         *   - assert Header STRICT_AFFINITY_RESULT=<boolean>
         *     - if STRICT_AFFINITY_RESULT header present, extract result:
         *       - if result == false, throw exception
         * NOTE: a SLSB can have its string affinity changed to ClusterAffinity on the server side, therefore when
         * the SLSB is in transaction scope, we do not want it to roam freely
         *
         * In the case of SFSB invocation responses, we want the following conditions to hold:
         * - if request not in transaction scope:
         *   - expect JSESSIONID Cookie, extract route
         *   - check for STRICT_AFFINITY_RESULT=<boolean>
         *     - if no STRICT_AFFINITY_RESULT header present
         *       - update weak affinity of proxy to NodeAffinity(route)
         *     - if STRICT_AFFINITY_RESULT header present, extract result:
         *       - if result == false, throw exception
         * - if request is in transaction scope:
         *   - expect JSESSIONID Cookie, extract route
         *   - assert Header STRICT_AFFINITY_RESULT=<boolean>
         *     - if STRICT_AFFINITY_RESULT header present, extract result:
         *       - if result == false, throw exception
         *
         */
        @Override
        public void processResponse(ClientExchange result) throws Exception  {
            EjbHttpClientMessages.MESSAGES.infof("InvocationStickinessHandler.processResponse for response %s", result.getResponse());

            EJBClientInvocationContext context = receiverInvocationContext.getClientInvocationContext();
            EJBLocator locator = context.getLocator();
            URI uri = context.getDestination();
            Affinity weakAffinity = context.getWeakAffinity();

            ClientResponse response = result.getResponse();

            boolean isSticky = HttpStickinessHelper.getStrictStickinessResult(response);

            if (inTransaction(context)) {
                // process transaction case
                if (!isSticky) {
                    // stickiness not respected for this transaction
                    throw new Exception("Stickiness not respected for transaction-scoped invocation");
                }
                // no need to update proxy as it is is URIAffinity
                // assert something?

            } else if (locator instanceof StatefulEJBLocator) {
                // process SFSB cases (which always have a route)
                boolean hasEncodedSessionID = HttpStickinessHelper.hasEncodedSessionID(response);
                if (!hasEncodedSessionID) {
                    // throw exception
                    throw new Exception("SFSB response is missing its route");
                }
                String encodedSessionID = HttpStickinessHelper.getEncodedSessionID(response);
                String sessionID = HttpStickinessHelper.extractSessionIDFromEncodedSessionID(encodedSessionID);
                String route = HttpStickinessHelper.extractRouteFromEncodedSessionID(encodedSessionID);
                EjbHttpClientMessages.MESSAGES.infof("InvocationStickinessHandler.processResponse(), sessionID, sessionID = %s, route = %s", sessionID, route);

                if (!isSticky) {
                    // update NodeAffinity in case we failed over
                    context.setWeakAffinity(new NodeAffinity(route));
                } else {
                    // no need to update proxy as it is URIAffinity
                }
            }
        }
    }
}
