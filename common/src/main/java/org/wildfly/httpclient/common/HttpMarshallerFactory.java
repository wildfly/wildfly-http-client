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

import org.jboss.marshalling.ClassNameTransformer;
import org.jboss.marshalling.ClassResolver;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.ObjectResolver;
import org.jboss.marshalling.ObjectTable;
import org.jboss.marshalling.SimpleClassResolver;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.marshalling.river.RiverMarshallerFactory;
import org.wildfly.common.annotation.NotNull;

import java.io.IOException;

/**
 * Creates {@link Marshaller} objects for reading and writing requests and responses objects as bytes.
 *
 * @author Richard Opalka
 * @author Flavia Rainone
 */
public final class HttpMarshallerFactory {
    /**
     * The default HTTP Marshaller factory, creates Marshallers using a simple {@link MarshallingConfiguration}
     * with {@link MarshallingConfiguration#setVersion(int) version} {@code 2}.
     */
    static final HttpMarshallerFactory DEFAULT_FACTORY = new HttpMarshallerFactory(null);

    // internal river marshaller factory
    private static final MarshallerFactory RIVER_MARSHALLER_FACTORY = new RiverMarshallerFactory();
    // default marshalling configuration: prevents the creation of empty configurations at every
    // request
    private final MarshallingConfiguration defaultConfiguration;
    // class name transformer to be used by this factory
    private final ClassNameTransformer classNameTransformer;

    HttpMarshallerFactory(ClassNameTransformer classNameTransformer) {
        this.classNameTransformer = classNameTransformer;
        this.defaultConfiguration = createMarshallingConfiguration();
        this.defaultConfiguration.setClassNameTransformer(classNameTransformer);
    }

    /**
     * Creates a simple {@code Marshaller}.
     *
     * @return a marshaller
     * @throws IOException if an I/O error occurs during marshaller creation
     */
    public Marshaller createMarshaller() throws IOException {
        return RIVER_MARSHALLER_FACTORY.createMarshaller(defaultConfiguration);
    }

    /**
     * Creates a {@code Marshaller} configured with an object resolver.
     *
     * @param resolver responsible for substituting objects when marshalling.
     * @return the marshaller
     * @throws IOException if an I/O error occurs during marshaller creation
     */
    public Marshaller createMarshaller(@NotNull ObjectResolver resolver) throws IOException {
        MarshallingConfiguration marshallingConfiguration = createMarshallingConfiguration();
        marshallingConfiguration.setObjectResolver(resolver);
        return RIVER_MARSHALLER_FACTORY.createMarshaller(marshallingConfiguration);
    }

    /**
     * Creates a {@code Marshaller} configured with an object table.
     *
     * @param table the object table used by the marshaller
     * @return the marshaller
     * @throws IOException if an I/O error occurs during marshaller creation
     */
    public Marshaller createMarshaller(@NotNull ObjectTable table) throws IOException {
        MarshallingConfiguration marshallingConfiguration = createMarshallingConfiguration();
        marshallingConfiguration.setObjectTable(table);
        return RIVER_MARSHALLER_FACTORY.createMarshaller(marshallingConfiguration);
    }

    /**
     * Creates a {@code Marshaller} configured with an object resolver and an object table.
     *
     * @param resolver responsible for substituting objects when marshalling
     * @param table the object table used by the marshaller
     * @return the marshaller
     * @throws IOException if an I/O error occurs during marshaller creation
     */
    public Marshaller createMarshaller(@NotNull ObjectResolver resolver, @NotNull ObjectTable table) throws IOException {
        MarshallingConfiguration marshallingConfiguration = createMarshallingConfiguration();
        marshallingConfiguration.setObjectResolver(resolver);
        marshallingConfiguration.setObjectTable(table);
        return RIVER_MARSHALLER_FACTORY.createMarshaller(marshallingConfiguration);
    }

    /**
     * Creates a {@code Marshaller} configured with a class resolver and an object table.
     *
     * @param resolver class annotator and resolver
     * @param table the object table used by the marshaller
     * @return the marshaller
     * @throws IOException if an I/O error occurs during marshaller creation
     */
    public Marshaller createMarshaller(@NotNull ClassResolver resolver, @NotNull ObjectTable table) throws IOException {
        MarshallingConfiguration marshallingConfiguration = createMarshallingConfiguration();
        marshallingConfiguration.setClassResolver(resolver);
        marshallingConfiguration.setObjectTable(table);
        return RIVER_MARSHALLER_FACTORY.createMarshaller(marshallingConfiguration);
    }

    /**
     * Creates a simple {@code Unmarshaller}.
     *
     * @return an unmarshaller
     * @throws IOException if an I/O error occurs during unmarshaller creation
     */
    public Unmarshaller createUnmarshaller() throws IOException {
        return RIVER_MARSHALLER_FACTORY.createUnmarshaller(defaultConfiguration);
    }

    /**
     * Creates an {@code Unmarshaller} configured with an object resolver.
     *
     * @param resolver responsible for substituting objects when unmarshalling.
     * @return the unmarshaller
     * @throws IOException if an I/O error occurs during unmarshaller creation
     */
    public Unmarshaller createUnmarshaller(@NotNull ObjectResolver resolver) throws IOException {
        MarshallingConfiguration marshallingConfiguration = createMarshallingConfiguration();
        marshallingConfiguration.setObjectResolver(resolver);
        return RIVER_MARSHALLER_FACTORY.createUnmarshaller(marshallingConfiguration);
    }

    /**
     * Creates an {@code Unmarshaller} configured with a class resolver.
     *
     * @param resolver class annotator and resolver
     * @return the unmarshaller
     * @throws IOException if an I/O error occurs during unmarshaller creation
     */
    public Unmarshaller createUnmarshaller(@NotNull ClassResolver resolver) throws IOException {
        MarshallingConfiguration marshallingConfiguration = createMarshallingConfiguration();
        marshallingConfiguration.setClassResolver(resolver);
        return RIVER_MARSHALLER_FACTORY.createUnmarshaller(marshallingConfiguration);
    }

    /**
     * Creates an {@code Unmarshaller} configured with a class resolver.
     *
     * @param cl the class loader that will be used by the class resolver
     * @return the unmarshaller
     * @throws IOException if an I/O error occurs during unmarshaller creation
     */
    public Unmarshaller createUnmarshaller(@NotNull final ClassLoader cl) throws IOException {
        MarshallingConfiguration marshallingConfiguration = createMarshallingConfiguration();
        marshallingConfiguration.setClassResolver(new SimpleClassResolver(cl));
        return RIVER_MARSHALLER_FACTORY.createUnmarshaller(marshallingConfiguration);
    }

    /**
     * Creates an {@code Unmarshaller} configured with an object table.
     *
     * @param table the object table used by the marshaller
     * @return the unmarshaller
     * @throws IOException if an I/O error occurs during unmarshaller creation
     */
    public Unmarshaller createUnmarshaller(@NotNull ObjectTable table) throws IOException {
        MarshallingConfiguration marshallingConfiguration = createMarshallingConfiguration();
        marshallingConfiguration.setObjectTable(table);
        return RIVER_MARSHALLER_FACTORY.createUnmarshaller(marshallingConfiguration);
    }

    /**
     * Creates an {@code Unmarshaller} configured with an object resolver and an object table.
     *
     * @param resolver responsible for substituting objects when unmarshalling.
     * @param table the object table used by the marshaller
     * @return the unmarshaller
     * @throws IOException if an I/O error occurs during unmarshaller creation
     */
    public Unmarshaller createUnmarshaller(@NotNull ObjectResolver resolver, @NotNull ObjectTable table) throws IOException {
        MarshallingConfiguration marshallingConfiguration = createMarshallingConfiguration();
        marshallingConfiguration.setObjectResolver(resolver);
        marshallingConfiguration.setObjectTable(table);
        return RIVER_MARSHALLER_FACTORY.createUnmarshaller(marshallingConfiguration);
    }

    /**
     * Creates an {@code Unmarshaller} configured with a class resolver and an object table.
     *
     * @param resolver class annotator and resolver
     * @param table the object table used by the marshaller
     * @return the unmarshaller
     * @throws IOException if an I/O error occurs during unmarshaller creation
     */
    public Unmarshaller createUnmarshaller(@NotNull ClassResolver resolver, @NotNull ObjectTable table) throws IOException {
        MarshallingConfiguration marshallingConfiguration = createMarshallingConfiguration();
        marshallingConfiguration.setClassResolver(resolver);
        marshallingConfiguration.setObjectTable(table);
        return RIVER_MARSHALLER_FACTORY.createUnmarshaller(marshallingConfiguration);
    }

    private MarshallingConfiguration createMarshallingConfiguration() {
        final MarshallingConfiguration config = new MarshallingConfiguration();
        config.setVersion(2);
        config.setClassNameTransformer(classNameTransformer);
        return config;
    }
}
