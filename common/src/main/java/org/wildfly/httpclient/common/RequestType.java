/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2025 Red Hat, Inc., and individual contributors
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

import io.undertow.util.HttpString;

/**
 * Module specific invocation types. Each EJB, JNDI and TXN module will define its own invocation types.
 * Every module specific invocation type will implement {@linkplain #getName() name}, {@linkplain #getMethod() method}
 * and {@linkplain #getPath() path} methods.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public interface RequestType {
    /**
     * Returns the name of this invocation.
     * @return this invocation name
     */
    String getName();

    /**
     * Returns the HTTP request method of this invocation.
     * @return this invocation HTTP request method
     */
    HttpString getMethod();

    /**
     * Returns the HTTP request prefix path of this invocation.
     * @return this invocation HTTP request prefix path
     */
    String getPath();
}
