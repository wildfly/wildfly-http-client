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

import org.jboss.marshalling.ClassResolver;
import org.jboss.marshalling.ClassNameTransformer;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.marshalling.river.RiverMarshallerFactory;
import org.wildfly.security.manager.WildFlySecurityManager;

import java.io.IOException;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class MarshallingHelper {

    // Batavia transformer sensible constant - it can start with either "javax." or "jakarta." if transformation was performed
    private static final String VARIABLE_CONSTANT = "javax.ejb.FAKE_STRING";
    private static final boolean JAKARTAEE_ENV_DETECTED = VARIABLE_CONSTANT.startsWith("jakarta");

    private static final boolean BC_MODE = Boolean.parseBoolean(
            WildFlySecurityManager.getPropertyPrivileged("org.wildfly.httpclient.javaee2jakartaee.enable", "true"));
    private static final MarshallerFactory RIVER_MARSHALLER_FACTORY = new RiverMarshallerFactory();

    private MarshallingHelper() {
        // forbidden instantiation
    }

    public static MarshallingConfiguration newConfig() {
        return newConfig(null);
    }

    public static MarshallingConfiguration newConfig(final ClassResolver resolver) {
        final MarshallingConfiguration config = new MarshallingConfiguration();
        config.setVersion(2);
        config.setClassResolver(resolver);
        if (JAKARTAEE_ENV_DETECTED && BC_MODE) {
            // for BC we need to translate classes from JavaEE to Jakarta API and vice versa
            config.setClassNameTransformer(ClassNameTransformer.JAVAEE_TO_JAKARTAEE);
        }
        return config;
    }

    public static Marshaller newMarshaller(final MarshallingConfiguration config) throws IOException {
        return RIVER_MARSHALLER_FACTORY.createMarshaller(config);
    }

    public static Unmarshaller newUnmarshaller(final MarshallingConfiguration config) throws IOException {
        return RIVER_MARSHALLER_FACTORY.createUnmarshaller(config);
    }

}
