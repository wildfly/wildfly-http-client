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

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import javax.transaction.xa.Xid;

import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HandlerVersion;
import org.wildfly.httpclient.common.VersionedHttpHandler;
import org.wildfly.transaction.client.SimpleXid;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

/**
 * Undertow HTTP handler that is responsible for initial parsing of EJB over HTTP messages
 *
 * @author Stuart Douglas
 */
public abstract class RemoteHTTPHandler extends VersionedHttpHandler {
    private final ExecutorService executorService;

    private static final AttachmentKey<ExecutorService> EXECUTOR = AttachmentKey.create(ExecutorService.class);

    public RemoteHTTPHandler(HandlerVersion version, ExecutorService executorService) {
        super(version);
        this.executorService = executorService;
    }

    @Override
    public final void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            if (executorService == null) {
                exchange.dispatch(this);
            } else {
                exchange.putAttachment(EXECUTOR, executorService);
                exchange.dispatch(executorService, this);
            }
            return;
        } else if (executorService != null && exchange.getAttachment(EXECUTOR) == null) {
            exchange.putAttachment(EXECUTOR, executorService);
            exchange.dispatch(executorService, this);
            return;
        }
        exchange.startBlocking();
        handleInternal(exchange);
    }

    protected abstract void handleInternal(HttpServerExchange exchange) throws Exception;

    protected ReceivedTransaction readTransaction(Unmarshaller unmarshaller) throws IOException {
        int type = unmarshaller.readByte();
        if (type == 0) {
            return null;
        } else if (type == 1 || type == 2) {
            int formatId = unmarshaller.readInt();
            int len = unmarshaller.readInt();
            byte[] globalId = new byte[len];
            unmarshaller.readFully(globalId);
            len = unmarshaller.readInt();
            byte[] branchId = new byte[len];
            unmarshaller.readFully(branchId);
            SimpleXid simpleXid = new SimpleXid(formatId, globalId, branchId);
            if (type == 2) {
                return new ReceivedTransaction(simpleXid, unmarshaller.readInt(), true);
            }
            return new ReceivedTransaction(simpleXid, 0, false);
        } else {
            throw EjbHttpClientMessages.MESSAGES.invalidTransactionType(type);
        }
    }

    static class ReceivedTransaction {
        final Xid xid;
        final int remainingTime;
        final boolean outflowed;

        ReceivedTransaction(Xid xid, int remainingTime, boolean outflowed) {
            this.xid = xid;
            this.remainingTime = remainingTime;
            this.outflowed = outflowed;
        }

        public Xid getXid() {
            return xid;
        }

        public int getRemainingTime() {
            return remainingTime;
        }

        public boolean isOutflowed() {
            return outflowed;
        }
    }
}
