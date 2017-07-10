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

import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
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
import javax.ejb.Asynchronous;
import javax.net.ssl.SSLContext;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.xa.Xid;

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
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;
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
import io.undertow.client.ClientRequest;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

/**
 * @author Stuart Douglas
 */
class HttpEJBReceiver extends EJBReceiver {

    private final AttachmentKey<EjbContextData> EJB_CONTEXT_DATA = AttachmentKey.create(EjbContextData.class);
    private final org.jboss.ejb.client.AttachmentKey<String> INVOCATION_ID = new org.jboss.ejb.client.AttachmentKey<>();
    private final RemoteTransactionContext transactionContext;

    private static final AtomicLong invocationIdGenerator = new AtomicLong();

    HttpEJBReceiver() {
        transactionContext = RemoteTransactionContext.getInstance();
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
        targetContext.awaitSessionId(false);


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
        ClientRequest request = builder.createRequest(targetContext.getUri().getPath());
        if (compressResponse) {
            request.getRequestHeaders().put(Headers.ACCEPT_ENCODING, Headers.GZIP.toString());
        }
        request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, Headers.CHUNKED.toString());
        final boolean compressRequest = receiverContext.getClientInvocationContext().isCompressRequest();
        if (compressRequest) {
            request.getRequestHeaders().put(Headers.CONTENT_ENCODING, Headers.GZIP.toString());
        }
        SSLContext sslContext = receiverContext.getSSLContext();
        AuthenticationConfiguration authenticationConfiguration = receiverContext.getAuthenticationConfiguration();
        if (sslContext == null || authenticationConfiguration == null) {
            final AuthenticationContext context = AuthenticationContext.captureCurrent();
            if (sslContext == null) {
                sslContext = CLIENT.getSSLContext(uri, context);
            }
            if (authenticationConfiguration == null) {
                authenticationConfiguration = CLIENT.getAuthenticationConfiguration(uri, context);
            }
        }
        targetContext.sendRequest(request, sslContext, authenticationConfiguration, (output -> {
                    OutputStream data = output;
                    if (compressRequest) {
                        data = new GZIPOutputStream(data);
                    }
                    try {
                        marshalEJBRequest(Marshalling.createByteOutput(data), clientInvocationContext, targetContext);
                    } finally {
                        IoUtils.safeClose(data);
                    }
                }),

                ((input, response) -> {
                    if (response.getResponseCode() == StatusCodes.ACCEPTED && clientInvocationContext.getInvokedMethod().getReturnType() == void.class) {
                        ejbData.asyncMethods.add(clientInvocationContext.getInvokedMethod());
                    }

                    Exception exception = null;
                    Object returned = null;
                    try {

                        final MarshallingConfiguration marshallingConfiguration = createMarshallingConfig(targetContext.getUri());
                        final Unmarshaller unmarshaller = targetContext.createUnmarshaller(marshallingConfiguration);

                        unmarshaller.start(new InputStreamByteInput(input));
                        returned = unmarshaller.readObject();
                        // read the attachments
                        //TODO: do we need attachments?
                        final Map<String, Object> attachments = readAttachments(unmarshaller);
                        // finish unmarshalling
                        if (unmarshaller.read() != -1) {
                            exception = EjbHttpClientMessages.MESSAGES.unexpectedDataInResponse();
                        }
                        unmarshaller.finish();

                        if (response.getResponseCode() >= 400) {
                            receiverContext.resultReady(new StaticResultProducer((Exception) returned, null));
                            return;
                        }
                    } catch (Exception e) {
                        exception = e;
                    }
                    final Object ret = returned;
                    final Exception ex = exception;
                    receiverContext.resultReady(new StaticResultProducer(ex, ret));
                }),
                (e) -> receiverContext.resultReady(new StaticResultProducer(e instanceof Exception ? (Exception) e : new RuntimeException(e), null)), EjbHeaders.EJB_RESPONSE_VERSION_ONE, null);
    }

    private static final AuthenticationContextConfigurationClient CLIENT = doPrivileged(AuthenticationContextConfigurationClient.ACTION);

    protected SessionID createSession(final EJBReceiverSessionCreationContext receiverContext) throws Exception {
        final EJBLocator<?> locator = receiverContext.getClientInvocationContext().getLocator();
        URI uri = receiverContext.getClientInvocationContext().getDestination();
        SSLContext sslContext = receiverContext.getSSLContext();
        AuthenticationConfiguration authenticationConfiguration = receiverContext.getAuthenticationConfiguration();
        if (sslContext == null || authenticationConfiguration == null) {
            final AuthenticationContext context = AuthenticationContext.captureCurrent();
            if (sslContext == null) {
                sslContext = CLIENT.getSSLContext(uri, context);
            }
            if (authenticationConfiguration == null) {
                authenticationConfiguration = CLIENT.getAuthenticationConfiguration(uri, context);
            }
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

        targetContext.awaitSessionId(true);
        CompletableFuture<StatefulEJBLocator<?>> result = new CompletableFuture<>();

        HttpEJBInvocationBuilder builder = new HttpEJBInvocationBuilder()
                .setInvocationType(HttpEJBInvocationBuilder.InvocationType.STATEFUL_CREATE)
                .setAppName(locator.getAppName())
                .setModuleName(locator.getModuleName())
                .setDistinctName(locator.getDistinctName())
                .setView(locator.getViewType().getName())
                .setBeanName(locator.getBeanName());
        ClientRequest request = builder.createRequest(targetContext.getUri().getPath());
        targetContext.sendRequest(request, sslContext, authenticationConfiguration, output -> {
                    MarshallingConfiguration config = createMarshallingConfig(targetContext.getUri());
                    Marshaller marshaller = targetContext.createMarshaller(config);
                    marshaller.start(Marshalling.createByteOutput(output));
                    writeTransaction(ContextTransactionManager.getInstance().getTransaction(), marshaller, targetContext.getUri());
                    marshaller.finish();
                },
                ((unmarshaller, response) -> {
                    String sessionId = response.getResponseHeaders().getFirst(EjbHeaders.EJB_SESSION_ID);
                    if (sessionId == null) {
                        result.completeExceptionally(EjbHttpClientMessages.MESSAGES.noSessionIdInResponse());
                    } else {
                        SessionID sessionID = SessionID.createSessionID(Base64.getUrlDecoder().decode(sessionId));
                        result.complete(locator.withSession(sessionID));
                    }
                })
                , result::completeExceptionally, EjbHeaders.EJB_RESPONSE_NEW_SESSION, null);

        return result.get().getSessionId();
    }

    @Override
    protected boolean cancelInvocation(EJBReceiverInvocationContext receiverContext, boolean cancelIfRunning) {

        EJBClientInvocationContext clientInvocationContext = receiverContext.getClientInvocationContext();
        EJBLocator<?> locator = clientInvocationContext.getLocator();

        Affinity affinity = locator.getAffinity();
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
        targetContext.awaitSessionId(false);
        HttpEJBInvocationBuilder builder = new HttpEJBInvocationBuilder()
                .setInvocationType(HttpEJBInvocationBuilder.InvocationType.CANCEL)
                .setAppName(locator.getAppName())
                .setModuleName(locator.getModuleName())
                .setDistinctName(locator.getDistinctName())
                .setCancelIfRunning(cancelIfRunning)
                .setInvocationId(receiverContext.getClientInvocationContext().getAttachment(INVOCATION_ID))
                .setBeanName(locator.getBeanName());
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        targetContext.sendRequest(builder.createRequest(targetContext.getUri().getPath()), receiverContext.getSSLContext(), receiverContext.getAuthenticationConfiguration(), null, (stream, response) -> {
            result.complete(true);
            IoUtils.safeClose(stream);
        }, throwable -> result.complete(false), null, null);
        try {
            return result.get();
        } catch (InterruptedException | ExecutionException e) {
            return false;
        }
    }

    private MarshallingConfiguration createMarshallingConfig(URI uri) {
        final MarshallingConfiguration marshallingConfiguration = new MarshallingConfiguration();
        marshallingConfiguration.setObjectResolver(new HttpProtocolV1ObjectResolver(uri));
        marshallingConfiguration.setObjectTable(HttpProtocolV1ObjectTable.INSTANCE);
        marshallingConfiguration.setVersion(2);
        return marshallingConfiguration;
    }

    private void marshalEJBRequest(ByteOutput byteOutput, EJBClientInvocationContext clientInvocationContext, HttpTargetContext targetContext) throws IOException, RollbackException, SystemException {

        MarshallingConfiguration config = createMarshallingConfig(targetContext.getUri());
        Marshaller marshaller = targetContext.createMarshaller(config);
        marshaller.start(byteOutput);
        writeTransaction(clientInvocationContext.getTransaction(), marshaller, targetContext.getUri());


        Object[] methodParams = clientInvocationContext.getParameters();
        if (methodParams != null && methodParams.length > 0) {
            for (final Object methodParam : methodParams) {
                marshaller.writeObject(methodParam);
            }
        }
        // write out the attachments
        // we write out the private (a.k.a JBoss specific) attachments as well as public invocation context data
        // (a.k.a user application specific data)
        final Map<?, ?> privateAttachments = clientInvocationContext.getAttachments();
        final Map<String, Object> contextData = clientInvocationContext.getContextData();
        int privateAttachmentsSize = privateAttachments.size() - (privateAttachments.containsKey(INVOCATION_ID) ? 1 : 0);
        // no private or public data to write out
        if (contextData == null && privateAttachmentsSize == 0) {
            marshaller.writeByte(0);
        } else {
            // write the attachment count which is the sum of invocation context data + 1 (since we write
            // out the private attachments under a single key with the value being the entire attachment map)
            int totalAttachments = contextData.size();
            if (privateAttachmentsSize > 0) {
                totalAttachments++;
            }
            PackedInteger.writePackedInteger(marshaller, totalAttachments);
            // write out public (application specific) context data
            for (Map.Entry<String, Object> invocationContextData : contextData.entrySet()) {
                marshaller.writeObject(invocationContextData.getKey());
                marshaller.writeObject(invocationContextData.getValue());
            }
            if (privateAttachmentsSize > 0) {
                // now write out the JBoss specific attachments under a single key and the value will be the
                // entire map of JBoss specific attachments
                marshaller.writeObject(EJBClientInvocationContext.PRIVATE_ATTACHMENTS_KEY);
                Map<?, ?> copy = new HashMap<>(privateAttachments);
                copy.remove(INVOCATION_ID);
                marshaller.writeObject(copy);
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
            final XidProvider ir = ((RemoteTransaction) transaction).getProviderInterface(XidProvider.class);
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
        private final Exception ex;
        private final Object ret;

        public StaticResultProducer(Exception ex, Object ret) {
            this.ex = ex;
            this.ret = ret;
        }

        @Override
        public Object getResult() throws Exception {
            if (ex != null) {
                throw ex;
            }
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
