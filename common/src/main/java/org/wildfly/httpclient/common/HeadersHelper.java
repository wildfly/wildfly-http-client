/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2024 Red Hat, Inc., and individual contributors
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

import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;

public final class HeadersHelper {

    private HeadersHelper() {
        // forbidden instantiation
    }

    public static void addRequestHeader(final ClientRequest request, final HttpString headerName, final String headerValue) {
        if (request == null || headerName == null) return;
        request.getRequestHeaders().add(headerName, headerValue);
    }

    public static boolean containsRequestHeader(final ClientRequest request, final HttpString headerName) {
        if (request == null || headerName == null) throw new IllegalArgumentException();
        return request.getRequestHeaders().contains(headerName);
    }

    public static String getRequestHeader(final ClientRequest request, final HttpString headerName) {
        if (request == null || headerName == null) throw new IllegalArgumentException();
        return request.getRequestHeaders().getFirst(headerName);
    }

    public static String getResponseHeader(final ClientResponse response, final HttpString headerName) {
        if (response == null || headerName == null) throw new IllegalArgumentException();
        return response.getResponseHeaders().getFirst(headerName);
    }

    public static HeaderValues getResponseHeaders(final ClientResponse response, final HttpString headerName) {
        if (response == null || headerName == null) throw new IllegalArgumentException();
        return response.getResponseHeaders().get(headerName);
    }

    public static void putRequestHeader(final ClientRequest request, final HttpString headerName, final ContentType headerValue) {
        if (request == null || headerName == null || headerValue == null) throw new IllegalArgumentException();
        request.getRequestHeaders().put(headerName, headerValue.toString());
    }

    public static void putRequestHeader(final ClientRequest request, final HttpString headerName, final HttpString headerValue) {
        if (request == null || headerName == null || headerValue == null) throw new IllegalArgumentException();
        request.getRequestHeaders().put(headerName, headerValue.toString());
    }

    public static void putRequestHeader(final ClientRequest request, final HttpString headerName, final String headerValue) {
        if (request == null || headerName == null || headerValue == null) throw new IllegalArgumentException();
        request.getRequestHeaders().put(headerName, headerValue);
    }

    public static void addResponseHeader(final HttpServerExchange exchange, final HttpString headerName, final String headerValue) {
        if (exchange == null || headerName == null || headerValue == null) throw new IllegalArgumentException();
        exchange.getResponseHeaders().add(headerName, headerValue);
    }

    public static String getRequestHeader(final HttpServerExchange exchange, final HttpString headerName) {
        if (exchange == null || headerName == null) throw new IllegalArgumentException();
        return exchange.getRequestHeaders().getFirst(headerName);
    }

    public static void putResponseHeader(final HttpServerExchange exchange, final HttpString headerName, final String headerValue) {
        if (exchange == null || headerName == null || headerValue == null) throw new IllegalArgumentException();
        exchange.getResponseHeaders().put(headerName, headerValue);
    }

    public static void putResponseHeader(final HttpServerExchange exchange, final HttpString headerName, final ContentType headerValue) {
        if (exchange == null || headerName == null || headerValue == null) throw new IllegalArgumentException();
        exchange.getResponseHeaders().put(headerName, headerValue.toString());
    }

}
