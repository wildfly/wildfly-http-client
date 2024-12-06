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
import static org.wildfly.httpclient.common.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.common.HeadersHelper.putResponseHeader;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;

import java.io.OutputStream;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public abstract class AbstractServerHttpHandler implements HttpHandler {

    protected final HttpServiceConfig config;

    protected AbstractServerHttpHandler(final HttpServiceConfig config) {
        this.config = config;
    }

    protected final void sendException(HttpServerExchange exchange, HttpServiceConfig serviceConfig, int status, Throwable e) {
        try {
            exchange.setStatusCode(status);
            putResponseHeader(exchange, CONTENT_TYPE, "application/x-wf-jbmar-exception;version=1");
            final Marshaller marshaller = serviceConfig.getHttpMarshallerFactory(exchange).createMarshaller();
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
