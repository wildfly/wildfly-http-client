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
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
enum RequestType {

    START_EJB_INVOCATION(POST),
    CANCEL_EJB_INVOCATION(DELETE),
    CREATE_SESSION_EJB(POST),
    DISCOVER_EJB(GET);

    private final HttpString method;

    RequestType(final HttpString method) {
        this.method = method;
    }

    HttpString getMethod() {
        return method;
    }

}
