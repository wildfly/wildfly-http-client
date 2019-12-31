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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.jboss.ejb.server.Association;
import org.jboss.ejb.server.CancelHandle;
import org.wildfly.transaction.client.LocalTransactionContext;
import io.undertow.conduits.GzipStreamSourceConduit;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.AllowedMethodsHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.server.handlers.encoding.RequestEncodingHandler;
import io.undertow.util.Headers;
import io.undertow.util.Methods;

/**
 * @author Stuart Douglas
 */
public class EjbHttpService {

    static final String JSESSIONID = "JSESSIONID";

    private final Association association;
    private final ExecutorService executorService;
    private final LocalTransactionContext localTransactionContext;

    private final Map<InvocationIdentifier, CancelHandle> cancellationFlags = new ConcurrentHashMap<>();

    public EjbHttpService(Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext) {
        this.association = association;
        this.executorService = executorService;
        this.localTransactionContext = localTransactionContext;
    }

    public HttpHandler createHttpHandler() {
        PathHandler pathHandler = new PathHandler();
        pathHandler.addPrefixPath("/v1/invoke", new AllowedMethodsHandler(new HttpInvocationHandler(association, executorService, localTransactionContext, cancellationFlags), Methods.POST))
                .addPrefixPath("/v1/open", new AllowedMethodsHandler(new HttpSessionOpenHandler(association, executorService, localTransactionContext), Methods.POST))
                .addPrefixPath("/v1/cancel", new AllowedMethodsHandler(new HttpCancelHandler(association, executorService, localTransactionContext, cancellationFlags), Methods.DELETE))
                .addPrefixPath("/v1/discover", new AllowedMethodsHandler(new HttpDiscoveryHandler(executorService, association), Methods.GET));
        EncodingHandler encodingHandler = new EncodingHandler(pathHandler, new ContentEncodingRepository().addEncodingHandler(Headers.GZIP.toString(), new GzipEncodingProvider(), 1));
        RequestEncodingHandler requestEncodingHandler = new RequestEncodingHandler(encodingHandler);
        requestEncodingHandler.addEncoding(Headers.GZIP.toString(), GzipStreamSourceConduit.WRAPPER);
        return requestEncodingHandler;
    }

}
