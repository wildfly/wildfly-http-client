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

package org.wildfly.httpclient.naming;

import static org.wildfly.httpclient.naming.Constants.NAME_PATH_PARAMETER;

import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import org.wildfly.httpclient.common.ElytronIdentityHandler;
import org.wildfly.httpclient.common.HttpServiceConfig;

import javax.naming.Context;
import java.util.function.Function;

/**
 * HTTP service that handles naming invocations.
 *
 * @author Stuart Douglas
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class HttpRemoteNamingService {
    private final HttpServiceConfig httpServiceConfig;
    private final ServerHandlers serverHandlers;

    public HttpRemoteNamingService(final Context localContext, final Function<String, Boolean> classResolverFilter) {
        this (localContext, classResolverFilter, HttpServiceConfig.getInstance());
    }

    private HttpRemoteNamingService(final Context localContext, final Function<String, Boolean> classResolverFilter, final HttpServiceConfig httpServiceConfig) {
        this.httpServiceConfig = httpServiceConfig;
        this.serverHandlers = ServerHandlers.newInstance(localContext, classResolverFilter, httpServiceConfig);
    }

    public HttpHandler createHandler() {
        RoutingHandler routingHandler = new RoutingHandler();
        for (RequestType requestType : RequestType.values()) {
            registerHandler(routingHandler, requestType);
        }

        return httpServiceConfig.wrap(new BlockingHandler(new ElytronIdentityHandler(routingHandler)));
    }

    private void registerHandler(final RoutingHandler routingHandler, final RequestType requestType) {
        final String nameParamPathSuffix = "/{" + NAME_PATH_PARAMETER + "}";
        routingHandler.add(requestType.getMethod(), requestType.getPath() + nameParamPathSuffix, serverHandlers.handlerOf(requestType));
    }
}
