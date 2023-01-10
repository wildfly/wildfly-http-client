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
     * Default configuration. Used by both EE namespace interoperable and non-interoperable servers
     */
    DEFAULT (EENamespaceInteroperability::createInteroperabilityHandler,
            EENamespaceInteroperability::createInteroperabilityHandler,
            EENamespaceInteroperability.getHttpMarshallerFactoryProvider());

    /**
     * Returns the default configuration.
     *
     * @return the configuration for http services
     */
    public static HttpServiceConfig getInstance() {
        return DEFAULT;
    }

    private final Function<HttpHandler, HttpHandler> singleHandlerWrapper;
    private final Function<HttpHandler[], HttpHandler> multiVersionedHandlerWrapper;
    private final HttpMarshallerFactoryProvider marshallerFactoryProvider;

    HttpServiceConfig(Function<HttpHandler, HttpHandler> singleHandlerWrapper, Function<HttpHandler[], HttpHandler> multiVersionedHandlerWrapper, HttpMarshallerFactoryProvider marshallerFactoryProvider) {
        this.singleHandlerWrapper = singleHandlerWrapper;
        this.multiVersionedHandlerWrapper = multiVersionedHandlerWrapper;
        this.marshallerFactoryProvider = marshallerFactoryProvider;
    }

    /**
     * Wraps the http service handler. Should be applied to all http handlers configured by
     * a http service.
     * <br>
     * The resulting handler is compatible with EE namespace interoperability and accepts
     * {@code javax} namespace requests at the path prefix {@code "/v1"}, while {@code jakarta}
     * namespace requests are received at the path prefix {@code "/v2"}. Both requests are
     * forwarded to {@code handler}, but in case of {@code "/v1"} the {@code javax} namespace
     * is converted to {@code jakarta}.
     *
     * @param handler responsible for handling the HTTP service requests directed to a specific
     *                URI. This handler must operate on {@code jakarta} namespace.
     * @return the HttpHandler that should be provided to Undertow and associated with the HTTP
     *         service URI. The resulting handler is a wrapper that will add any necessary actions
     *         before invoking the inner {@code handler}.
     */
    public HttpHandler wrap(HttpHandler handler) {
        return singleHandlerWrapper.apply(handler);
    }

    /**
     * Wraps a multi-version series of handlers. Each handler represents a version of the same operation
     * provided by a HTTP service.
     * <br>
     * The resulting handler receives {@code javax} namespace requests at the path prefix {@code "/v1"},
     * translates them to {@code jakarta namespace} and forwards them to {@code multiVersionedHandlers[0]}.
     * The subsequent handlers in the {@code multiVersionedHandlers} array are mapped to path {@code "/v2"},
     * {@code "/v3"} and so on.
     * <br>
     * Use this method when the http service supports more than one version of an HTTP Handler. This will be
     * the case as http handlers evolve to incorporate new features and fixes that change the particular
     * protocol format used by the HTTP handler for the specific operation it represents.
     *
     * @param multiVersionedHandlers responsible for handling the HTTP service requests directed to a specific
     *                               URI. The handlers must be in crescent protocol number order, i.e., in the
     *                               sequence corresponding to {@code "/v2"}, {@code "/v3}, {@code "/v4"}. All
     *                               the handlers must be compatible with requests in the Jakarta namespace.
     *
     * @return the HttpHandler that should be provided to Undertow and associated with the HTTP
     *         service URI. The resulting handler is a wrapper that will take care of protocol
     *         versioning to invoke the appropriate handler
     */
    public HttpHandler wrap(HttpHandler... multiVersionedHandlers) {
        return multiVersionedHandlerWrapper.apply(multiVersionedHandlers);
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