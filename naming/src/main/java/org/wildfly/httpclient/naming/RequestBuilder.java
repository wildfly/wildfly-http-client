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

package org.wildfly.httpclient.naming;

import static io.undertow.util.Headers.ACCEPT;
import static io.undertow.util.Headers.CONTENT_TYPE;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.wildfly.httpclient.common.HeadersHelper.putRequestHeader;
import static org.wildfly.httpclient.common.Protocol.VERSION_PATH;
import static org.wildfly.httpclient.naming.Constants.EXCEPTION;
import static org.wildfly.httpclient.naming.Constants.NAMING_CONTEXT;
import static org.wildfly.httpclient.naming.Constants.NEW_QUERY_PARAMETER;
import static org.wildfly.httpclient.naming.Constants.VALUE;

import io.undertow.client.ClientRequest;

import org.wildfly.httpclient.common.HttpTargetContext;

import javax.naming.Name;

/**
 * HTTP JNDI module client request builder. Encapsulates all information needed to create HTTP JNDI client requests.
 * Use setter methods (those returning {@link RequestBuilder}) to configure the builder.
 * Once configured {@link #createRequest(String)} method must be called to build HTTP client request.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class RequestBuilder extends org.wildfly.httpclient.common.RequestBuilder<RequestType> {

    private Name name;
    private Name newName;
    private Object object;

    // constructor

    RequestBuilder(final HttpTargetContext targetContext, final RequestType requestType) {
        super(targetContext, requestType);
    }

    // setters

    RequestBuilder setName(final Name name) {
        this.name = name;
        return this;
    }

    RequestBuilder setNewName(final Name newName) {
        this.newName = newName;
        return this;
    }

    RequestBuilder setObject(final Object object) {
        this.object = object;
        return this;
    }

    // implementation

    protected void setRequestPath(final ClientRequest request) {
        final StringBuilder sb = new StringBuilder();
        sb.append(super.getPathPrefix());
        appendPath(sb, NAMING_CONTEXT, false);
        appendPath(sb, VERSION_PATH + getProtocolVersion(), false);
        appendPath(sb, getRequestType().getPath(), false);
        appendPath(sb, name.toString(), true);
        if (newName != null) {
            sb.append("?" + NEW_QUERY_PARAMETER + "=");
            sb.append(encode(newName.toString(), UTF_8));
        }
        request.setPath(sb.toString());
    }

    protected void setRequestHeaders(final ClientRequest request) {
        putRequestHeader(request, ACCEPT, VALUE + "," + EXCEPTION);
        if (object != null) {
            putRequestHeader(request, CONTENT_TYPE, VALUE);
        }
    }

    private static void appendPath(final StringBuilder sb, final String path, final boolean encode) {
        if (!path.startsWith("/") || encode) {
            sb.append("/");
        }
        sb.append(encode ? encode(path, UTF_8) : path);
    }

}
