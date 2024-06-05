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
import static org.wildfly.httpclient.common.Protocol.VERSION_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.EXCEPTION;
import static org.wildfly.httpclient.naming.NamingConstants.NAMING_CONTEXT;
import static org.wildfly.httpclient.naming.NamingConstants.VALUE;

import io.undertow.client.ClientRequest;
import io.undertow.util.HeaderMap;
import org.wildfly.httpclient.common.Protocol;

import javax.naming.Name;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class RequestBuilder {

    private RequestType requestType;
    private Name name;
    private Name newName;
    private Object object;
    private int version = Protocol.LATEST;

    // setters

    RequestBuilder setRequestType(final RequestType requestType) {
        this.requestType = requestType;
        return this;
    }

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

    RequestBuilder setVersion(final int version) {
        this.version = version;
        return this;
    }

    // helper methods

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
        final StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix);
        }
        appendPath(sb, NAMING_CONTEXT, false);
        appendPath(sb, VERSION_PATH + version, false);
        appendPath(sb, requestType.getPath(), false);
        appendPath(sb, name.toString(), true);
        if (newName != null) {
            sb.append("?new=");
            sb.append(encode(newName.toString(), UTF_8));
        }
        request.setPath(sb.toString());
    }

    private void setRequestHeaders(final ClientRequest request) {
        final HeaderMap headers = request.getRequestHeaders();
        headers.put(ACCEPT, VALUE + "," + EXCEPTION);
        if (object != null) {
            headers.put(CONTENT_TYPE, VALUE.toString());
        }
    }

    private static void appendPath(final StringBuilder sb, final String path, final boolean encode) {
        if (!path.startsWith("/")) {
            sb.append("/");
        }
        sb.append(encode ? encode(path, UTF_8) : path);
    }

}
