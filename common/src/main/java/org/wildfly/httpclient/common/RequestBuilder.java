/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2025 Red Hat, Inc., and individual contributors
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

public abstract class RequestBuilder<E extends Enum<? extends RequestType>> {

    private final HttpTargetContext targetContext;
    private final E requestType;

    protected RequestBuilder(final HttpTargetContext targetContext, final E requestType) {
        this.targetContext = targetContext;
        this.requestType = requestType;
    }

    protected E getRequestType() {
        return requestType;
    }

    protected int getProtocolVersion() {
        return targetContext.getProtocolVersion();
    }

    protected String getPathPrefix() {
        return targetContext.getUri().getPath();
    }

    public final ClientRequest createRequest() {
        final ClientRequest request = new ClientRequest();
        setRequestMethod(request);
        setRequestPath(request);
        setRequestHeaders(request);
        return request;
    }

    protected void setRequestMethod(final ClientRequest request) {
        request.setMethod(((RequestType) requestType).getMethod());
    }

    protected abstract void setRequestPath(final ClientRequest request);

    protected abstract void setRequestHeaders(final ClientRequest request);

}
