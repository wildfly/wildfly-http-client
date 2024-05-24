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

    BIND(BIND_PATH, PUT),
    CREATE_SUBCONTEXT(CREATE_SUBCONTEXT_PATH, PUT),
    DESTROY_SUBCONTEXT(DESTROY_SUBCONTEXT_PATH, DELETE),
    LIST(LIST_PATH, GET),
    LIST_BINDINGS(LIST_BINDINGS_PATH, GET),
    LOOKUP(LOOKUP_PATH, POST),
    LOOKUP_LINK(LOOKUP_LINK_PATH, POST),
    REBIND(REBIND_PATH, PATCH),
    RENAME(RENAME_PATH, PATCH),
    UNBIND(UNBIND_PATH, DELETE);

    private final String path;
    private final HttpString method;

    RequestType(final String path, final HttpString method) {
        this.path = path;
        this.method = method;
    }

    String getPath() {
        return path;
    }

    HttpString getMethod() {
        return method;
    }

}
