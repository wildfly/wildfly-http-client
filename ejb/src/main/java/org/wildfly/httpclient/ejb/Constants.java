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

package org.wildfly.httpclient.ejb;

import org.wildfly.httpclient.common.ContentType;
import io.undertow.util.HttpString;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class Constants {

    // request headers
    static final ContentType INVOCATION_ACCEPT = new ContentType("application/x-wf-ejb-response", 1);
    static final ContentType INVOCATION = new ContentType("application/x-wf-ejb-jbmar-invocation", 1);
    static final ContentType SESSION_OPEN = new ContentType("application/x-wf-jbmar-sess-open", 1);
    static final ContentType EJB_EXCEPTION = new ContentType("application/x-wf-jbmar-exception", 1);

    // response headers
    static final ContentType EJB_RESPONSE = new ContentType("application/x-wf-ejb-jbmar-response", 1);
    static final ContentType EJB_RESPONSE_NEW_SESSION = new ContentType("application/x-wf-ejb-jbmar-new-session", 1);
    static final ContentType EJB_DISCOVERY_RESPONSE = new ContentType("application/x-wf-ejb-jbmar-discovery-response", 1);

    static final HttpString EJB_SESSION_ID = new HttpString("x-wf-ejb-jbmar-session-id");
    static final HttpString INVOCATION_ID = new HttpString("X-wf-invocation-id");

    // cookies
    static final String JSESSIONID_COOKIE_NAME = "JSESSIONID";

    // protocols
    static final String HTTP_SCHEME = "http";
    static final String HTTPS_SCHEME = "https";

    // ports
    static final int HTTP_PORT = 80;
    static final int HTTPS_PORT = 443;

    private Constants() {
        // forbidden instantiation
    }

}
