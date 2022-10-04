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
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.marshalling.river.RiverMarshallerFactory;

import java.io.IOException;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class MarshallingHelper {

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
        return config;
    }

    public static Marshaller newMarshaller(final MarshallingConfiguration config) throws IOException {
        return RIVER_MARSHALLER_FACTORY.createMarshaller(config);
    }

    public static Unmarshaller newUnmarshaller(final MarshallingConfiguration config) throws IOException {
        return RIVER_MARSHALLER_FACTORY.createUnmarshaller(config);
    }

}
