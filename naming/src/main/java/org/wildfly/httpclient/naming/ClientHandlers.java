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
package org.wildfly.httpclient.naming;

import static io.undertow.util.StatusCodes.NO_CONTENT;
import static org.wildfly.httpclient.common.ByteInputs.byteInputOf;
import static org.wildfly.httpclient.common.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.naming.Serializer.deserializeObject;
import static org.wildfly.httpclient.naming.Serializer.serializeObject;
import static org.wildfly.httpclient.naming.ClassLoaderUtils.setContextClassLoader;

import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.naming.client.NamingProvider;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Utility class providing factory methods for creating client-side handlers of Remote JNDI over HTTP protocol.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ClientHandlers {

    private ClientHandlers() {
        // forbidden instantiation
    }

    static HttpTargetContext.HttpMarshaller objectHttpMarshaller(final Marshaller marshaller, final Object object) {
        return new ObjectHttpMarshaller(marshaller, object);
    }

    static <T> HttpTargetContext.HttpResultHandler emptyHttpResultHandler(final CompletableFuture<T> result, final Function<ClientResponse, T> function) {
        return new EmptyHttpResultHandler<T>(result, function);
    }

    static HttpTargetContext.HttpResultHandler optionalObjectHttpResultHandler(final Unmarshaller unmarshaller, final CompletableFuture<Object> result, final NamingProvider namingProvider, final ClassLoader classLoader) {
        return new OptionalObjectHttpResultHandler(unmarshaller, result, namingProvider, classLoader);
    }

    private static HttpTargetContext.HttpResultHandler objectHttpResultHandler(final Unmarshaller unmarshaller, final CompletableFuture<Object> result) {
        return new ObjectHttpResultHandler(unmarshaller, result);
    }

    private static final class ObjectHttpMarshaller implements HttpTargetContext.HttpMarshaller {
        private final Marshaller marshaller;
        private final Object object;

        private ObjectHttpMarshaller(final Marshaller marshaller, final Object object) {
            this.marshaller = marshaller;
            this.object = object;
        }

        @Override
        public void marshall(final OutputStream os, final ClientRequest request) throws Exception {
            try (ByteOutput out = byteOutputOf(os)) {
                marshaller.start(out);
                serializeObject(marshaller, object);
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
        public void handleResult(final InputStream is, final ClientResponse response) {
            try (is) {
                result.complete(function != null ? function.apply(response) : null);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }
    }

    private static final class OptionalObjectHttpResultHandler implements HttpTargetContext.HttpResultHandler {
        private final Unmarshaller unmarshaller;
        private final CompletableFuture<Object> result;
        private final NamingProvider namingProvider;
        private final ClassLoader classLoader;

        private OptionalObjectHttpResultHandler(final Unmarshaller unmarshaller, final CompletableFuture<Object> result, final NamingProvider namingProvider, final ClassLoader classLoader) {
            this.unmarshaller = unmarshaller;
            this.result = result;
            this.namingProvider = namingProvider;
            this.classLoader = classLoader;
        }

        @Override
        public void handleResult(final InputStream is, final ClientResponse response) {
            namingProvider.performExceptionAction((a, b) -> {
                ClassLoader old = setContextClassLoader(classLoader);
                try {
                    if (response.getResponseCode() == NO_CONTENT) {
                        emptyHttpResultHandler(result, null).handleResult(is, response);
                    } else {
                        objectHttpResultHandler(unmarshaller, result).handleResult(is, response);
                    }
                } finally {
                    setContextClassLoader(old);
                }
                return null;
            }, null, null);
        }
    }

    private static final class ObjectHttpResultHandler implements HttpTargetContext.HttpResultHandler {
        private final Unmarshaller unmarshaller;
        private final CompletableFuture<Object> result;

        private ObjectHttpResultHandler(final Unmarshaller unmarshaller, final CompletableFuture<Object> result) {
            this.unmarshaller = unmarshaller;
            this.result = result;
        }

        @Override
        public void handleResult(final InputStream is, final ClientResponse response) {
            try (ByteInput in = byteInputOf(is)) {
                unmarshaller.start(in);
                Object object = deserializeObject(unmarshaller);
                unmarshaller.finish();
                result.complete(object);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }
    }

}
