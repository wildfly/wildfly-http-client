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

import io.undertow.client.ClientResponse;
import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBModuleIdentifier;
import org.jboss.ejb.client.EJBReceiverInvocationContext;
import org.jboss.ejb.client.SessionID;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.xnio.IoUtils;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.wildfly.httpclient.ejb.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.ejb.Serializer.deserializeObject;
import static org.wildfly.httpclient.ejb.Serializer.deserializeSet;
import static org.wildfly.httpclient.ejb.Serializer.serializeMap;
import static org.wildfly.httpclient.ejb.Serializer.serializeObjectArray;
import static org.wildfly.httpclient.ejb.Serializer.serializeTransaction;
import static org.wildfly.httpclient.ejb.Serializer.deserializeMap;
import static org.xnio.IoUtils.safeClose;

/**
 * Utility class providing factory methods for creating client-side handlers of Remote EJB over HTTP protocol.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ClientHandlers {

    private ClientHandlers() {
        // forbidden instantiation
    }

    static HttpTargetContext.HttpMarshaller invokeHttpMarshaller(final Marshaller marshaller, final TransactionInfo txnInfo, final Object[] parameters, final Map<String, Object> contextData) {
        return new InvokeHttpMarshaller(marshaller, txnInfo, parameters, contextData);
    }

    static HttpTargetContext.HttpMarshaller createSessionHttpMarshaller(final Marshaller marshaller, final TransactionInfo txnInfo) {
        return new CreateSessionHttpMarshaller(marshaller, txnInfo);
    }

    static <T> HttpTargetContext.HttpResultHandler emptyHttpResultHandler(final CompletableFuture<T> result, final Function<ClientResponse, T> function) {
        return new EmptyHttpResultHandler<T>(result, function);
    }

    static HttpTargetContext.HttpResultHandler discoveryHttpResultHandler(final Unmarshaller unmarshaller, final CompletableFuture<Set<EJBModuleIdentifier>> result) {
        return new DiscoveryHttpResultHandler(unmarshaller, result);
    }

    static HttpTargetContext.HttpResultHandler invokeHttpResultHandler(final Unmarshaller unmarshaller, final EJBReceiverInvocationContext receiverContext, final EJBClientInvocationContext clientInvocationContext) {
        return new EjbClassLoaderAwareHttpResultHandler(unmarshaller, receiverContext, clientInvocationContext);
    }

    static Function<ClientResponse, Boolean> cancelInvocationResponseFunction() {
        return new CancelInvocationResponseFunction();
    }

    static Function<ClientResponse, SessionID> createSessionResponseFunction() {
        return new CreateSessionResponseFunction();
    }

    private static HttpTargetContext.HttpResultHandler invokeHttpResultHandlerInternal(final Unmarshaller unmarshaller, final CompletableFuture<InvocationInfo> result) {
        return new InvokeHttpResultHandler(unmarshaller, result);
    }

    private static final class InvokeHttpMarshaller implements HttpTargetContext.HttpMarshaller {
        private final Marshaller marshaller;
        private final TransactionInfo txnInfo;
        private final Object[] parameters;
        private final Map<String, Object> contextData;

        private InvokeHttpMarshaller(final Marshaller marshaller, final TransactionInfo txnInfo, final Object[] parameters, final Map<String, Object> contextData) {
            this.marshaller = marshaller;
            this.txnInfo = txnInfo;
            this.parameters = parameters;
            this.contextData = contextData;
        }

        @Override
        public void marshall(final OutputStream httpBodyRequestStream) throws Exception {
            try (ByteOutput out = Marshalling.createByteOutput(httpBodyRequestStream)) {
                marshaller.start(out);
                serializeTransaction(marshaller, txnInfo);
                serializeObjectArray(marshaller, parameters);
                serializeMap(marshaller, contextData);
                marshaller.finish();
            }
        }
    }

    private static final class CreateSessionHttpMarshaller implements HttpTargetContext.HttpMarshaller {
        private final Marshaller marshaller;
        private final TransactionInfo txnInfo;

        private CreateSessionHttpMarshaller(final Marshaller marshaller, final TransactionInfo txnInfo) {
            this.marshaller = marshaller;
            this.txnInfo = txnInfo;
        }

        @Override
        public void marshall(final OutputStream httpBodyRequestStream) throws Exception {
            try (ByteOutput out = byteOutputOf(httpBodyRequestStream)) {
                marshaller.start(out);
                serializeTransaction(marshaller, txnInfo);
                marshaller.finish();
            }
        }
    }

    private static final class EmptyHttpResultHandler<T> implements HttpTargetContext.HttpResultHandler {
        private final CompletableFuture<T> result;
        private final Function<ClientResponse, T> function;

        private EmptyHttpResultHandler(final CompletableFuture<T> result, final Function<ClientResponse, T> function) {
            this.result = result;
            this.function = function;
        }

        @Override
        public void handleResult(final InputStream httpBodyResponseStream, final ClientResponse httpResponse, final Closeable doneCallback) {
            try {
                result.complete(function != null ? function.apply(httpResponse) : null);
            } finally {
                IoUtils.safeClose(doneCallback);
            }
        }
    }

    private static final class DiscoveryHttpResultHandler implements HttpTargetContext.HttpResultHandler {
        private final CompletableFuture<Set<EJBModuleIdentifier>> result;
        private final Unmarshaller unmarshaller;

        private DiscoveryHttpResultHandler(final Unmarshaller unmarshaller, final CompletableFuture<Set<EJBModuleIdentifier>> result) {
            this.unmarshaller = unmarshaller;
            this.result = result;
        }

        @Override
        public void handleResult(final InputStream httpBodyResponseStream, final ClientResponse httpResponse, final Closeable doneCallback) {
            try (ByteInput in = new InputStreamByteInput(httpBodyResponseStream)) {
                Set<EJBModuleIdentifier> modules;
                unmarshaller.start(in);
                modules = deserializeSet(unmarshaller);
                unmarshaller.finish();
                result.complete(modules);
            } catch (Exception e) {
                result.completeExceptionally(e);
            } finally {
                safeClose(doneCallback);
            }
        }
    }

    private static final class EjbClassLoaderAwareHttpResultHandler implements HttpTargetContext.HttpResultHandler {
        private static final Set<String> WELL_KNOWN_KEYS;

        static {
            WELL_KNOWN_KEYS = new HashSet<>();
            WELL_KNOWN_KEYS.add("jboss.source.address");
        }

        private final EJBReceiverInvocationContext receiverContext;
        private final EJBClientInvocationContext clientInvocationContext;
        private final Unmarshaller unmarshaller;

        private EjbClassLoaderAwareHttpResultHandler(final Unmarshaller unmarshaller, final EJBReceiverInvocationContext receiverContext, final EJBClientInvocationContext clientInvocationContext) {
            this.unmarshaller = unmarshaller;
            this.receiverContext = receiverContext;
            this.clientInvocationContext = clientInvocationContext;
        }

        public void handleResult(final InputStream httpBodyResponseStream, final ClientResponse httpResponse, final Closeable doneCallback) {
            receiverContext.resultReady(new EJBReceiverInvocationContext.ResultProducer() {
                @Override
                public Object getResult() throws Exception {
                    final CompletableFuture<InvocationInfo> result = new CompletableFuture<>();
                    invokeHttpResultHandlerInternal(unmarshaller, result).handleResult(httpBodyResponseStream, httpResponse, doneCallback);

                    // WEJBHTTP-83 - remove jboss.returned.keys values from the local context data, so that after unmarshalling the response, we have the correct ContextData
                    Set<String> returnedContextDataKeys = (Set<String>) clientInvocationContext.getContextData().get(EJBClientInvocationContext.RETURNED_CONTEXT_DATA_KEY);
                    if(returnedContextDataKeys != null) {
                        clientInvocationContext.getContextData().keySet().removeIf(k -> (!k.equals(EJBClientInvocationContext.RETURNED_CONTEXT_DATA_KEY)));
                    }
                    Set<String> returnedKeys =  (Set<String>) clientInvocationContext.getContextData().get(EJBClientInvocationContext.RETURNED_CONTEXT_DATA_KEY);

                    // If there are any attachments, add them to the client invocation's context data
                    if (result.get().getAttachments() != null) {
                        for (Map.Entry<String, Object> entry : result.get().getAttachments().entrySet()) {
                            if (entry.getValue() != null &&
                                    ((returnedKeys != null && returnedKeys.contains(entry.getKey())) || WELL_KNOWN_KEYS.contains(entry.getKey()))) {
                                clientInvocationContext.getContextData().put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                    return result.get().getResult();
                }

                @Override
                public void discardResult() {
                    IoUtils.safeClose(doneCallback);
                    IoUtils.safeClose(httpBodyResponseStream);
                }
            });

        }
    }

    private static final class InvokeHttpResultHandler implements HttpTargetContext.HttpResultHandler {
        private final CompletableFuture<InvocationInfo> result;
        private final Unmarshaller unmarshaller;

        private InvokeHttpResultHandler(final Unmarshaller unmarshaller, final CompletableFuture<InvocationInfo> result) {
            this.unmarshaller = unmarshaller;
            this.result = result;
        }

        @Override
        public void handleResult(final InputStream httpBodyResponseStream, final ClientResponse httpResponse, final Closeable doneCallback) {
            try (ByteInput in = new InputStreamByteInput(httpBodyResponseStream)) {
                unmarshaller.start(in);
                final Object returned = deserializeObject(unmarshaller);
                final Map<String, Object> attachments = deserializeMap(unmarshaller);
                unmarshaller.finish();
                result.complete(InvocationInfo.newInstance(returned, attachments));
            } catch (Exception e) {
                result.completeExceptionally(e);
            } finally {
                safeClose(doneCallback);
            }
        }
    }

    private static final class CancelInvocationResponseFunction implements Function<ClientResponse, Boolean> {
        @Override
        public Boolean apply(final ClientResponse clientResponse) {
            return true;
        }
    }

    private static final class CreateSessionResponseFunction implements Function<ClientResponse, SessionID> {
        @Override
        public SessionID apply(final ClientResponse clientResponse) {
            final String sessionId = clientResponse.getResponseHeaders().getFirst(Constants.EJB_SESSION_ID);
            if (sessionId != null) {
                return SessionID.createSessionID(Base64.getUrlDecoder().decode(sessionId));
            }
            throw new IllegalStateException(EjbHttpClientMessages.MESSAGES.noSessionIdInResponse());
        }
    }

}
