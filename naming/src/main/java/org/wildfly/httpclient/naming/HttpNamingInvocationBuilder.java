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
import static org.wildfly.httpclient.naming.NamingConstants.EXCEPTION;
import static org.wildfly.httpclient.naming.NamingConstants.VALUE;

import io.undertow.client.ClientRequest;
import io.undertow.util.HeaderMap;
import org.wildfly.httpclient.common.Protocol;

import javax.naming.CompositeName;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class HttpNamingInvocationBuilder {

    private InvocationType invocationType;
    private CompositeName name;
    private CompositeName newName;
    private Object object;
    private int version = Protocol.LATEST;

    // setters

    HttpNamingInvocationBuilder setInvocationType(final InvocationType invocationType) {
        this.invocationType = invocationType;
        return this;
    }

    HttpNamingInvocationBuilder setName(final CompositeName name) {
        this.name = name;
        return this;
    }

    HttpNamingInvocationBuilder setNewName(final CompositeName newName) {
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
        BIND,
        CREATE_SUBCONTEXT,
        DESTROY_SUBCONTEXT,
        LIST,
        LIST_BINDINGS,
        LOOKUP,
        LOOKUP_LINK,
        REBIND_PATH,
        RENAME_PATH,
        UNBIND_PATH;
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
        else if (invocationType == InvocationType.REBIND_PATH) request.setMethod(PATCH);
        else if (invocationType == InvocationType.RENAME_PATH) request.setMethod(PATCH);
        else if (invocationType == InvocationType.UNBIND_PATH) request.setMethod(DELETE);
        else throw new IllegalStateException();
    }

    private void setRequestPath(final ClientRequest request, final String prefix) {
        throw new UnsupportedOperationException(); // TODO: implement
    }

    private void setRequestHeaders(final ClientRequest request) {
        final HeaderMap headers = request.getRequestHeaders();
        headers.put(ACCEPT, VALUE + "," + EXCEPTION);
        if (object != null) {
            headers.put(CONTENT_TYPE, VALUE.toString());
        }
    }

}
