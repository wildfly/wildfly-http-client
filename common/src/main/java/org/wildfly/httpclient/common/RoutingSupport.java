/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2023 Red Hat, Inc., and individual contributors
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
package org.wildfly.httpclient.common;

import java.util.Map;

/**
 * Exposes the mechanism for parsing and formation routing information from/into a requested session identifier.
 *
 * @author Paul Ferraro
 */
public interface RoutingSupport {
    /**
     * Parses the routing information from the specified session identifier.
     *
     * @param requestedSessionId the requested session identifier.
     * @return a map entry containing the session ID and routing information as the key and value, respectively.
     */
    Map.Entry<CharSequence, CharSequence> parse(CharSequence requestedSessionId);

    /**
     * Formats the specified session identifier and route identifier into a single identifier.
     *
     * @param sessionId a session identifier
     * @param route     a route identifier.
     * @return a single identifier containing the specified session identifier and routing identifier.
     */
    CharSequence format(CharSequence sessionId, CharSequence route);
}


