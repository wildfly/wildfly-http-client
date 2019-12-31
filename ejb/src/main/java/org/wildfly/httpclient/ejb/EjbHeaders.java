/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
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
 * @author Stuart Douglas
 */
interface EjbHeaders {
    //request headers
    String INVOCATION_VERSION_ONE = "application/x-wf-ejb-jbmar-invocation;version=1";
    String SESSION_OPEN_VERSION_ONE = "application/x-wf-jbmar-sess-open;version=1";
    String DISCOVERY_VERSION_ONE = "application/x-wf-ejb-jbmar-discovery;version=1";
    String SESSION_OPEN = "application/x-wf-jbmar-sess-open";
    String INVOCATION = "application/x-wf-ejb-jbmar-invocation";
    String DISCOVERY = "application/x-wf-ejb-jbmar-discovery";

    //response headers
    ContentType EJB_RESPONSE_VERSION_ONE = new ContentType("application/x-wf-ejb-jbmar-response", 1);
    ContentType EJB_RESPONSE_NEW_SESSION = new ContentType("application/x-wf-ejb-jbmar-new-session", 1);
    ContentType EJB_DISCOVERY_RESPONSE_VERSION_ONE = new ContentType("application/x-wf-ejb-jbmar-discovery-response", 1);

    HttpString EJB_SESSION_ID = new HttpString("x-wf-ejb-jbmar-session-id");
    HttpString INVOCATION_ID = new HttpString("X-wf-invocation-id");
}
