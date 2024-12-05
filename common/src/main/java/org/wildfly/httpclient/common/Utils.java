/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2024 Red Hat, Inc., and individual contributors
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

import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.ObjectResolver;
import org.jboss.marshalling.Unmarshaller;

import java.util.concurrent.CompletableFuture;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class Utils {

    private Utils() {
        // forbidden instantiation
    }

    public static <T> Marshaller newMarshaller(final ObjectResolver objectResolver, final HttpMarshallerFactory factory, final CompletableFuture<T> failureHandler) {
        Marshaller marshaller = null;
        try {
            marshaller = objectResolver != null ? factory.createMarshaller(objectResolver) : factory.createMarshaller();
        } catch (Exception e) {
            failureHandler.completeExceptionally(e);
        }
        return marshaller;
    }

    public static <T> Unmarshaller newUnmarshaller(final ObjectResolver objectResolver, final HttpMarshallerFactory factory, final CompletableFuture<T> failureHandler) {
        Unmarshaller unmarshaller = null;
        try {
            unmarshaller = objectResolver != null ? factory.createUnmarshaller(objectResolver) : factory.createUnmarshaller();
        } catch (Exception e) {
            failureHandler.completeExceptionally(e);
        }
        return unmarshaller;
    }

    public static <T> Marshaller newMarshaller(final HttpMarshallerFactory factory, final CompletableFuture<T> failureHandler) {
        Marshaller marshaller = null;
        try {
            marshaller = factory.createMarshaller();
        } catch (Exception e) {
            failureHandler.completeExceptionally(e);
        }
        return marshaller;
    }

    public static <T> Unmarshaller newUnmarshaller(final HttpMarshallerFactory factory, final CompletableFuture<T> failureHandler) {
        Unmarshaller unmarshaller = null;
        try {
            unmarshaller = factory.createUnmarshaller();
        } catch (Exception e) {
            failureHandler.completeExceptionally(e);
        }
        return unmarshaller;
    }

}
