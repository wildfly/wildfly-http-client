/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022 Red Hat, Inc., and individual contributors
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

import org.wildfly.httpclient.common.ContentType;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class NamingConstants {

    // request headers
    static final ContentType VALUE = new ContentType("application/x-wf-jndi-jbmar-value", 1);
    static final ContentType EXCEPTION = new ContentType("application/x-wf-jbmar-exception", 1);

    // context path
    static final String NAMING_CONTEXT = "/naming";

    // params
    static final String NAME_PATH_PARAMETER = "name";
    static final String NEW_QUERY_PARAMETER = "new";

    // protocols
    static final String HTTP_SCHEME = "http";
    static final String HTTPS_SCHEME = "https";
    static final String JAVA_SCHEME = "java";

    // ports
    static final int HTTP_PORT = 80;
    static final int HTTPS_PORT = 443;

    private NamingConstants() {
        // forbidden instantiation
    }

}
