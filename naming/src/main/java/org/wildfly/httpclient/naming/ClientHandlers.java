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

import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpTargetContext.RequestContext;
import org.wildfly.httpclient.common.HttpTargetContext.ResponseContext;
import org.wildfly.httpclient.common.HttpTargetContext.HttpBodyDecoder;
import org.wildfly.httpclient.common.HttpTargetContext.HttpBodyEncoder;
import org.wildfly.naming.client.NamingProvider;

import java.io.InputStream;
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

    static HttpBodyEncoder objectHttpBodyEncoder(final Marshaller marshaller, final Object object) {
        return new ObjectHttpBodyEncoder(marshaller, object);
    }

    static <T> HttpBodyDecoder emptyHttpBodyDecoder(final CompletableFuture<T> result, final Function<ResponseContext, T> function) {
        return new EmptyHttpBodyDecoder<T>(result, function);
    }

    static HttpBodyDecoder optionalObjectHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<Object> result, final NamingProvider namingProvider, final ClassLoader classLoader) {
        return new OptionalObjectHttpBodyDecoder(unmarshaller, result, namingProvider, classLoader);
    }

    private static HttpBodyDecoder objectHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<Object> result) {
        return new ObjectHttpBodyDecoder(unmarshaller, result);
    }

    private static final class ObjectHttpBodyEncoder implements HttpBodyEncoder {
        private final Marshaller marshaller;
        private final Object object;

        private ObjectHttpBodyEncoder(final Marshaller marshaller, final Object object) {
            this.marshaller = marshaller;
            this.object = object;
        }

        @Override
        public void encode(final RequestContext ctx) throws Exception {
            try (ByteOutput out = byteOutputOf(ctx.getRequestBody())) {
                marshaller.start(out);
                serializeObject(marshaller, object);
                marshaller.finish();
            }
        }
    }

    private static final class EmptyHttpBodyDecoder<T> implements HttpBodyDecoder {
        private final CompletableFuture<T> result;
        private final Function<ResponseContext, T> function;

        private EmptyHttpBodyDecoder(final CompletableFuture<T> result, final Function<ResponseContext, T> function) {
            this.result = result;
            this.function = function;
        }

        @Override
        public void decode(final ResponseContext ctx) {
            final InputStream is = ctx.getResponseBody();
            try (is) {
                result.complete(function != null ? function.apply(ctx) : null);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }
    }

    private static final class OptionalObjectHttpBodyDecoder implements HttpBodyDecoder {
        private final Unmarshaller unmarshaller;
        private final CompletableFuture<Object> result;
        private final NamingProvider namingProvider;
        private final ClassLoader classLoader;

        private OptionalObjectHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<Object> result, final NamingProvider namingProvider, final ClassLoader classLoader) {
            this.unmarshaller = unmarshaller;
            this.result = result;
            this.namingProvider = namingProvider;
            this.classLoader = classLoader;
        }

        @Override
        public void decode(final ResponseContext ctx) {
            namingProvider.performExceptionAction((a, b) -> {
                ClassLoader old = setContextClassLoader(classLoader);
                try {
                    if (ctx.getResponseCode() == NO_CONTENT) {
                        emptyHttpBodyDecoder(result, null).decode(ctx);
                    } else {
                        objectHttpBodyDecoder(unmarshaller, result).decode(ctx);
                    }
                } finally {
                    setContextClassLoader(old);
                }
                return null;
            }, null, null);
        }
    }

    private static final class ObjectHttpBodyDecoder implements HttpBodyDecoder {
        private final Unmarshaller unmarshaller;
        private final CompletableFuture<Object> result;

        private ObjectHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<Object> result) {
            this.unmarshaller = unmarshaller;
            this.result = result;
        }

        @Override
        public void decode(final ResponseContext ctx) {
            try (ByteInput in = byteInputOf(ctx.getResponseBody())) {
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
