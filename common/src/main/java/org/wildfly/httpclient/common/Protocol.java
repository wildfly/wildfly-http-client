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

/**
 * Protocol version constants.
 *
 * @author Flavia Rainone
 */
public class Protocol {

    // the last version compatible with javax ee namespace
    static final int JAVAEE_PROTOCOL_VERSION = 1;
    // the first version compatible with jakarta ee namespace
    static final int JAKARTAEE_PROTOCOL_VERSION = 2;
    // version one path
    static final String VERSION_ONE_PATH = "/v1";
    // version two path
    static final String VERSION_TWO_PATH = "/v2";
    // version path prefix
    public static final String VERSION_PATH="/v";
    // latest protocol version
    public static int LATEST = 2;

    private Protocol() {
    }
}
