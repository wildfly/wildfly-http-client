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

import java.util.concurrent.ExecutorService;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

/**
 * Undertow HTTP handler that is responsible for initial parsing of EJB over HTTP messages
 *
 * @author Stuart Douglas
 */
abstract class RemoteHTTPHandler implements HttpHandler {

    private final ExecutorService executorService;

    private static final AttachmentKey<ExecutorService> EXECUTOR = AttachmentKey.create(ExecutorService.class);

    public RemoteHTTPHandler(ExecutorService executorService) {
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

}
