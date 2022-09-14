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

import io.undertow.util.AbstractAttachable;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;

/**
 * Provides the correct {@link HttpMarshallerFactory} for creating {@link Marshaller marshallers} and
 * {@link Unmarshaller unmarshallers}. Those objects are used to read and write objects by both client
 * and server sides.
 *
 * @author Flavia Rainone
 */
interface HttpMarshallerFactoryProvider {

    /**
     * Returns the HttpMarshallerFactory for the request/response represented by
     * {@code attachable}.
     *
     * @param attachable represents a response/request exchange and contains context
     *                   data that may be needed to indicate the correct http
     *                   marshaller factory
     * @return the HttpMarshallerFactory
     */
    HttpMarshallerFactory getMarshallerFactory(AbstractAttachable attachable);

    /**
     * Returns the HttpMarshallerFactory for unmarshalling the request/response
     * represented by {@code attachable}.
     * <p>Use this method only when the factory for unmarshalling is different from
     * the one needed for marshalling.</p>
     *
     * @param attachable represents a response/request exchange and contains context
     *                   data that may be needed to indicate the correct http
     *                   marshaller factory
     * @return the HttpMarshallerFactory
     */
    HttpMarshallerFactory getUnmarshallerFactory(AbstractAttachable attachable);

    static HttpMarshallerFactoryProvider getDefaultHttpMarshallerFactoryProvider() {
        return new HttpMarshallerFactoryProvider() {
            @Override
            public HttpMarshallerFactory getMarshallerFactory(AbstractAttachable attachable) {
                return HttpMarshallerFactory.DEFAULT_FACTORY;
            }

            @Override
            public HttpMarshallerFactory getUnmarshallerFactory(AbstractAttachable attachable) {
                return HttpMarshallerFactory.DEFAULT_FACTORY;
            }
        };
    }
}