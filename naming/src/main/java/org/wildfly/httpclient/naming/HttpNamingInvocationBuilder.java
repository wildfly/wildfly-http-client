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

import io.undertow.client.ClientRequest;
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
        throw new UnsupportedOperationException(); // TODO: implement
    }

    private void setRequestPath(final ClientRequest request, final String prefix) {
        throw new UnsupportedOperationException(); // TODO: implement
    }

    private void setRequestHeaders(final ClientRequest request) {
        throw new UnsupportedOperationException(); // TODO: implement
    }

}
