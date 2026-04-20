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

import static org.wildfly.httpclient.common.Protocol.VERSION_ONE_PATH;
import static org.wildfly.httpclient.common.Protocol.VERSION_TWO_PATH;

import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.PathHandler;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * Mode configuration for http services.
 * <p>
 * The http services are internal server services responsible for handling client requests, and they are
 * simple pojos conventionally named Http*Service.
 *
 * @author Flavia Rainone
 * @author <a href="mailto:ropalka@ibm.com">Richard Opalka</a>
 */
public final class HttpServiceConfig {

    private static final boolean EE_NAMESPACE_INTEROPERABLE_MODE = Boolean.parseBoolean(
            WildFlySecurityManager.getPropertyPrivileged("org.wildfly.ee.namespace.interop", "false"));
    /**
     * Indicates EE namespace interoperable mode is disabled.
     */
    static final HttpServiceConfig DEFAULT = new HttpServiceConfig();
    /**
     * Indicates EE namespace interoperable mode is enabled.
     */
    static final HttpServiceConfig INTEROP = new HttpServiceConfig();
    private static volatile HttpServiceConfig currentMode;

    private HttpServiceConfig() {
        // forbidden instantiation
    }

    /**
     * Returns the default configuration.
     *
     * @return the configuration for http services
     */
    public static HttpServiceConfig getInstance() {
        if (currentMode == null) {
            synchronized (HttpServiceConfig.class) {
                if (currentMode == null) {
                    if (EE_NAMESPACE_INTEROPERABLE_MODE) {
                        HttpClientMessages.MESSAGES.javaeeToJakartaeeBackwardCompatibilityLayerInstalled();
                        currentMode = INTEROP;
                    } else {
                        currentMode = DEFAULT;
                    }
                }
            }
        }
        return currentMode;
    }

    /**
     * Configures config factory.
     *
     * @throws IllegalStateException if factory is already configured
     */
    public static void initialize(final boolean interopMode) {
        if (currentMode != null) throw new IllegalStateException();
        synchronized (HttpServiceConfig.class) {
            if (currentMode != null) throw new IllegalStateException();
            if (interopMode) {
                HttpClientMessages.MESSAGES.javaeeToJakartaeeBackwardCompatibilityLayerInstalled();
                currentMode = INTEROP;
            } else {
                currentMode = DEFAULT;
            }
        }
    }

    /**
     * Wraps the http service handler. Should be applied to all http handlers configured by
     * a http service.
     *
     * @param handler responsible for handling the HTTP service requests directed to a specific
     *                URI
     * @return the HttpHandler that should be provided to Undertow and associated with the HTTP
     *         service URI. The resulting handler is a wrapper that will add any necessary actions
     *         before invoking the inner {@code handler}.
     */
    public HttpHandler wrap(final HttpHandler handler) {
        final PathHandler versionPathHandler = new PathHandler();
        versionPathHandler.addPrefixPath(VERSION_ONE_PATH, handler);
        versionPathHandler.addPrefixPath(VERSION_TWO_PATH, handler);
        return versionPathHandler;
    }

}
