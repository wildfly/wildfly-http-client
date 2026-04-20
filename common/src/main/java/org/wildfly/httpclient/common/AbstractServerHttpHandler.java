/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2026 Red Hat, Inc., and individual contributors
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
import static org.wildfly.httpclient.common.HttpMarshallerFactory.DEFAULT_FACTORY;
import static org.wildfly.httpclient.common.HttpMarshallerFactory.INTEROPERABLE_FACTORY;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;

import java.io.OutputStream;
import java.util.Deque;

/**
 * All WildFly HTTP Client library server handlers must extend this base class.
 * It provides various template and utility methods for easier server handlers implementation.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public abstract class AbstractServerHttpHandler implements HttpHandler {

    private final Version serverVersion = Version.LATEST;

    protected AbstractServerHttpHandler() {
    }

    /**
     * Template method used by validation method
     * {@link AbstractServerHttpHandler#containsRequiredContentType(HttpServerExchange)}
     * during validation.
     * <p>
     * If this method returns {@code null}, no validation is performed. Otherwise,
     * the returned content type is used as the expected value for validation.
     *
     * @return the expected content type, or {@code null} if no validation is required
     */
    protected ContentType getRequiredContentType() {
        return null;
    }

    /**
     * Template method used by validation method
     * {@link AbstractServerHttpHandler#containsRequiredRequestHeaders(HttpServerExchange)}
     * during validation.
     * <p>
     * If this method returns {@code null}, no validation is performed. Otherwise,
     * the returned request headers are used as the expected values for validation.
     *
     * @return the expected request headers, or {@code null} if no validation is required
     */
    protected HttpString[] getRequiredRequestHeaders() {
        return null;
    }

    /**
     * Template method used by validation method
     * {@link AbstractServerHttpHandler#containsRequiredQueryParameters(HttpServerExchange)}
     * during validation.
     * <p>
     * If this method returns {@code null}, no validation is performed. Otherwise,
     * the returned query parameters are used as the expected values for validation.
     *
     * @return the expected query parameters, or {@code null} if no validation is required
     */
    protected String[] getRequiredQueryParameters() {
        return null;
    }

    /**
     * Template method invoked after all validations performed by the following methods succeed:
     * <ul>
     *   <li>{@link AbstractServerHttpHandler#containsRequiredContentType(HttpServerExchange)}</li>
     *   <li>{@link AbstractServerHttpHandler#containsRequiredRequestHeaders(HttpServerExchange)}}</li>
     *   <li>{@link AbstractServerHttpHandler#containsRequiredQueryParameters(HttpServerExchange)}</li>
     * </ul>
     * This method is not invoked if any validation fails; in that case, the validation exception
     * is propagated to the client.
     *
     * @param exchange the HTTP exchange
     * @throws Exception if an error occurs; the exception is propagated to the client
     */
    protected abstract void processRequest(final HttpServerExchange exchange) throws Exception;

    /**
     * Handles an incoming request. First, the following validation methods are invoked:
     * <ul>
     *   <li>{@link AbstractServerHttpHandler#containsRequiredContentType(HttpServerExchange)}</li>
     *   <li>{@link AbstractServerHttpHandler#containsRequiredRequestHeaders(HttpServerExchange)}}</li>
     *   <li>{@link AbstractServerHttpHandler#containsRequiredQueryParameters(HttpServerExchange)}</li>
     * </ul>
     * If all validations succeed, the protocol version is determined and the template method
     * {@link AbstractServerHttpHandler#processRequest(HttpServerExchange)} is invoked.
     *
     * If any validation fails, or if {@code processRequest()} throws an exception, the exception
     * is propagated to the client.
     *
     * @param exchange the HTTP request/response exchange
     */
    @Override
    public final void handleRequest(final HttpServerExchange exchange) {
        try {
            if (!containsRequiredContentType(exchange)) return;
            if (!containsRequiredRequestHeaders(exchange)) return;
            if (!containsRequiredQueryParameters(exchange)) return;
            handshakeVersion(exchange);
            processRequest(exchange);
        } catch (Throwable e) {
            sendException(exchange, INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * Returns the protocol version currently in use for the given exchange.
     *
     * @param exchange the HTTP request/response exchange
     * @return the protocol version associated with the exchange
     */
    protected final Version getVersion(final HttpServerExchange exchange) {
        return exchange.getAttachment(Version.KEY);
    }

    /**
     * Returns the {@code HttpMarshallerFactory} associated with the given exchange,
     * based on the server configuration and the {@link Version} in use.
     *
     * @param exchange the HTTP request/response exchange
     * @return the corresponding {@code HttpMarshallerFactory}
     */
    protected final HttpMarshallerFactory getHttpMarshallerFactory(final HttpServerExchange exchange) {
        return Version.JAVA_EE_8 == getVersion(exchange) ? INTEROPERABLE_FACTORY : DEFAULT_FACTORY;
    }

    /**
     * Utility method that propagates an exception to the client.
     *
     * @param exchange the HTTP request/response exchange
     * @param status the HTTP status code to return
     * @param e the exception to propagate
     */
    protected final void sendException(final HttpServerExchange exchange, final int status, final Throwable e) {
        try {
            exchange.setStatusCode(status);
            putResponseHeader(exchange, CONTENT_TYPE, "application/x-wf-jbmar-exception;version=1");
            final Marshaller marshaller = getHttpMarshallerFactory(exchange).createMarshaller();
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
        String value;
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
        Deque<String> values;
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

    private void handshakeVersion(final HttpServerExchange exchange) {
        final Version clientVersion = Version.readFrom(exchange);
        if (Version.JAVA_EE_8.equals(clientVersion)) {
            if (HttpServiceConfig.getInstance() == HttpServiceConfig.DEFAULT) {
                throw HttpClientMessages.MESSAGES.javaeeToJakartaeeBackwardCompatibilityLayerDisabled();
            }
        }
        if (clientVersion.compareTo(serverVersion) < 0) {
            exchange.putAttachment(Version.KEY, clientVersion);
            clientVersion.writeTo(exchange);
        } else {
            exchange.putAttachment(Version.KEY, serverVersion);
            serverVersion.writeTo(exchange);
        }
    }

}
