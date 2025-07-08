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

import static org.wildfly.httpclient.common.HeadersHelper.getResponseHeader;
import static org.wildfly.httpclient.common.ByteInputs.byteInputOf;
import static org.wildfly.httpclient.common.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.ejb.Constants.EJB_SESSION_ID;
import static org.wildfly.httpclient.ejb.Serializer.deserializeObject;
import static org.wildfly.httpclient.ejb.Serializer.deserializeSet;
import static org.wildfly.httpclient.ejb.Serializer.serializeMap;
import static org.wildfly.httpclient.ejb.Serializer.serializeObjectArray;
import static org.wildfly.httpclient.ejb.Serializer.serializeTransaction;
import static org.wildfly.httpclient.ejb.Serializer.deserializeMap;

import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBModuleIdentifier;
import org.jboss.ejb.client.EJBReceiverInvocationContext;
import org.jboss.ejb.client.SessionID;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpTargetContext.HttpBodyDecoder;
import org.wildfly.httpclient.common.HttpTargetContext.HttpBodyEncoder;
import org.xnio.IoUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Utility class providing factory methods for creating client-side handlers of Remote EJB over HTTP protocol.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ClientHandlers {

    private ClientHandlers() {
        // forbidden instantiation
    }

    static HttpBodyEncoder invokeHttpBodyEncoder(final Marshaller marshaller, final TransactionInfo txnInfo, final Object[] objects, final Map<String, Object> map) {
        return new InvokeHttpBodyEncoder(marshaller, txnInfo, objects, map);
    }

    static HttpBodyEncoder createSessionHttpBodyEncoder(final Marshaller marshaller, final TransactionInfo txnInfo) {
        return new CreateSessionHttpBodyEncoder(marshaller, txnInfo);
    }

    static <T> HttpBodyDecoder emptyHttpBodyDecoder(final CompletableFuture<T> result, final Function<ClientResponse, T> function) {
        return new EmptyHttpBodyDecoder<T>(result, function);
    }

    static HttpBodyDecoder discoveryHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<Set<EJBModuleIdentifier>> result) {
        return new DiscoveryHttpBodyDecoder(unmarshaller, result);
    }

    static HttpBodyDecoder invokeHttpBodyDecoder(final Unmarshaller unmarshaller, final EJBReceiverInvocationContext receiverCtx, final EJBClientInvocationContext clientCtx) {
        return new EjbClassLoaderAwareHttpBodyDecoder(unmarshaller, receiverCtx, clientCtx);
    }

    static Function<ClientResponse, Boolean> cancelInvocationResponseFunction() {
        return new CancelInvocationResponseFunction();
    }

    static Function<ClientResponse, SessionID> createSessionResponseFunction() {
        return new CreateSessionResponseFunction();
    }

    private static HttpBodyDecoder invokeHttpBodyDecoderInternal(final Unmarshaller unmarshaller, final CompletableFuture<InvocationInfo> result) {
        return new InvokeHttpBodyDecoder(unmarshaller, result);
    }

    private static final class InvokeHttpBodyEncoder implements HttpBodyEncoder {
        private final Marshaller marshaller;
        private final TransactionInfo txnInfo;
        private final Object[] objects;
        private final Map<String, Object> map;

        private InvokeHttpBodyEncoder(final Marshaller marshaller, final TransactionInfo txnInfo, final Object[] objects, final Map<String, Object> map) {
            this.marshaller = marshaller;
            this.txnInfo = txnInfo;
            this.objects = objects;
            this.map = map;
        }

        @Override
        public void encode(final OutputStream os, final ClientRequest request) throws Exception {
            try (ByteOutput out = byteOutputOf(os)) {
                marshaller.start(out);
                serializeTransaction(marshaller, txnInfo);
                serializeObjectArray(marshaller, objects);
                serializeMap(marshaller, map);
                marshaller.finish();
            }
        }
    }

    private static final class CreateSessionHttpBodyEncoder implements HttpBodyEncoder {
        private final Marshaller marshaller;
        private final TransactionInfo txnInfo;

        private CreateSessionHttpBodyEncoder(final Marshaller marshaller, final TransactionInfo txnInfo) {
            this.marshaller = marshaller;
            this.txnInfo = txnInfo;
        }

        @Override
        public void encode(final OutputStream os, final ClientRequest request) throws Exception {
            try (ByteOutput out = byteOutputOf(os)) {
                marshaller.start(out);
                serializeTransaction(marshaller, txnInfo);
                marshaller.finish();
            }
        }
    }

    private static final class EmptyHttpBodyDecoder<T> implements HttpBodyDecoder {
        private final CompletableFuture<T> result;
        private final Function<ClientResponse, T> function;

        private EmptyHttpBodyDecoder(final CompletableFuture<T> result, final Function<ClientResponse, T> function) {
            this.result = result;
            this.function = function;
        }

        @Override
        public void decode(final InputStream is, final ClientResponse response) {
            try (is) {
                result.complete(function != null ? function.apply(response) : null);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }
    }

    private static final class DiscoveryHttpBodyDecoder implements HttpBodyDecoder {
        private final Unmarshaller unmarshaller;
        private final CompletableFuture<Set<EJBModuleIdentifier>> result;

        private DiscoveryHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<Set<EJBModuleIdentifier>> result) {
            this.unmarshaller = unmarshaller;
            this.result = result;
        }

        @Override
        public void decode(final InputStream is, final ClientResponse response) {
            try (ByteInput in = byteInputOf(is)) {
                Set<EJBModuleIdentifier> modules;
                unmarshaller.start(in);
                modules = deserializeSet(unmarshaller);
                unmarshaller.finish();
                result.complete(modules);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }
    }

    private static final class EjbClassLoaderAwareHttpBodyDecoder implements HttpBodyDecoder {
        private static final Set<String> WELL_KNOWN_KEYS;

        static {
            WELL_KNOWN_KEYS = new HashSet<>();
            WELL_KNOWN_KEYS.add("jboss.source.address");
        }

        private final Unmarshaller unmarshaller;
        private final EJBReceiverInvocationContext receiverCtx;
        private final EJBClientInvocationContext clientCtx;

        private EjbClassLoaderAwareHttpBodyDecoder(final Unmarshaller unmarshaller, final EJBReceiverInvocationContext receiverCtx, final EJBClientInvocationContext clientCtx) {
            this.unmarshaller = unmarshaller;
            this.receiverCtx = receiverCtx;
            this.clientCtx = clientCtx;
        }

        public void decode(final InputStream is, final ClientResponse response) {
            receiverCtx.resultReady(new EJBReceiverInvocationContext.ResultProducer() {
                @Override
                public Object getResult() throws Exception {
                    final CompletableFuture<InvocationInfo> result = new CompletableFuture<>();
                    invokeHttpBodyDecoderInternal(unmarshaller, result).decode(is, response);

                    // WEJBHTTP-83 - remove jboss.returned.keys values from the local context data, so that after unmarshalling the response, we have the correct ContextData
                    Set<String> returnedContextDataKeys = (Set<String>) clientCtx.getContextData().get(EJBClientInvocationContext.RETURNED_CONTEXT_DATA_KEY);
                    if(returnedContextDataKeys != null) {
                        clientCtx.getContextData().keySet().removeIf(k -> (!k.equals(EJBClientInvocationContext.RETURNED_CONTEXT_DATA_KEY)));
                    }
                    Set<String> returnedKeys =  (Set<String>) clientCtx.getContextData().get(EJBClientInvocationContext.RETURNED_CONTEXT_DATA_KEY);

                    // If there are any attachments, add them to the client invocation's context data
                    if (result.get().getAttachments() != null) {
                        for (Map.Entry<String, Object> entry : result.get().getAttachments().entrySet()) {
                            if (entry.getValue() != null &&
                                    ((returnedKeys != null && returnedKeys.contains(entry.getKey())) || WELL_KNOWN_KEYS.contains(entry.getKey()))) {
                                clientCtx.getContextData().put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                    return result.get().getResult();
                }

                @Override
                public void discardResult() {
                    IoUtils.safeClose(is);
                }
            });

        }
    }

    private static final class InvokeHttpBodyDecoder implements HttpBodyDecoder {
        private final Unmarshaller unmarshaller;
        private final CompletableFuture<InvocationInfo> result;

        private InvokeHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<InvocationInfo> result) {
            this.unmarshaller = unmarshaller;
            this.result = result;
        }

        @Override
        public void decode(final InputStream is, final ClientResponse response) {
            try (ByteInput in = byteInputOf(is)) {
                unmarshaller.start(in);
                final Object returned = deserializeObject(unmarshaller);
                final Map<String, Object> attachments = deserializeMap(unmarshaller);
                unmarshaller.finish();
                result.complete(InvocationInfo.newInstance(returned, attachments));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }
    }

    private static final class CancelInvocationResponseFunction implements Function<ClientResponse, Boolean> {
        @Override
        public Boolean apply(final ClientResponse response) {
            return true;
        }
    }

    private static final class CreateSessionResponseFunction implements Function<ClientResponse, SessionID> {
        @Override
        public SessionID apply(final ClientResponse response) {
            final String sessionId = getResponseHeader(response, EJB_SESSION_ID);
            if (sessionId != null) {
                return SessionID.createSessionID(Base64.getUrlDecoder().decode(sessionId));
            }
            throw new IllegalStateException(EjbHttpClientMessages.MESSAGES.noSessionIdInResponse());
        }
    }

}
