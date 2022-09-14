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
package org.wildfly.httpclient.common;

import io.undertow.connector.ByteBufferPool;
import org.xnio.OptionMap;
import org.xnio.XnioWorker;

/**
 * Creates the {@link HttpConnectionPool connection pool} used by the client side.
 *
 * @author Flavia Rainone
 */
interface HttpConnectionPoolFactory {

    /**
     * Creates a http connection pool for a specific {@link HostPool set of host addresses}.
     * Used by the client side.
     *
     * @param maxConnections          the maximum number of connections
     * @param maxStreamsPerConnection the maximum number of open streams per connection
     * @param worker                  the worker used for executing tasks
     * @param byteBufferPool          the byte buffer pool used by the connection channels
     * @param options                 XNIO configuration for the connections
     * @param hostPool                the set of host addresses associated with an URI that will be
     *                                used as targets for the connections in the pool
     * @param connectionIdleTimeout   the idle timeout, after which any idle connection in the pool will
     *                                be closed
     * @return the connection pool
     */
    HttpConnectionPool createHttpConnectionPool(int maxConnections, int maxStreamsPerConnection, XnioWorker worker, ByteBufferPool byteBufferPool, OptionMap options, HostPool hostPool, long connectionIdleTimeout);

    /**
     * Returns the default HttpConnectionPoolFactory.
     *
     * @return the default http connection pool factory
     */
    static HttpConnectionPoolFactory getDefault() {
        return HttpConnectionPool::new;
    }
}