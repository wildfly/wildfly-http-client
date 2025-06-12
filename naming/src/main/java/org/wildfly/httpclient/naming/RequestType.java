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

/**
 * HTTP JNDI module invocation types. Each invocation type has {@linkplain #getName() name}, {@linkplain #getMethod() method}
 * and {@linkplain #getPath() path}. An invocation can be one of the following types:
 * <ul>
 *     <li>{@link #BIND}<br>
 *     Binds a name to an object.
 *     </li>
 *     <li>{@link #CREATE_SUBCONTEXT}<br>
 *     Creates and binds a new context.
 *     </li>
 *     <li>{@link #DESTROY_SUBCONTEXT}<br>
 *     Destroys the named context and removes it from the namespace.
 *     </li>
 *     <li>{@link #LIST}<br>
 *     Enumerates the names bound in the named context, along with the class names of objects bound to them.
 *     </li>
 *     <li>{@link #LIST_BINDINGS}<br>
 *     Enumerates the names bound in the named context, along with the objects bound to them.
 *     </li>
 *     <li>{@link #LOOKUP}<br>
 *     Retrieves the named object.
 *     </li>
 *     <li>{@link #LOOKUP_LINK}<br>
 *     Retrieves the named object, following links except for the terminal atomic component of the name.
 *     </li>
 *     <li>{@link #REBIND}<br>
 *     Binds a name to an object, overwriting any existing binding.
 *     </li>
 *     <li>{@link #RENAME}<br>
 *     Binds a new name to the object bound to an old name, and unbinds the old name.
 *     </li>
 *     <li>{@link #UNBIND}<br>
 *     Unbinds the named object.
 *     </li>
 * </ul>
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
enum RequestType implements org.wildfly.httpclient.common.RequestType {

    /**
     * {@code BIND} invocation type: used to bind a name to an object via HTTP protocol.
     */
    BIND(PUT, "/bind"),
    /**
     * {@code CREATE_SUBCONTEXT} invocation type: used to create and bind a new context via HTTP protocol.
     */
    CREATE_SUBCONTEXT(PUT, "/create-subcontext"),
    /**
     * {@code DESTROY_SUBCONTEXT} invocation type: used to destroy the named context and remove it from the namespace via HTTP protocol.
     */
    DESTROY_SUBCONTEXT(DELETE, "/dest-subctx"),
    /**
     * {@code LIST} invocation type: used to enumerate the names bound in the named context, along with the class names of objects bound to them via HTTP protocol.
     */
    LIST(GET, "/list"),
    /**
     * {@code LIST_BINDINGS} invocation type: used to numerate the names bound in the named context, along with the objects bound to them via HTTP protocol.
     */
    LIST_BINDINGS(GET, "/list-bindings"),
    /**
     * {@code LOOKUP} invocation type: used to retrieve the named object via HTTP protocol.
     */
    LOOKUP(POST, "/lookup"),
    /**
     * {@code LOOKUP_LINK} invocation type: used to retrieves the named object, following links via HTTP protocol.
     */
    LOOKUP_LINK(POST, "/lookuplink"),
    /**
     * {@code REBIND} invocation type: used to bind a name to an object, overwriting any existing binding via HTTP protocol.
     */
    REBIND(PATCH, "/rebind"),
    /**
     * {@code RENAME} invocation type: used to bind a new name to the object bound to an old name, and unbind the old name via HTTP protocol.
     */
    RENAME(PATCH, "/rename"),
    /**
     * {@code UNBIND} invocation type: used to unbind the named object via HTTP protocol.
     */
    UNBIND(DELETE, "/unbind");

    private final HttpString method;
    private final String path;

    RequestType(final HttpString method, final String path) {
        this.method = method;
        this.path = path;
    }

    /**
     * Returns the name of this invocation.
     * @return this invocation name
     */
    @Override
    public final String getName() {
        return name();
    }

    /**
     * Returns the HTTP request method of this invocation.
     * @return this invocation HTTP request method.
     */
    @Override
    public final HttpString getMethod() {
        return method;
    }

    /**
     * Returns the HTTP request prefix path of this invocation.
     * @return this invocation HTTP request prefix path.
     */
    @Override
    public final String getPath() {
        return path;
    }

}
