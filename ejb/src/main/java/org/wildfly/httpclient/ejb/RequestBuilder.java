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

import static org.wildfly.httpclient.common.HeadersHelper.putRequestHeader;
import static org.wildfly.httpclient.ejb.Constants.EJB_CONTEXT;
import static org.wildfly.httpclient.ejb.Constants.EJB_DISCOVERY_RESPONSE;
import static org.wildfly.httpclient.ejb.Constants.EJB_EXCEPTION;
import static org.wildfly.httpclient.ejb.Constants.INVOCATION_ACCEPT;
import static org.wildfly.httpclient.ejb.Constants.INVOCATION_ID;
import static org.wildfly.httpclient.ejb.Constants.INVOCATION;
import static org.wildfly.httpclient.ejb.Constants.SESSION_OPEN;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.net.URLEncoder.encode;

import io.undertow.client.ClientRequest;
import org.jboss.ejb.client.EJBLocator;
import org.wildfly.httpclient.common.HttpTargetContext;

import java.lang.reflect.Method;

/**
 * HTTP EJB module client request builder. Encapsulates all information needed to create HTTP EJB client requests.
 * Use setter methods (those returning {@link RequestBuilder}) to configure the builder.
 * Once configured {@link #createRequest(String)} method must be called to build HTTP client request.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class RequestBuilder extends org.wildfly.httpclient.common.RequestBuilder<RequestType> {

    private EJBLocator<?> locator;
    private String beanId;
    private String view;
    private Method method;
    private String invocationId;
    private boolean cancelIfRunning;
    private boolean compressRequest;
    private boolean compressResponse;

    // constructor

    RequestBuilder(final HttpTargetContext targetContext, final RequestType requestType) {
        super(targetContext, requestType);
    }

    // setters

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

    RequestBuilder setInvocationId(final String invocationId) {
        this.invocationId = invocationId;
        return this;
    }

    RequestBuilder setCancelIfRunning(final boolean cancelIfRunning) {
        this.cancelIfRunning = cancelIfRunning;
        return this;
    }

    // implementation

    @Override
    protected void setRequestPath(final ClientRequest request) {
        final StringBuilder sb = new StringBuilder();
        appendOperationPath(sb, EJB_CONTEXT);
        switch (getRequestType()) {
            case CANCEL: {
                appendBeanPath(sb);
                appendPath(sb, invocationId, false);
                appendPath(sb, "" + cancelIfRunning, false);
            } break;
            case CREATE_SESSION: {
                appendBeanPath(sb);
            } break;
            case DISCOVER: break;
            case INVOKE: {
                appendBeanPath(sb);
                appendPath(sb, beanId, false);
                appendPath(sb, view, false);
                appendPath(sb, method.getName(), false);
                for (Class<?> param : method.getParameterTypes()) {
                    appendPath(sb, param.getName(), true);
                }
            } break;
            default: throw new IllegalStateException();
        }
        request.setPath(sb.toString());
    }

    @Override
    protected void setRequestHeaders(final ClientRequest request) {
        switch (getRequestType()) {
            case CANCEL: {
                // no headers to be added
            } break;
            case CREATE_SESSION: {
                putRequestHeader(request, ACCEPT, EJB_EXCEPTION);
                putRequestHeader(request, CONTENT_TYPE, SESSION_OPEN);
            } break;
            case DISCOVER: {
                putRequestHeader(request, ACCEPT, EJB_DISCOVERY_RESPONSE + "," + EJB_EXCEPTION);
            } break;
            case INVOKE: {
                putRequestHeader(request, ACCEPT, INVOCATION_ACCEPT + "," + EJB_EXCEPTION);
                putRequestHeader(request, CONTENT_TYPE, INVOCATION);
                if (invocationId != null) {
                    putRequestHeader(request, INVOCATION_ID, invocationId);
                }
                if (compressRequest) {
                    putRequestHeader(request, CONTENT_ENCODING, GZIP);
                }
                if (compressResponse) {
                    putRequestHeader(request, ACCEPT_ENCODING, GZIP);
                }
                putRequestHeader(request, TRANSFER_ENCODING, CHUNKED);
            } break;
            default: throw new IllegalStateException();
        }
    }

    @Override
    protected void appendPath(final StringBuilder sb, final String path, final boolean encode) {
        if (path == null || !path.startsWith("/") || encode) {
            sb.append("/");
        }
        sb.append(path == null || path.isEmpty() ? "-" : encode ? encode(path, UTF_8) : path);
    }

    private void appendBeanPath(final StringBuilder sb) {
        appendPath(sb, locator.getAppName(), true);
        appendPath(sb, locator.getModuleName(), true);
        appendPath(sb, locator.getDistinctName(), true);
        appendPath(sb, locator.getBeanName(), true);
    }

}
