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

/*
 * Versioning enum for HttpHandler implementations.
 *
 * TODO: due to the way EENamespaceInteroperability.createInteroperabilityHandler(HttpHandler...) works,
 * the original protocol versions JAVAEE_PROTOCOL_VERSION and JAKARTA_PROTOCOL_VERSION need to share
 * the same handler instance, so it was not possible to match protocol handler indexes to protocol versions
 * in a 1-to-1 manner. In order to avoid a confusing protocol version to handler version mismatch, they share
 * the handler installed at index 2.
 * TODO: integrate this with the Protocol class in a nice way.
 *
 * @author Richard Achmatowicz
 */
public enum HandlerVersion {
    EARLIEST(2),
    VERSION_1(2),
    VERSION_2(2),
    LATEST(3)
    ;
    private final int version;

    HandlerVersion(int version) {
        this.version = version;
    }

    public int getVersion() {
        return version;
    }

    public boolean since(HandlerVersion version) {
        return this.version >= version.version;
    }
}
