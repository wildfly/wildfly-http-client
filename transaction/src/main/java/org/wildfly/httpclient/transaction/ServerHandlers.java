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
import static org.wildfly.httpclient.common.HttpServerHelper.sendException;
import static org.wildfly.httpclient.transaction.Constants.NEW_TRANSACTION;
import static org.wildfly.httpclient.transaction.Constants.RECOVERY_FLAGS;
import static org.wildfly.httpclient.transaction.Constants.RECOVERY_PARENT_NAME;
import static org.wildfly.httpclient.transaction.Constants.TIMEOUT;
import static org.wildfly.httpclient.transaction.Constants.XID;
import static org.wildfly.httpclient.transaction.Serializer.deserializeXid;
import static org.wildfly.httpclient.transaction.Serializer.serializeXid;
import static org.wildfly.httpclient.transaction.Serializer.serializeXidArray;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.common.function.ExceptionBiFunction;
import org.wildfly.httpclient.common.ContentType;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpServiceConfig;
import org.wildfly.transaction.client.ImportResult;
import org.wildfly.transaction.client.LocalTransaction;
import org.wildfly.transaction.client.LocalTransactionContext;

import javax.transaction.xa.Xid;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.function.Function;

/**
 * Utility class providing factory methods for creating server-side handlers of Remote TXN over HTTP protocol.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ServerHandlers {

    private final LocalTransactionContext ctx;
    private final Function<LocalTransaction, Xid> xidResolver;
    private final HttpServiceConfig config;

    private ServerHandlers(final LocalTransactionContext ctx, final Function<LocalTransaction, Xid> xidResolver, final HttpServiceConfig config) {
        this.ctx = ctx;
        this.xidResolver = xidResolver;
        this.config = config;
    }

    static ServerHandlers newInstance(final LocalTransactionContext ctx, final Function<LocalTransaction, Xid> xidResolver, final HttpServiceConfig config) {
        return new ServerHandlers(ctx, xidResolver, config);
    }

    HttpHandler handlerOf(final RequestType requestType) {
        switch (requestType) {
            case UT_BEGIN:
                return new BeginHandler(ctx, config, xidResolver);
            case UT_COMMIT:
                return new UTCommitHandler(ctx, config);
            case UT_ROLLBACK:
                return new UTRollbackHandler(ctx, config);
            case XA_BEFORE_COMPLETION:
                return new XABeforeCompletionHandler(ctx, config);
            case XA_COMMIT:
                return new XACommitHandler(ctx, config);
            case XA_FORGET:
                return new XAForgetHandler(ctx, config);
            case XA_PREPARE:
                return new XAPrepHandler(ctx, config);
            case XA_RECOVER:
                return new XARecoveryHandler(ctx, config);
            case XA_ROLLBACK:
                return new XARollbackHandler(ctx, config);
            default:
                throw new IllegalStateException();
        }

    }

    private abstract static class ValidatingTransactionHandler implements HttpHandler {
        protected final LocalTransactionContext ctx;
        protected final Function<LocalTransaction, Xid> xidResolver;
        protected final HttpServiceConfig config;

        private ValidatingTransactionHandler(final LocalTransactionContext ctx, final HttpServiceConfig config) {
            this(ctx, null, config);
        }

        private ValidatingTransactionHandler(final LocalTransactionContext ctx, final Function<LocalTransaction, Xid> xidResolver, final HttpServiceConfig config) {
            this.ctx = ctx;
            this.xidResolver = xidResolver;
            this.config = config;
        }

        protected abstract boolean isValidRequest(HttpServerExchange exchange);
        protected abstract void processRequest(HttpServerExchange exchange);

        @Override
        public final void handleRequest(final HttpServerExchange exchange) throws Exception {
            if (isValidRequest(exchange)) {
                processRequest(exchange);
            }
        }
    }

    private abstract static class AbstractTransactionHandler extends ValidatingTransactionHandler {
        private AbstractTransactionHandler(final LocalTransactionContext ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected boolean isValidRequest(final HttpServerExchange exchange) {
            final ContentType contentType = ContentType.parse(exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE));
            if (contentType == null || contentType.getVersion() != 1 || !contentType.getType().equals(XID.getType())) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s has incorrect or missing content type", exchange);
                return false;
            }
            return true;
        }

        @Override
        protected void processRequest(final HttpServerExchange exchange) {
            try {
                final HttpMarshallerFactory httpMarshallerFactory = config.getHttpUnmarshallerFactory(exchange);
                final Unmarshaller unmarshaller = httpMarshallerFactory.createUnmarshaller();
                final InputStream is = exchange.getInputStream();
                Xid simpleXid;
                try (ByteInput in = byteInputOf(is)) {
                    unmarshaller.start(in);
                    simpleXid = deserializeXid(unmarshaller);
                    unmarshaller.finish();
                }

                final ImportResult<LocalTransaction> transaction = ctx.findOrImportTransaction(simpleXid, 0);
                transaction.getTransaction().performFunction((ExceptionBiFunction<ImportResult<LocalTransaction>, HttpServerExchange, Void, Exception>) (o, exchange2) -> {
                    handleImpl(exchange2, o);
                    return null;
                }, transaction, exchange);
            } catch (Exception e) {
                sendException(exchange, config, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }

        protected abstract void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> localTransactionImportResult) throws Exception;
    }

    private static final class BeginHandler extends ValidatingTransactionHandler {
        private BeginHandler(final LocalTransactionContext ctx, final HttpServiceConfig config, final Function<LocalTransaction, Xid> xidResolver) {
            super(ctx, xidResolver, config);
        }

        @Override
        protected boolean isValidRequest(final HttpServerExchange exchange) {
            final String timeoutString = exchange.getRequestHeaders().getFirst(TIMEOUT);
            if (timeoutString == null) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, TIMEOUT);
                return false;
            }
            return true;
        }

        @Override
        protected void processRequest(final HttpServerExchange exchange) {
            try {
                final String timeoutString = exchange.getRequestHeaders().getFirst(TIMEOUT);
                final Integer timeout = Integer.parseInt(timeoutString);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, NEW_TRANSACTION.toString());
                final LocalTransaction transaction = ctx.beginTransaction(timeout);
                final Xid xid = xidResolver.apply(transaction);

                final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                Marshaller marshaller = config.getHttpMarshallerFactory(exchange).createMarshaller();
                try (ByteOutput out = byteOutputOf(baos)) {
                    marshaller.start(out);
                    serializeXid(marshaller, xid);
                    marshaller.finish();
                }
                exchange.getResponseSender().send(ByteBuffer.wrap(baos.toByteArray()));
            } catch (Exception e) {
                sendException(exchange, config, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    private static final class XARecoveryHandler extends ValidatingTransactionHandler {
        private XARecoveryHandler(final LocalTransactionContext ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected boolean isValidRequest(HttpServerExchange exchange) {
            String flagsStringString = exchange.getRequestHeaders().getFirst(RECOVERY_FLAGS);
            if (flagsStringString == null) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, RECOVERY_FLAGS);
                return false;
            }
            String parentName = exchange.getRequestHeaders().getFirst(RECOVERY_PARENT_NAME);
            if (parentName == null) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, RECOVERY_PARENT_NAME);
                return false;
            }
            return true;
        }

        @Override
        protected void processRequest(HttpServerExchange exchange) {
            try {
                final String flagsStringString = exchange.getRequestHeaders().getFirst(RECOVERY_FLAGS);
                final int flags = Integer.parseInt(flagsStringString);
                final String parentName = exchange.getRequestHeaders().getFirst(RECOVERY_PARENT_NAME);
                final Xid[] recoveryList = ctx.getRecoveryInterface().recover(flags, parentName);

                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final ByteOutput byteOutput = byteOutputOf(out);
                try (byteOutput) {
                    Marshaller marshaller = config.getHttpMarshallerFactory(exchange).createMarshaller();
                    marshaller.start(byteOutput);
                    serializeXidArray(marshaller, recoveryList);
                    marshaller.finish();
                }
                exchange.getResponseSender().send(ByteBuffer.wrap(out.toByteArray()));
            } catch (Exception e) {
                sendException(exchange, config, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    private static final class UTRollbackHandler extends AbstractTransactionHandler {
        private UTRollbackHandler(final LocalTransactionContext ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getTransaction().rollback();
        }
    }

    private static final class UTCommitHandler extends AbstractTransactionHandler {
        private UTCommitHandler(final LocalTransactionContext ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getTransaction().commit();
        }
    }

    private static final class XABeforeCompletionHandler extends AbstractTransactionHandler {
        private XABeforeCompletionHandler(final LocalTransactionContext ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().beforeCompletion();
        }
    }

    private static final class XAForgetHandler extends AbstractTransactionHandler {
        private XAForgetHandler(final LocalTransactionContext ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().forget();
        }
    }

    private static final class XAPrepHandler extends AbstractTransactionHandler {
        private XAPrepHandler(final LocalTransactionContext ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().prepare();
        }
    }

    private static final class XARollbackHandler extends AbstractTransactionHandler {
        private XARollbackHandler(final LocalTransactionContext ctx, final HttpServiceConfig  config) {
            super(ctx, config);
        }

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().rollback();
        }
    }

    private static final class XACommitHandler extends AbstractTransactionHandler {
        private XACommitHandler(final LocalTransactionContext ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            Deque<String> opc = exchange.getQueryParameters().get("opc");
            boolean onePhase = false;
            if (opc != null && !opc.isEmpty()) {
                onePhase = Boolean.parseBoolean(opc.poll());
            }
            transaction.getControl().commit(onePhase);
        }
    }
}
