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
import static org.wildfly.httpclient.ejb.EjbConstants.EJB_CANCEL_PATH;
import static org.wildfly.httpclient.ejb.EjbConstants.EJB_DISCOVER_PATH;
import static org.wildfly.httpclient.ejb.EjbConstants.EJB_INVOKE_PATH;
import static org.wildfly.httpclient.ejb.EjbConstants.EJB_OPEN_PATH;

import io.undertow.util.HttpString;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
enum RequestType {

    START_EJB_INVOCATION(POST, EJB_INVOKE_PATH),
    CANCEL_EJB_INVOCATION(DELETE, EJB_CANCEL_PATH),
    CREATE_SESSION_EJB(POST, EJB_OPEN_PATH),
    DISCOVER_EJB(GET, EJB_DISCOVER_PATH);

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
