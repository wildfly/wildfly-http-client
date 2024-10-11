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

import static io.undertow.util.Headers.ACCEPT;
import static io.undertow.util.Headers.ACCEPT_ENCODING;
import static io.undertow.util.Headers.CONTENT_ENCODING;
import static io.undertow.util.Headers.CONTENT_TYPE;
import static io.undertow.util.Headers.CHUNKED;
import static io.undertow.util.Headers.GZIP;
import static io.undertow.util.Headers.TRANSFER_ENCODING;

import static org.wildfly.httpclient.ejb.EjbConstants.EJB_DISCOVERY_RESPONSE;
import static org.wildfly.httpclient.ejb.EjbConstants.EJB_EXCEPTION;
import static org.wildfly.httpclient.ejb.EjbConstants.INVOCATION_ACCEPT;
import static org.wildfly.httpclient.ejb.EjbConstants.INVOCATION_ID;
import static org.wildfly.httpclient.ejb.EjbConstants.INVOCATION;
import static org.wildfly.httpclient.ejb.EjbConstants.SESSION_OPEN;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.net.URLEncoder.encode;

import io.undertow.client.ClientRequest;
import io.undertow.util.HeaderMap;
import org.jboss.ejb.client.EJBLocator;
import org.wildfly.httpclient.common.Protocol;

import java.lang.reflect.Method;

/**
 * HTTP EJB module client request builder. Encapsulates all information needed to create HTTP EJB client requests.
 * Use setter methods (those returning {@link RequestBuilder}) to configure the builder.
 * Once configured {@link #createRequest(String)} method must be called to build HTTP client request.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class RequestBuilder {

    private EJBLocator<?> locator;
    private String beanId;
    private String view;
    private Method method;
    private RequestType requestType;
    private String invocationId;
    private int version = Protocol.LATEST;
    private boolean cancelIfRunning;
    private boolean compressRequest;
    private boolean compressResponse;

    RequestBuilder setCompressRequest(final boolean compressRequest) {
        this.compressRequest = compressRequest;
        return this;
    }

    RequestBuilder setCompressResponse(final boolean compressResponse) {
        this.compressResponse = compressResponse;
        return this;
    }

    RequestBuilder setLocator(final EJBLocator<?> locator) {
        this.locator = locator;
        return this;
    }

    RequestBuilder setBeanId(final String beanId) {
        this.beanId = beanId;
        return this;
    }

    RequestBuilder setMethod(final Method method) {
        this.method = method;
        return this;
    }

    RequestBuilder setView(final String view) {
        this.view = view;
        return this;
    }

    RequestBuilder setRequestType(final RequestType requestType) {
        this.requestType = requestType;
        return this;
    }

    RequestBuilder setInvocationId(final String invocationId) {
        this.invocationId = invocationId;
        return this;
    }

    RequestBuilder setVersion(final int version) {
        this.version = version;
        return this;
    }

    RequestBuilder setCancelIfRunning(final boolean cancelIfRunning) {
        this.cancelIfRunning = cancelIfRunning;
        return this;
    }

    ClientRequest createRequest(final String prefix) {
        final ClientRequest clientRequest = new ClientRequest();
        setRequestMethod(clientRequest);
        setRequestPath(clientRequest, prefix);
        setRequestHeaders(clientRequest);
        return clientRequest;
    }

    private void setRequestMethod(final ClientRequest request) {
        request.setMethod(requestType.getMethod());
    }

    private void setRequestPath(final ClientRequest request, final String prefix) {
        switch (requestType) {
            case INVOKE: request.setPath(getStartEjbInvocationRequestPath(prefix)); break;
            case OPEN: request.setPath(getCreateSessionEjbRequestPath(prefix)); break;
            case DISCOVER: request.setPath(getDiscoverEjbRequestPath(prefix)); break;
            case CANCEL: request.setPath(getCancelEjbInvocationRequestPath(prefix)); break;
            default: throw new IllegalStateException();
        }
    }

    private void setRequestHeaders(final ClientRequest request) {
        final HeaderMap headers = request.getRequestHeaders();
        switch (requestType) {
            case INVOKE: {
                headers.add(ACCEPT, INVOCATION_ACCEPT + "," + EJB_EXCEPTION);
                headers.put(CONTENT_TYPE, INVOCATION.toString());
                if (invocationId != null) {
                    headers.put(INVOCATION_ID, invocationId);
                }
                if (compressRequest) {
                    headers.put(CONTENT_ENCODING, GZIP.toString());
                }
                if (compressResponse) {
                    headers.put(ACCEPT_ENCODING, GZIP.toString());
                }
                headers.put(TRANSFER_ENCODING, CHUNKED.toString());
            } break;
            case OPEN: {
                headers.add(ACCEPT, EJB_EXCEPTION.toString());
                headers.put(CONTENT_TYPE, SESSION_OPEN.toString());
            } break;
            case DISCOVER: {
                headers.add(ACCEPT, EJB_DISCOVERY_RESPONSE + "," + EJB_EXCEPTION);
            } break;
            case CANCEL: {
                // no headers to be added
            } break;
            default: throw new IllegalStateException();
        }
    }

    private String getCreateSessionEjbRequestPath(final String prefix) {
        final StringBuilder sb = new StringBuilder();
        appendOperationPath(sb, prefix);
        appendBeanPath(sb);
        return sb.toString();
    }

    private String getDiscoverEjbRequestPath(final String prefix) {
        final StringBuilder sb = new StringBuilder();
        appendOperationPath(sb, prefix);
        return sb.toString();
    }

    private String getCancelEjbInvocationRequestPath(final String prefix) {
        final StringBuilder sb = new StringBuilder();
        appendOperationPath(sb, prefix);
        appendBeanPath(sb);
        appendPath(sb, invocationId, false);
        appendPath(sb, "" + cancelIfRunning, false);
        return sb.toString();
    }

    private String getStartEjbInvocationRequestPath(final String prefix) {
        final StringBuilder sb = new StringBuilder();
        appendOperationPath(sb, prefix);
        appendBeanPath(sb);
        appendPath(sb, beanId, false);
        appendPath(sb, view, false);
        appendPath(sb, method.getName(), false);
        for (final Class<?> param : method.getParameterTypes()) {
            appendPath(sb, param.getName(), true);
        }
        return sb.toString();
    }

    private void appendBeanPath(final StringBuilder sb) {
        appendPath(sb, locator.getAppName(), true);
        appendPath(sb, locator.getModuleName(), true);
        appendPath(sb, locator.getDistinctName(), true);
        appendPath(sb, locator.getBeanName(), true);
    }

    private void appendOperationPath(final StringBuilder sb, final String prefix) {
        if (prefix != null) {
            sb.append(prefix);
        }
        appendPath(sb, "ejb", false);
        appendPath(sb, "v" + version, false);
        appendPath(sb, requestType.getPath(), false);
    }

    private static void appendPath(final StringBuilder sb, final String path, final boolean encode) {
        sb.append("/").append(path == null || path.isEmpty() ? "-" : encode ? encode(path, UTF_8) : path);
    }

}
