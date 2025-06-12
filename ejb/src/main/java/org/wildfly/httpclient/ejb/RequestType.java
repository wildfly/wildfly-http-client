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

package org.wildfly.httpclient.ejb;

import static io.undertow.util.Methods.DELETE;
import static io.undertow.util.Methods.GET;
import static io.undertow.util.Methods.POST;

import io.undertow.util.HttpString;

/**
 * HTTP EJB module invocation types. Each invocation type has {@linkplain #getName() name}, {@linkplain #getMethod() method}
 * and {@linkplain #getPath() path}. An invocation can be one of the following types:
 * <ul>
 *     <li>{@link #INVOKE}<br>
 *     Start EJB method invocation.
 *     </li>
 *     <li>{@link #CANCEL}<br>
 *     Cancel EJB method invocation.
 *     </li>
 *     <li>{@link #CREATE_SESSION}<br>
 *     Create EJB session bean.
 *     </li>
 *     <li>{@link #DISCOVER}<br>
 *     Discover available EJB beans.
 *     </li>
 * </ul>
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
enum RequestType implements org.wildfly.httpclient.common.RequestType {

    /**
     * {@code INVOKE} invocation type: used to start EJB method invocation via HTTP protocol.
     */
    INVOKE(POST, "/invoke"),
    /**
     * {@code CANCEL} invocation type: used to cancel EJB method invocation via HTTP protocol.
     */
    CANCEL(DELETE, "/cancel"),
    /**
     * {@code OPEN} invocation type: used to create EJB session bean via HTTP protocol.
     */
    CREATE_SESSION(POST, "/open"),
    /**
     * {@code DISCOVER} invocation type: used to discover available EJB beans via HTTP protocol.
     */
    DISCOVER(GET, "/discover");

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
