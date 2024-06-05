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

import io.undertow.util.HttpString;

import static io.undertow.util.Methods.DELETE;
import static io.undertow.util.Methods.GET;
import static io.undertow.util.Methods.PATCH;
import static io.undertow.util.Methods.POST;
import static io.undertow.util.Methods.PUT;
import static org.wildfly.httpclient.naming.NamingConstants.BIND_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.CREATE_SUBCONTEXT_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.DESTROY_SUBCONTEXT_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LIST_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LIST_BINDINGS_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LOOKUP_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LOOKUP_LINK_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.REBIND_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.RENAME_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.UNBIND_PATH;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
enum RequestType {

    BIND(PUT, BIND_PATH),
    CREATE_SUBCONTEXT(PUT, CREATE_SUBCONTEXT_PATH),
    DESTROY_SUBCONTEXT(DELETE, DESTROY_SUBCONTEXT_PATH),
    LIST(GET, LIST_PATH),
    LIST_BINDINGS(GET, LIST_BINDINGS_PATH),
    LOOKUP(POST, LOOKUP_PATH),
    LOOKUP_LINK(POST, LOOKUP_LINK_PATH),
    REBIND(PATCH, REBIND_PATH),
    RENAME(PATCH, RENAME_PATH),
    UNBIND(DELETE, UNBIND_PATH);

    private final HttpString method;
    private final String path;

    RequestType(final HttpString method, final String path) {
        this.method = method;
        this.path = path;
    }

    HttpString getMethod() {
        return method;
    }

    String getPath() {
        return path;
    }

}
