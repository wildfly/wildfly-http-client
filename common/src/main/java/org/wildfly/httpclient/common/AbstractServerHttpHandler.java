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

package org.wildfly.httpclient.common;

import static io.undertow.util.Headers.CONTENT_TYPE;
import static io.undertow.util.StatusCodes.BAD_REQUEST;
import static io.undertow.util.StatusCodes.INTERNAL_SERVER_ERROR;
import static org.wildfly.httpclient.common.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.common.HeadersHelper.getRequestHeader;
import static org.wildfly.httpclient.common.HeadersHelper.putResponseHeader;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;

import java.io.OutputStream;
import java.util.Deque;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public abstract class AbstractServerHttpHandler implements HttpHandler {

    protected final HttpServiceConfig config;

    protected AbstractServerHttpHandler(final HttpServiceConfig config) {
        this.config = config;
    }

    protected abstract void processRequest(final HttpServerExchange exchange) throws Exception;

    @Override
    public final void handleRequest(final HttpServerExchange exchange) {
        try {
            if (!containsRequiredContentType(exchange)) return;
            if (!containsRequiredRequestHeaders(exchange)) return;
            if (!containsRequiredQueryParameters(exchange)) return;
            processRequest(exchange);
        } catch (Throwable e) {
            sendException(exchange, INTERNAL_SERVER_ERROR, e);
        }
    }

    private boolean containsRequiredContentType(final HttpServerExchange exchange) {
        final ContentType expectedCT = getRequiredContentType();
        if (expectedCT == null) return true;
        final ContentType currentCT = ContentType.parse(getRequestHeader(exchange, CONTENT_TYPE));
        if (!expectedCT.equals(currentCT)) {
            exchange.setStatusCode(BAD_REQUEST);
            exchange.endExchange();
            HttpClientMessages.MESSAGES.debugf("Exchange %s is missing %s content type header", exchange, expectedCT);
            return false;
        }
        return true;
    }

    private boolean containsRequiredRequestHeaders(final HttpServerExchange exchange) {
        final HttpString[] requestHeaders = getRequiredRequestHeaders();
        if (requestHeaders == null || requestHeaders.length == 0) return true;
        String value = null;
        for (int i = 0; i < requestHeaders.length; i++) {
            value = getRequestHeader(exchange, requestHeaders[i]);
            if (value == null) {
                exchange.setStatusCode(BAD_REQUEST);
                exchange.endExchange();
                HttpClientMessages.MESSAGES.debugf("Exchange %s is missing %s request header", exchange, requestHeaders[i]);
                return false;
            }
        }
        return true;
    }

    private boolean containsRequiredQueryParameters(final HttpServerExchange exchange) {
        final String[] queryParameters = getRequiredQueryParameters();
        if (queryParameters == null || queryParameters.length == 0) return true;
        Deque<String> values = null;
        for (int i = 0; i < queryParameters.length; i++) {
            values = exchange.getQueryParameters().get(queryParameters[i]);
            if (values == null || values.isEmpty()) {
                exchange.setStatusCode(BAD_REQUEST);
                exchange.endExchange();
                HttpClientMessages.MESSAGES.debugf("Exchange %s is missing %s query parameter", exchange, queryParameters[i]);
                return false;
            }
        }
        return true;
    }

    protected ContentType getRequiredContentType() {
        return null;
    }

    protected HttpString[] getRequiredRequestHeaders() {
        return null;
    }

    protected String[] getRequiredQueryParameters() {
        return null;
    }

    protected final void sendException(final HttpServerExchange exchange, final int status, final Throwable e) {
        try {
            exchange.setStatusCode(status);
            putResponseHeader(exchange, CONTENT_TYPE, "application/x-wf-jbmar-exception;version=1");
            final Marshaller marshaller = config.getHttpMarshallerFactory(exchange).createMarshaller();
            final OutputStream outputStream = exchange.getOutputStream();
            try (ByteOutput byteOutput = byteOutputOf(outputStream)) {
                // start the marshaller
                marshaller.start(byteOutput);
                marshaller.writeObject(e);
                marshaller.write(0);
                marshaller.finish();
                marshaller.flush();
            }
            exchange.endExchange();
        } catch (Exception ex) {
            ex.addSuppressed(e);
            HttpClientMessages.MESSAGES.failedToWriteException(ex);
            exchange.endExchange();
        }
    }

}
