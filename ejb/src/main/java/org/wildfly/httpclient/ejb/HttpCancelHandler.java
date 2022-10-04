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

import static org.wildfly.httpclient.ejb.EjbConstants.JSESSIONID_COOKIE_NAME;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.jboss.ejb.server.Association;
import org.jboss.ejb.server.CancelHandle;
import org.wildfly.httpclient.common.ContentType;
import org.wildfly.httpclient.common.Version;
import org.wildfly.transaction.client.LocalTransactionContext;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

/**
 * A versioned handler for handling EJB client cancel operations.
 *
 * @author Stuart Douglas
 * @author <a href="rachmato@redhat.com">Richard Achmatowicz</a>
 */
class HttpCancelHandler extends RemoteHTTPHandler {

    private final Association association;
    private final ExecutorService executorService;
    private final LocalTransactionContext localTransactionContext;
    private final Map<InvocationIdentifier, CancelHandle> cancellationFlags;

    HttpCancelHandler(Version version, Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext, Map<InvocationIdentifier, CancelHandle> cancellationFlags) {
        super(version, executorService);
        this.association = association;
        this.executorService = executorService;
        this.localTransactionContext = localTransactionContext;
        this.cancellationFlags = cancellationFlags;
    }

    @Override
    protected void handleInternal(HttpServerExchange exchange) throws Exception {
        String ct = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        ContentType contentType = ContentType.parse(ct);
        if (contentType != null) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            EjbHttpClientMessages.MESSAGES.debugf("Bad content type %s", ct);
            return;
        }

        String relativePath = exchange.getRelativePath();
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        String[] parts = relativePath.split("/");
        if (parts.length != 6) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            return;
        }
        final String app = handleDash(parts[0]);
        final String module = handleDash(parts[1]);
        final String distinct = handleDash(parts[2]);
        final String bean = parts[3];
        String invocationId = parts[4];
        boolean cancelIdRunning = Boolean.parseBoolean(parts[5]);
        // TODO: cancellation requires that a Cookie be present
        Cookie cookie = exchange.getRequestCookies().get(JSESSIONID_COOKIE_NAME);
        final String sessionAffinity = cookie != null ? cookie.getValue() : null;
        final InvocationIdentifier identifier;
        if (invocationId != null && sessionAffinity != null) {
            identifier = new InvocationIdentifier(invocationId, sessionAffinity);
        } else {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            EjbHttpClientMessages.MESSAGES.debugf("Exchange %s did not include both session id and invocation id in cancel request", exchange);
            return;
        }
        CancelHandle handle = cancellationFlags.remove(identifier);
        if (handle != null) {
            handle.cancel(cancelIdRunning);
        }
    }

    private static String handleDash(String s) {
        if (s.equals("-")) {
            return "";
        }
        return s;
    }

}
