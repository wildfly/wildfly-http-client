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

import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.undertow.client.ClientRequest;

/**
 * Module-specific request builders. Each module (e.g., EJB, JNDI, TXN) defines its own request builder.
 * <p>
 * Every new request builder must implement {@link #setRequestPath(ClientRequest)}
 * and {@link #setRequestHeaders(ClientRequest)} methods.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public abstract class RequestBuilder<E extends Enum<? extends RequestType>> {

    private final HttpTargetContext targetContext;
    private final E requestType;

    protected RequestBuilder(final HttpTargetContext targetContext, final E requestType) {
        this.targetContext = targetContext;
        this.requestType = requestType;
    }

    /**
     * Creates an HTTP client request.
     *
     * @return the created HTTP client request
     */
    public final ClientRequest createRequest() {
        final ClientRequest request = new ClientRequest();
        setRequestMethod(request);
        setRequestPath(request);
        setRequestHeaders(request);
        return request;
    }

    /**
     * Returns the request type associated with this builder.
     *
     * @return the request type
     */
    protected final E getRequestType() {
        return requestType;
    }

    /**
     * Returns the version associated with this builder.
     *
     * @return the version
     */
    protected final Version getVersion() {
        return targetContext.getVersion();
    }

    /**
     * Returns the path prefix associated with this builder.
     *
     * @return the path prefix
     */
    protected final String getPathPrefix() {
        return targetContext.getUri().getPath();
    }


    /**
     * Template method all request builders have to implement.
     *
     * @param request the HTTP client request
     */
    protected abstract void setRequestPath(final ClientRequest request);

    /**
     * Template method all request builders have to implement.
     *
     * @param request the HTTP client request
     */
    protected abstract void setRequestHeaders(final ClientRequest request);

    /**
     * Appends a query parameter to the given path builder.
     *
     * @param sb the StringBuilder to append the path to
     * @param paramName the parameter name to append
     * @param paramValue the parameter value to append
     */
    protected final void setQueryParameter(final StringBuilder sb, final String paramName, final String paramValue) {
        sb.append("?");
        sb.append(encode(paramName, UTF_8));
        sb.append("=");
        sb.append(encode(paramValue, UTF_8));
    }

    /**
     * Appends a path to the given path builder.
     *
     * @param sb the StringBuilder to append the path to
     * @param path the path to append
     * @param encode whether the path should be encoded
     */
    protected void appendPath(final StringBuilder sb, final String path, final boolean encode) {
        if (!path.startsWith("/") || encode) {
            sb.append("/");
        }
        sb.append(encode ? encode(path, UTF_8) : path);
    }

    /**
     * Appends the operation path to the given path builder.
     *
     * @param sb the StringBuilder to append the operation path to
     */
    protected final void appendOperationPath(final StringBuilder sb, final String contextPath) {
        sb.append(getPathPrefix());
        appendPath(sb, contextPath, false);
        appendPath(sb, getVersionPath(), false);
        appendPath(sb, ((RequestType) getRequestType()).getPath(), false);
    }

    private void setRequestMethod(final ClientRequest request) {
        request.setMethod(((RequestType) requestType).getMethod());
    }

    private String getVersionPath() {
        final Version.Handler handlerVersion = getVersion().handler();
        if (handlerVersion == Version.Handler.VERSION_1) return Protocol.VERSION_ONE_PATH;
        if (handlerVersion == Version.Handler.VERSION_2) return Protocol.VERSION_TWO_PATH;
        throw new IllegalStateException();
    }

}
