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
package org.wildfly.httpclient.transaction;

import static org.wildfly.httpclient.common.ByteInputs.byteInputOf;
import static org.wildfly.httpclient.common.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.transaction.Serializer.deserializeXid;
import static org.wildfly.httpclient.transaction.Serializer.deserializeXidArray;
import static org.wildfly.httpclient.transaction.Serializer.serializeXid;

import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpTargetContext.RequestContext;
import org.wildfly.httpclient.common.HttpTargetContext.ResponseContext;
import org.wildfly.httpclient.common.HttpTargetContext.HttpBodyDecoder;
import org.wildfly.httpclient.common.HttpTargetContext.HttpBodyEncoder;

import javax.transaction.xa.Xid;
import java.io.InputStream;
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

    static HttpBodyEncoder xidHttpBodyEncoder(final Marshaller marshaller, final Xid xid) {
        return new XidHttpBodyEncoder(marshaller, xid);
    }

    static <T> HttpBodyDecoder emptyHttpBodyDecoder(final CompletableFuture<T> result, final Function<ResponseContext, T> function) {
        return new EmptyHttpBodyDecoder<T>(result, function);
    }

    static HttpBodyDecoder xidHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<Xid> result) {
        return new XidHttpBodyDecoder(unmarshaller, result);
    }

    static HttpBodyDecoder xidArrayHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<Xid[]> result) {
        return new XidArrayHttpBodyDecoder(unmarshaller, result);
    }

    private static final class XidHttpBodyEncoder implements HttpBodyEncoder {
        private final Marshaller marshaller;
        private final Xid xid;

        private XidHttpBodyEncoder(final Marshaller marshaller, final Xid xid) {
            this.marshaller = marshaller;
            this.xid = xid;
        }

        @Override
        public void encode(final RequestContext ctx) throws Exception {
            try (ByteOutput out = byteOutputOf(ctx.getRequestBody())) {
                marshaller.start(out);
                serializeXid(marshaller, xid);
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

    private static final class XidHttpBodyDecoder implements HttpBodyDecoder {
        private final Unmarshaller unmarshaller;
        private final CompletableFuture<Xid> result;

        private XidHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<Xid> result) {
            this.unmarshaller = unmarshaller;
            this.result = result;
        }

        @Override
        public void decode(final ResponseContext ctx) {
            try (ByteInput in = byteInputOf(ctx.getResponseBody())) {
                unmarshaller.start(in);
                Xid xid = deserializeXid(unmarshaller);
                unmarshaller.finish();
                result.complete(xid);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }
    }

    private static final class XidArrayHttpBodyDecoder implements HttpBodyDecoder {
        private final Unmarshaller unmarshaller;
        private final CompletableFuture<Xid[]> result;

        private XidArrayHttpBodyDecoder(final Unmarshaller unmarshaller, final CompletableFuture<Xid[]> result) {
            this.unmarshaller = unmarshaller;
            this.result = result;
        }

        @Override
        public void decode(final ResponseContext ctx) {
            try (ByteInput in = byteInputOf(ctx.getResponseBody())) {
                unmarshaller.start(in);
                Xid[] ret = deserializeXidArray(unmarshaller);
                unmarshaller.finish();
                result.complete(ret);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }
    }

}
