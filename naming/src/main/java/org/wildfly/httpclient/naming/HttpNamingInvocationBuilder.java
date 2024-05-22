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
import static io.undertow.util.Methods.DELETE;
import static io.undertow.util.Methods.GET;
import static io.undertow.util.Methods.PATCH;
import static io.undertow.util.Methods.POST;
import static io.undertow.util.Methods.PUT;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.wildfly.httpclient.common.Protocol.VERSION_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.BIND_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.CREATE_SUBCONTEXT_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.DESTROY_SUBCONTEXT_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.EXCEPTION;
import static org.wildfly.httpclient.naming.NamingConstants.LIST_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LIST_BINDINGS_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LOOKUP_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LOOKUP_LINK_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.NAMING_CONTEXT;
import static org.wildfly.httpclient.naming.NamingConstants.REBIND_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.RENAME_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.UNBIND_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.VALUE;

import io.undertow.client.ClientRequest;
import io.undertow.util.HeaderMap;
import org.wildfly.httpclient.common.Protocol;

import javax.naming.Name;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class HttpNamingInvocationBuilder {

    private InvocationType invocationType;
    private Name name;
    private Name newName;
    private Object object;
    private int version = Protocol.LATEST;

    // setters

    HttpNamingInvocationBuilder setInvocationType(final InvocationType invocationType) {
        this.invocationType = invocationType;
        return this;
    }

    HttpNamingInvocationBuilder setName(final Name name) {
        this.name = name;
        return this;
    }

    HttpNamingInvocationBuilder setNewName(final Name newName) {
        this.newName = newName;
        return this;
    }

    HttpNamingInvocationBuilder setObject(final Object object) {
        this.object = object;
        return this;
    }

    HttpNamingInvocationBuilder setVersion(final int version) {
        this.version = version;
        return this;
    }

    enum InvocationType {
        BIND(BIND_PATH),
        CREATE_SUBCONTEXT(CREATE_SUBCONTEXT_PATH),
        DESTROY_SUBCONTEXT(DESTROY_SUBCONTEXT_PATH),
        LIST(LIST_PATH),
        LIST_BINDINGS(LIST_BINDINGS_PATH),
        LOOKUP(LOOKUP_PATH),
        LOOKUP_LINK(LOOKUP_LINK_PATH),
        REBIND(REBIND_PATH),
        RENAME(RENAME_PATH),
        UNBIND(UNBIND_PATH);

        private final String path;

        InvocationType(final String path) {
            this.path = path;
        }

        String getPath() {
            return path;
        }
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
        if (invocationType == InvocationType.BIND) request.setMethod(PUT);
        else if (invocationType == InvocationType.CREATE_SUBCONTEXT) request.setMethod(PUT);
        else if (invocationType == InvocationType.DESTROY_SUBCONTEXT) request.setMethod(DELETE);
        else if (invocationType == InvocationType.LIST) request.setMethod(GET);
        else if (invocationType == InvocationType.LIST_BINDINGS) request.setMethod(GET);
        else if (invocationType == InvocationType.LOOKUP) request.setMethod(POST);
        else if (invocationType == InvocationType.LOOKUP_LINK) request.setMethod(POST);
        else if (invocationType == InvocationType.REBIND) request.setMethod(PATCH);
        else if (invocationType == InvocationType.RENAME) request.setMethod(PATCH);
        else if (invocationType == InvocationType.UNBIND) request.setMethod(DELETE);
        else throw new IllegalStateException();
    }

    private void setRequestPath(final ClientRequest request, final String prefix) {
        final StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix);
        }
        appendPath(sb, NAMING_CONTEXT, false);
        appendPath(sb, VERSION_PATH + version, false);
        appendPath(sb, invocationType.getPath(), false);
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
