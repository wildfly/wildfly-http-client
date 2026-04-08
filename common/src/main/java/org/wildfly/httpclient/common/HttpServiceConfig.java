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
package org.wildfly.httpclient.common;

import io.undertow.server.HttpHandler;

import java.util.function.Function;

/**
 * Mode configuration for http services.
 * <p>
 * The http services are internal server services responsible for handling client requests, and they are
 * simple pojos conventionally named Http*Service.
 *
 * @author Flavia Rainone
 */
public enum HttpServiceConfig {

    /**
     * Default configuration. Used by both EE namespace interoperable and non-interoperable servers
     */
    DEFAULT (EENamespaceInteroperability::createInteroperabilityHandler);

    /**
     * Returns the default configuration.
     *
     * @return the configuration for http services
     */
    public static HttpServiceConfig getInstance() {
        return DEFAULT;
    }

    private final Function<HttpHandler, HttpHandler> handlerWrapper;

    HttpServiceConfig(Function<HttpHandler, HttpHandler> handlerWrapper) {
        this.handlerWrapper = handlerWrapper;
    }

    /**
     * Wraps the http service handler. Should be applied to all http handlers configured by
     * a http service.
     *
     * @param handler responsible for handling the HTTP service requests directed to a specific
     *                URI
     * @return the HttpHandler that should be provided to Undertow and associated with the HTTP
     *         service URI. The resulting handler is a wrapper that will add any necessary actions
     *         before invoking the inner {@code handler}.
     */
    public HttpHandler wrap(HttpHandler handler) {
        return handlerWrapper.apply(handler);
    }

}