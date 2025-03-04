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

import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import org.wildfly.httpclient.common.ElytronIdentityHandler;
import org.wildfly.httpclient.common.HttpServiceConfig;
import org.wildfly.transaction.client.LocalTransaction;
import org.wildfly.transaction.client.LocalTransactionContext;

import javax.transaction.xa.Xid;
import java.util.function.Function;

/**
 * @author Stuart Douglas
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class HttpRemoteTransactionService {
    private final HttpServiceConfig config;
    private final ServerHandlers serverHandlers;

    public HttpRemoteTransactionService(final LocalTransactionContext transactionContext, final Function<LocalTransaction, Xid> xidResolver) {
        this(HttpServiceConfig.getInstance(), transactionContext, xidResolver);
    }

    private  HttpRemoteTransactionService(final HttpServiceConfig config, final LocalTransactionContext transactionContext, final Function<LocalTransaction, Xid> xidResolver) {
        this.config = config;
        this.serverHandlers = ServerHandlers.newInstance(config, transactionContext, xidResolver);
    }

    public HttpHandler createHandler() {
        RoutingHandler routingHandler = new RoutingHandler();
        for (RequestType requestType : RequestType.values()) {
            registerHandler(routingHandler, requestType);
        }

        return config.wrap(new BlockingHandler(new ElytronIdentityHandler(routingHandler)));
    }

    private void registerHandler(final RoutingHandler routingHandler, final RequestType requestType) {
        routingHandler.add(requestType.getMethod(), requestType.getPath(), serverHandlers.handlerOf(requestType));
    }
}
