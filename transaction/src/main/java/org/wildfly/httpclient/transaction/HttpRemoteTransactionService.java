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

package org.wildfly.httpclient.transaction;

import static org.wildfly.httpclient.transaction.Constants.EXCEPTION;
import static org.wildfly.httpclient.transaction.Constants.NEW_TRANSACTION;
import static org.wildfly.httpclient.transaction.Constants.RECOVERY_FLAGS;
import static org.wildfly.httpclient.transaction.Constants.RECOVERY_PARENT_NAME;
import static org.wildfly.httpclient.transaction.Constants.TIMEOUT;
import static org.wildfly.httpclient.transaction.Constants.XID;
import static org.wildfly.httpclient.transaction.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.transaction.Serializer.serializeThrowable;
import static org.wildfly.httpclient.transaction.Serializer.deserializeXid;
import static org.wildfly.httpclient.transaction.Serializer.serializeXid;
import static org.wildfly.httpclient.transaction.Serializer.serializeXidArray;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.common.function.ExceptionBiFunction;
import org.wildfly.httpclient.common.ContentType;
import org.wildfly.httpclient.common.ElytronIdentityHandler;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpServiceConfig;
import org.wildfly.httpclient.common.NoFlushByteOutput;
import org.wildfly.transaction.client.ImportResult;
import org.wildfly.transaction.client.LocalTransaction;
import org.wildfly.transaction.client.LocalTransactionContext;

import javax.transaction.xa.Xid;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.function.Function;

/**
 * @author Stuart Douglas
 */
public class HttpRemoteTransactionService {

    private final LocalTransactionContext transactionContext;
    private final Function<LocalTransaction, Xid> xidResolver;
    private final HttpServiceConfig httpServiceConfig;

    public HttpRemoteTransactionService(LocalTransactionContext transactionContext, Function<LocalTransaction, Xid> xidResolver) {
        this(transactionContext, xidResolver, HttpServiceConfig.getInstance());
    }

    public HttpRemoteTransactionService(LocalTransactionContext transactionContext, Function<LocalTransaction, Xid> xidResolver, HttpServiceConfig httpServiceConfig) {
        this.transactionContext = transactionContext;
        this.xidResolver = xidResolver;
        this.httpServiceConfig = httpServiceConfig;
    }

    public HttpHandler createHandler() {
        RoutingHandler routingHandler = new RoutingHandler();
        for (RequestType requestType : RequestType.values()) {
            registerHandler(routingHandler, requestType);
        }

        return httpServiceConfig.wrap(new BlockingHandler(new ElytronIdentityHandler(routingHandler)));
    }

    private void registerHandler(final RoutingHandler routingHandler, final RequestType requestType) {
        routingHandler.add(requestType.getMethod(), requestType.getPath(), newInvocationHandler(requestType));
    }

    private HttpHandler newInvocationHandler(final RequestType requestType) {
        switch (requestType) {
            case UT_BEGIN: return new BeginHandler();
            case UT_COMMIT: return new UTCommitHandler();
            case UT_ROLLBACK: return new UTRollbackHandler();
            case XA_BEFORE_COMPLETION: return new XABeforeCompletionHandler();
            case XA_COMMIT: return new XACommitHandler();
            case XA_FORGET: return new XAForgetHandler();
            case XA_PREPARE: return new XAPrepHandler();
            case XA_RECOVER: return new XARecoveryHandler();
            case XA_ROLLBACK: return new XARollbackHandler();
            default: throw new IllegalStateException();
        }
    }

    abstract class AbstractTransactionHandler implements HttpHandler {

        @Override
        public final void handleRequest(HttpServerExchange exchange) throws Exception {
            ContentType contentType = ContentType.parse(exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE));
            if (contentType == null || contentType.getVersion() != 1 || !contentType.getType().equals(XID.getType())) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s has incorrect or missing content type", exchange);
                return;
            }

            try {
                HttpMarshallerFactory httpMarshallerFactory = httpServiceConfig.getHttpUnmarshallerFactory(exchange);
                Unmarshaller unmarshaller = httpMarshallerFactory.createUnmarshaller();
                Xid simpleXid;
                try (ByteInput in = new InputStreamByteInput(exchange.getInputStream())) {
                    unmarshaller.start(in);
                    simpleXid = deserializeXid(unmarshaller);
                    unmarshaller.finish();
                }
                ImportResult<LocalTransaction> transaction = transactionContext.findOrImportTransaction(simpleXid, 0);
                transaction.getTransaction().performFunction((ExceptionBiFunction<ImportResult<LocalTransaction>, HttpServerExchange, Void, Exception>) (o, exchange2) -> {
                    handleImpl(exchange2, o);
                    return null;
                }, transaction, exchange);
            } catch (Exception e) {
                internalSendException(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }

        protected abstract void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> localTransactionImportResult) throws Exception;
    }

    class BeginHandler implements HttpHandler {

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            try {
                String timeoutString = exchange.getRequestHeaders().getFirst(TIMEOUT);
                if (timeoutString == null) {
                    exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                    HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, TIMEOUT);
                    return;
                }
                final Integer timeout = Integer.parseInt(timeoutString);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, NEW_TRANSACTION.toString());
                final LocalTransaction transaction = transactionContext.beginTransaction(timeout);
                final Xid xid = xidResolver.apply(transaction);
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final ByteOutput byteOutput = byteOutputOf(out);
                try (byteOutput) {
                    Marshaller marshaller = httpServiceConfig.getHttpMarshallerFactory(exchange).createMarshaller();
                    marshaller.start(byteOutput);
                    serializeXid(marshaller, xid);
                    marshaller.finish();
                }
                exchange.getResponseSender().send(ByteBuffer.wrap(out.toByteArray()));
            } catch (Exception e) {
                internalSendException(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    class XARecoveryHandler implements HttpHandler {

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            try {
                String flagsStringString = exchange.getRequestHeaders().getFirst(RECOVERY_FLAGS);
                if (flagsStringString == null) {
                    exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                    HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, RECOVERY_FLAGS);
                    return;
                }
                final int flags = Integer.parseInt(flagsStringString);
                String parentName = exchange.getRequestHeaders().getFirst(RECOVERY_PARENT_NAME);
                if (parentName == null) {
                    exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                    HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, RECOVERY_PARENT_NAME);
                    return;
                }

                final Xid[] recoveryList = transactionContext.getRecoveryInterface().recover(flags, parentName);
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteOutput byteOutput = new NoFlushByteOutput(byteOutputOf(out));
                byte[] data;
                try (byteOutput) {
                    Marshaller marshaller = httpServiceConfig.getHttpMarshallerFactory(exchange).createMarshaller();
                    marshaller.start(byteOutput);
                    serializeXidArray(marshaller, recoveryList);
                    marshaller.finish();
                    data = out.toByteArray();
                }
                exchange.getResponseSender().send(ByteBuffer.wrap(data));
            } catch (Exception e) {
                internalSendException(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    class UTRollbackHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getTransaction().rollback();
        }
    }

    class UTCommitHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getTransaction().commit();
        }
    }

    class XABeforeCompletionHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().beforeCompletion();
        }
    }

    class XAForgetHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().forget();
        }
    }

    class XAPrepHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().prepare();
        }
    }

    class XARollbackHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().rollback();
        }
    }

    class XACommitHandler extends AbstractTransactionHandler {

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

    private void internalSendException(HttpServerExchange exchange, int status, Throwable e) {
        try {
            exchange.setStatusCode(status);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, EXCEPTION.toString());

            final Marshaller marshaller = httpServiceConfig.getHttpMarshallerFactory(exchange).createMarshaller();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] data;
            try (ByteOutput out = new NoFlushByteOutput(byteOutputOf(outputStream))) {
                marshaller.start(out);
                serializeThrowable(marshaller, e);
                marshaller.finish();
                data = outputStream.toByteArray();
            }
            exchange.getResponseSender().send(ByteBuffer.wrap(data));
        } catch (IOException e1) {
            HttpRemoteTransactionMessages.MESSAGES.debugf(e, "Failed to write exception");
        }
    }

    @Deprecated
    public static void sendException(HttpServerExchange exchange, int status, Throwable e) {
        try {
            exchange.setStatusCode(status);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, EXCEPTION.toString());

            final Marshaller marshaller = HttpServiceConfig.getInstance().getHttpMarshallerFactory(exchange).createMarshaller();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] data;
            try (ByteOutput out = new NoFlushByteOutput(byteOutputOf(outputStream))) {
                marshaller.start(out);
                serializeThrowable(marshaller, e);
                marshaller.finish();
                data = outputStream.toByteArray();
            }
            exchange.getResponseSender().send(ByteBuffer.wrap(data));
        } catch (IOException e1) {
            HttpRemoteTransactionMessages.MESSAGES.debugf(e, "Failed to write exception");
        }
    }
}
