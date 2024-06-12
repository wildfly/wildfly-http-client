/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.httpclient.ejb;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.jboss.ejb.client.EJBModuleIdentifier;
import org.jboss.ejb.server.Association;
import org.jboss.ejb.server.ModuleAvailabilityListener;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
import org.wildfly.httpclient.common.HttpServiceConfig;
import org.wildfly.httpclient.common.NoFlushByteOutput;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Http handler for discovery requests.
 *
 * @author <a href="mailto:tadamski@redhat.com">Tomasz Adamski</a>
 */

public class HttpDiscoveryHandler extends RemoteHTTPHandler {

    private final Set<EJBModuleIdentifier> availableModules = new HashSet<>();
    private final HttpServiceConfig httpServiceConfig;

    @Deprecated
    public HttpDiscoveryHandler(ExecutorService executorService, Association association) {
        this (executorService, association, HttpServiceConfig.getInstance());
    }

    public HttpDiscoveryHandler(ExecutorService executorService, Association association, HttpServiceConfig httpServiceConfig) {
        super(executorService);
        association.registerModuleAvailabilityListener(new ModuleAvailabilityListener() {
            @Override
            public void moduleAvailable(List<EJBModuleIdentifier> modules) {
                availableModules.addAll(modules);
            }

            @Override
            public void moduleUnavailable(List<EJBModuleIdentifier> modules) {
                availableModules.removeAll(modules);
            }
        });
        this.httpServiceConfig = httpServiceConfig;
    }

    @Override
    protected void handleInternal(HttpServerExchange exchange) throws Exception {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, EjbConstants.EJB_DISCOVERY_RESPONSE.toString());
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Marshaller marshaller = httpServiceConfig.getHttpMarshallerFactory(exchange)
                .createMarshaller(HttpProtocolV1ObjectTable.INSTANCE);
        marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(out)));
        marshaller.writeInt(availableModules.size());
        for (EJBModuleIdentifier ejbModuleIdentifier : availableModules) {
            marshaller.writeObject(ejbModuleIdentifier);
        }
        marshaller.finish();
        marshaller.flush();
        exchange.getResponseSender().send(ByteBuffer.wrap(out.toByteArray()));
    }
}
