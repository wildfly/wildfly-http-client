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

import static java.lang.Boolean.parseBoolean;
import static io.undertow.util.Headers.CONTENT_TYPE;
import static io.undertow.util.StatusCodes.BAD_REQUEST;
import static io.undertow.util.StatusCodes.INTERNAL_SERVER_ERROR;
import static org.wildfly.httpclient.common.ByteInputs.byteInputOf;
import static org.wildfly.httpclient.common.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.common.HeadersHelper.getRequestHeader;
import static org.wildfly.httpclient.common.HeadersHelper.putResponseHeader;
import static org.wildfly.httpclient.transaction.Constants.NEW_TRANSACTION;
import static org.wildfly.httpclient.transaction.Constants.OPC_QUERY_PARAMETER;
import static org.wildfly.httpclient.transaction.Constants.RECOVERY_FLAGS;
import static org.wildfly.httpclient.transaction.Constants.RECOVERY_PARENT_NAME;
import static org.wildfly.httpclient.transaction.Constants.TIMEOUT;
import static org.wildfly.httpclient.transaction.Constants.XID;
import static org.wildfly.httpclient.transaction.Serializer.deserializeXid;
import static org.wildfly.httpclient.transaction.Serializer.serializeXid;
import static org.wildfly.httpclient.transaction.Serializer.serializeXidArray;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.common.function.ExceptionBiFunction;
import org.wildfly.httpclient.common.AbstractServerHttpHandler;
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

    private ServerHandlers(final HttpServiceConfig config, final LocalTransactionContext ctx, final Function<LocalTransaction, Xid> xidResolver) {
        this.config = config;
        this.ctx = ctx;
        this.xidResolver = xidResolver;
    }

    static ServerHandlers newInstance(final HttpServiceConfig config, final LocalTransactionContext ctx, final Function<LocalTransaction, Xid> xidResolver) {
        return new ServerHandlers(config, ctx, xidResolver);
    }

    HttpHandler handlerOf(final RequestType requestType) {
        switch (requestType) {
            case UT_BEGIN:
                return new BeginHandler(config, ctx, xidResolver);
            case UT_COMMIT:
                return new UTCommitHandler(config, ctx);
            case UT_ROLLBACK:
                return new UTRollbackHandler(config, ctx);
            case XA_BEFORE_COMPLETION:
                return new XABeforeCompletionHandler(config, ctx);
            case XA_COMMIT:
                return new XACommitHandler(config, ctx);
            case XA_FORGET:
                return new XAForgetHandler(config, ctx);
            case XA_PREPARE:
                return new XAPrepHandler(config, ctx);
            case XA_RECOVER:
                return new XARecoveryHandler(config, ctx);
            case XA_ROLLBACK:
                return new XARollbackHandler(config, ctx);
            default:
                throw new IllegalStateException();
        }

    }

    private abstract static class AbstractTransactionHandler extends AbstractServerHttpHandler {
        protected final LocalTransactionContext ctx;
        protected final Function<LocalTransaction, Xid> xidResolver;

        private AbstractTransactionHandler(final HttpServiceConfig config, final LocalTransactionContext ctx) {
            this(config, ctx, null);
        }

        private AbstractTransactionHandler(final HttpServiceConfig config, final LocalTransactionContext ctx, final Function<LocalTransaction, Xid> xidResolver) {
            super(config);
            this.ctx = ctx;
            this.xidResolver = xidResolver;
        }

        @Override
        protected boolean isValidRequest(final HttpServerExchange exchange) {
            final ContentType contentType = ContentType.parse(getRequestHeader(exchange, CONTENT_TYPE));
            if (contentType == null || contentType.getVersion() != 1 || !contentType.getType().equals(XID.getType())) {
                exchange.setStatusCode(BAD_REQUEST);
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
                sendException(exchange, INTERNAL_SERVER_ERROR, e);
            }
        }

        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> localTransactionImportResult) throws Exception {}
    }

    private static final class BeginHandler extends AbstractTransactionHandler {
        private BeginHandler(final HttpServiceConfig config, final LocalTransactionContext ctx, final Function<LocalTransaction, Xid> xidResolver) {
            super(config, ctx, xidResolver);
        }

        @Override
        protected boolean isValidRequest(final HttpServerExchange exchange) {
            final String timeoutString = getRequestHeader(exchange, TIMEOUT);
            if (timeoutString == null) {
                exchange.setStatusCode(BAD_REQUEST);
                HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, TIMEOUT);
                return false;
            }
            return true;
        }

        @Override
        protected void processRequest(final HttpServerExchange exchange) {
            try {
                final String timeoutString = getRequestHeader(exchange, TIMEOUT);
                final Integer timeout = Integer.parseInt(timeoutString);
                putResponseHeader(exchange, CONTENT_TYPE, NEW_TRANSACTION);
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
                sendException(exchange, INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    private static final class XARecoveryHandler extends AbstractTransactionHandler {
        private XARecoveryHandler(final HttpServiceConfig config, final LocalTransactionContext ctx) {
            super(config, ctx);
        }

        @Override
        protected boolean isValidRequest(final HttpServerExchange exchange) {
            String flagsStringString = getRequestHeader(exchange, RECOVERY_FLAGS);
            if (flagsStringString == null) {
                exchange.setStatusCode(BAD_REQUEST);
                HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, RECOVERY_FLAGS);
                return false;
            }
            String parentName = getRequestHeader(exchange, RECOVERY_PARENT_NAME);
            if (parentName == null) {
                exchange.setStatusCode(BAD_REQUEST);
                HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, RECOVERY_PARENT_NAME);
                return false;
            }
            return true;
        }

        @Override
        protected void processRequest(final HttpServerExchange exchange) {
            try {
                final String flagsStringString = getRequestHeader(exchange, RECOVERY_FLAGS);
                final int flags = Integer.parseInt(flagsStringString);
                final String parentName = getRequestHeader(exchange, RECOVERY_PARENT_NAME);
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
                sendException(exchange, INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    private static final class UTRollbackHandler extends AbstractTransactionHandler {
        private UTRollbackHandler(final HttpServiceConfig config, final LocalTransactionContext ctx) {
            super(config, ctx);
        }

        @Override
        protected void handleImpl(final HttpServerExchange exchange, final ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getTransaction().rollback();
        }
    }

    private static final class UTCommitHandler extends AbstractTransactionHandler {
        private UTCommitHandler(final HttpServiceConfig config, final LocalTransactionContext ctx) {
            super(config, ctx);
        }

        @Override
        protected void handleImpl(final HttpServerExchange exchange, final ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getTransaction().commit();
        }
    }

    private static final class XABeforeCompletionHandler extends AbstractTransactionHandler {
        private XABeforeCompletionHandler(final HttpServiceConfig config, final LocalTransactionContext ctx) {
            super(config, ctx);
        }

        @Override
        protected void handleImpl(final HttpServerExchange exchange, final ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().beforeCompletion();
        }
    }

    private static final class XAForgetHandler extends AbstractTransactionHandler {
        private XAForgetHandler(final HttpServiceConfig config, final LocalTransactionContext ctx) {
            super(config, ctx);
        }

        @Override
        protected void handleImpl(final HttpServerExchange exchange, final ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().forget();
        }
    }

    private static final class XAPrepHandler extends AbstractTransactionHandler {
        private XAPrepHandler(final HttpServiceConfig config, final LocalTransactionContext ctx) {
            super(config, ctx);
        }

        @Override
        protected void handleImpl(final HttpServerExchange exchange, final ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().prepare();
        }
    }

    private static final class XARollbackHandler extends AbstractTransactionHandler {
        private XARollbackHandler(final HttpServiceConfig config, final LocalTransactionContext ctx) {
            super(config, ctx);
        }

        @Override
        protected void handleImpl(final HttpServerExchange exchange, final ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().rollback();
        }
    }

    private static final class XACommitHandler extends AbstractTransactionHandler {
        private XACommitHandler(final HttpServiceConfig config, final LocalTransactionContext ctx) {
            super(config, ctx);
        }

        @Override
        protected void handleImpl(final HttpServerExchange exchange, final ImportResult<LocalTransaction> transaction) throws Exception {
            Deque<String> opc = exchange.getQueryParameters().get(OPC_QUERY_PARAMETER);
            boolean onePhase = false;
            if (opc != null && !opc.isEmpty()) {
                onePhase = parseBoolean(opc.poll());
            }
            transaction.getControl().commit(onePhase);
        }
    }
}
