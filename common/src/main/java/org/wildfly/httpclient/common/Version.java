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
 * TODO: integrate this with the Protocol class in a nice way.
 *
 * @author Richard Achmatowicz
 */
public enum Version {
    VERSION_1(1),
    VERSION_2(2),
    LATEST(3)
    ;
    private final int version;

    Version(int version) {
        this.version = version;
    }

    public int getVersion() {
        return version;
    }

    public boolean since(Version version) {
        return this.version >= version.version;
    }
}
