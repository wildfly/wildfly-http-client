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
import io.undertow.server.HttpServerExchange;

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
     * Default configuration.
     */
    DEFAULT (Function.identity(), HttpMarshallerFactoryProvider.getDefaultHttpMarshallerFactoryProvider()),
    /**
     * Configuration for running the HTTP remoting layer in EE interoperable mode, where this
     * Java instance can interoperate with Javax EE clients and servers.
     */
    EE_INTEROPERABLE_MODE (EEInteroperability::wrap, EEInteroperability.getHttpMarshallerFactoryProvider());

    /**
     * Returns the right configuration according to the value of
     * {@link EEInteroperability#EE_INTEROPERABLE_MODE}.
     *
     * @return the configuration for http services
     */
    public static HttpServiceConfig getInstance() {
        if (EEInteroperability.EE_INTEROPERABLE_MODE) {
            return EE_INTEROPERABLE_MODE;
        }
        return DEFAULT;
    }

    private final Function<HttpHandler, HttpHandler> handlerWrapper;
    private final HttpMarshallerFactoryProvider marshallerFactoryProvider;

    HttpServiceConfig(Function<HttpHandler, HttpHandler> handlerWrapper, HttpMarshallerFactoryProvider marshallerFactoryProvider) {
        this.handlerWrapper = handlerWrapper;
        this.marshallerFactoryProvider = marshallerFactoryProvider;
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

    /**
     * Returns the http marshaller factory that must be used for unmarshalling the objects
     * from service requests bytes.
     *
     * @param exchange the server exchange
     * @return the HTTP marshaller factory for unmarshalling server request objects
     */
    public HttpMarshallerFactory getHttpUnmarshallerFactory(HttpServerExchange exchange) {
        return marshallerFactoryProvider.getUnmarshallerFactory(exchange);
    }

    /**
     * Returns the http marshaller factory that must be used for marshalling the service
     * responses as bytes to be sent as a server response data.
     *
     * @param exchange the server exchange
     * @return the HTTP marshaller factory for marshalling server responses
     */
    public HttpMarshallerFactory getHttpMarshallerFactory(HttpServerExchange exchange) {
        return marshallerFactoryProvider.getMarshallerFactory(exchange);
    }
}