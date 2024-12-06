/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022 Red Hat, Inc., and individual contributors
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

import io.undertow.conduits.GzipStreamSourceConduit;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.AllowedMethodsHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.server.handlers.encoding.RequestEncodingHandler;
import io.undertow.util.Headers;
import org.jboss.ejb.server.Association;
import org.wildfly.httpclient.common.HttpServiceConfig;
import org.wildfly.transaction.client.LocalTransactionContext;

import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * HTTP service that handles EJB calls.
 *
 * @author Stuart Douglas
 * @author Flavia Rainone
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class HttpRemoteEjbService {
    private final HttpServiceConfig config;
    private final ServerHandlers serverHandlers;

    public HttpRemoteEjbService(Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext,
                                Function<String, Boolean> classResolverFilter) {
        this(HttpServiceConfig.getInstance(), association, executorService, localTransactionContext, classResolverFilter);
    }

    private HttpRemoteEjbService(HttpServiceConfig config, Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext,
                                 Function<String, Boolean> classResolverFilter) {
        this.config = config;
        this.serverHandlers = ServerHandlers.newInstance(config, association, executorService, localTransactionContext, classResolverFilter);
    }

    public HttpHandler createHttpHandler() {
        PathHandler pathHandler = new PathHandler();
        for (RequestType requestType : RequestType.values()) {
            registerHandler(pathHandler, requestType);
        }

        EncodingHandler encodingHandler = new EncodingHandler(pathHandler, new ContentEncodingRepository().addEncodingHandler(Headers.GZIP.toString(), new GzipEncodingProvider(), 1));
        RequestEncodingHandler requestEncodingHandler = new RequestEncodingHandler(encodingHandler);
        requestEncodingHandler.addEncoding(Headers.GZIP.toString(), GzipStreamSourceConduit.WRAPPER);
        return config.wrap(requestEncodingHandler);
    }

    private void registerHandler(final PathHandler pathHandler, final RequestType requestType) {
        pathHandler.addPrefixPath(requestType.getPath(), new AllowedMethodsHandler(serverHandlers.handlerOf(requestType), requestType.getMethod()));
    }
}
