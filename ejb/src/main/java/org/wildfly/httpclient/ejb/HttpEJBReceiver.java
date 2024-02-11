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

import io.undertow.client.ClientRequest;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBLocator;
import org.jboss.ejb.client.EJBReceiver;
import org.jboss.ejb.client.EJBReceiverInvocationContext;
import org.jboss.ejb.client.EJBReceiverSessionCreationContext;
import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.client.StatefulEJBLocator;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.httpclient.common.WildflyHttpContext;
import org.wildfly.httpclient.transaction.XidProvider;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;
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
        WildflyHttpContext current = WildflyHttpContext.getCurrent();
        HttpTargetContext targetContext = current.getTargetContext(uri);
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
        targetContext.awaitSessionId(false, AUTH_CONTEXT_CLIENT.getAuthenticationConfiguration(targetContext.getUri(), receiverContext.getAuthenticationContext()));


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
            //cancellation is only supported if we have affinity
            if (targetContext.getSessionId() != null) {
                long invocationId = invocationIdGenerator.incrementAndGet();
                String invocationIdString = Long.toString(invocationId);
                builder.setInvocationId(invocationIdString);
                clientInvocationContext.putAttachment(INVOCATION_ID, invocationIdString);
            }
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
        targetContext.sendRequest(request, sslContext, authenticationConfiguration, (output -> {
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
                                        clientInvocationContext.getContextData().keySet().removeIf(k -> (!k.equals(EJBClientInvocationContext.RETURNED_CONTEXT_DATA_KEY)));
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
                (e) -> receiverContext.requestFailed(e instanceof Exception ? (Exception) e : new RuntimeException(e)), EjbConstants.EJB_RESPONSE, null);
    }

    private static final AuthenticationContextConfigurationClient CLIENT = doPrivileged(AuthenticationContextConfigurationClient.ACTION);

    protected SessionID createSession(final EJBReceiverSessionCreationContext receiverContext) throws Exception {
        final EJBLocator<?> locator = receiverContext.getClientInvocationContext().getLocator();
        URI uri = receiverContext.getClientInvocationContext().getDestination();
        final AuthenticationContext context = receiverContext.getAuthenticationContext();
        final AuthenticationContextConfigurationClient client = CLIENT;
        final int defaultPort = uri.getScheme().equals(HTTPS_SCHEME) ? HTTPS_PORT : HTTP_PORT;
        final AuthenticationConfiguration authenticationConfiguration = client.getAuthenticationConfiguration(uri, context, defaultPort, "jndi", "jboss");
        final SSLContext sslContext = client.getSSLContext(uri, context, "jndi", "jboss");
        WildflyHttpContext current = WildflyHttpContext.getCurrent();
        HttpTargetContext targetContext = current.getTargetContext(uri);
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

        targetContext.awaitSessionId(true, authenticationConfiguration);
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
        targetContext.sendRequest(request, sslContext, authenticationConfiguration, output -> {
                    Marshaller marshaller = createMarshaller(targetContext.getUri(), targetContext.getHttpMarshallerFactory(request));
                    marshaller.start(Marshalling.createByteOutput(output));
                    writeTransaction(ContextTransactionManager.getInstance().getTransaction(), marshaller, targetContext.getUri());
                    marshaller.finish();
                },
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
                })
                , result::completeExceptionally, EjbConstants.EJB_RESPONSE_NEW_SESSION, null);

        return result.get();
    }

    @Override
    protected boolean cancelInvocation(EJBReceiverInvocationContext receiverContext, boolean cancelIfRunning) {

        EJBClientInvocationContext clientInvocationContext = receiverContext.getClientInvocationContext();
        EJBLocator<?> locator = clientInvocationContext.getLocator();

        Affinity affinity = locator.getAffinity();
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
        WildflyHttpContext current = WildflyHttpContext.getCurrent();
        HttpTargetContext targetContext = current.getTargetContext(uri);
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
        targetContext.awaitSessionId(false, authenticationConfiguration);
        HttpEJBInvocationBuilder builder = new HttpEJBInvocationBuilder()
                .setInvocationType(HttpEJBInvocationBuilder.InvocationType.CANCEL)
                .setAppName(locator.getAppName())
                .setModuleName(locator.getModuleName())
                .setDistinctName(locator.getDistinctName())
                .setCancelIfRunning(cancelIfRunning)
                .setInvocationId(receiverContext.getClientInvocationContext().getAttachment(INVOCATION_ID))
                .setBeanName(locator.getBeanName());
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        builder.setVersion(targetContext.getProtocolVersion());
        targetContext.sendRequest(builder.createRequest(targetContext.getUri().getPath()), sslContext, authenticationConfiguration, null, (stream, response, closeable) -> {
            try {
                result.complete(true);
                IoUtils.safeClose(stream);
            } finally {
                IoUtils.safeClose(closeable);
            }
        }, throwable -> result.complete(false), null, null);
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
}
