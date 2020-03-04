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

import io.undertow.client.ClientRequest;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.jboss.ejb.client.EJBClientConnection;
import org.jboss.ejb.client.EJBClientContext;
import org.jboss.ejb.client.EJBModuleIdentifier;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.discovery.AttributeValue;
import org.wildfly.discovery.FilterSpec;
import org.wildfly.discovery.ServiceType;
import org.wildfly.discovery.ServiceURL;
import org.wildfly.discovery.spi.DiscoveryProvider;
import org.wildfly.discovery.spi.DiscoveryRequest;
import org.wildfly.discovery.spi.DiscoveryResult;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.httpclient.common.WildflyHttpContext;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;
import org.xnio.IoUtils;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.security.AccessController.doPrivileged;
import static org.jboss.ejb.client.EJBClientContext.getCurrent;

/**
 * @author <a href="mailto:tadamski@redhat.com">Tomasz Adamski</a>
 */

public final class HttpEJBDiscoveryProvider implements DiscoveryProvider {

    private static final String DISCOVERY_PATH =  "/ejb/v1/discover";
    private static final String DISCOVERY_ACCEPT = "application/x-wf-ejb-jbmar-discovery-response;version=1,application/x-wf-jbmar-exception;version=1";

    private static final AuthenticationContextConfigurationClient AUTH_CONFIGURATION_CLIENT = doPrivileged(AuthenticationContextConfigurationClient.ACTION);

    private Set<ServiceURL> serviceURLCache = new HashSet<>();
    private AtomicBoolean shouldRefreshCache = new AtomicBoolean(true);

    HttpEJBDiscoveryProvider() {
    }

    public DiscoveryRequest discover(final ServiceType serviceType, final FilterSpec filterSpec, final DiscoveryResult discoveryResult) {
        final EJBClientContext ejbClientContext = getCurrent();

        if (shouldRefreshCache.get()) {
            refreshCache(ejbClientContext);
        }

        searchCache(discoveryResult, filterSpec);

        return DiscoveryRequest.NULL;
    }

    private boolean supportsScheme(String s) {
        switch (s) {
            case "http":
            case "https":
                return true;
        }
        return false;
    }

    private void searchCache(final DiscoveryResult discoveryResult, final FilterSpec filterSpec) {
        for (ServiceURL serviceURL : serviceURLCache) {
            if (serviceURL.satisfies(filterSpec)) {
                discoveryResult.addMatch(serviceURL.getLocationURI());
            }
        }
        discoveryResult.complete();
    }

    private void refreshCache(final EJBClientContext ejbClientContext){
        serviceURLCache.clear();
        final List<EJBClientConnection> httpConnections = ejbClientContext.getConfiguredConnections().stream().filter(
                (connection) -> supportsScheme(connection.getDestination().getScheme())
        ).collect(Collectors.toList());
        final CountDownLatch outstandingLatch = new CountDownLatch(httpConnections.size());
        for (EJBClientConnection connection : httpConnections) {
            disoverFromConnection(connection, outstandingLatch);
        }
        try {
            outstandingLatch.await();
            shouldRefreshCache.set(false);
        } catch(InterruptedException e){
            EjbHttpClientMessages.MESSAGES.httpDiscoveryInterrupted(e);
        }
        shouldRefreshCache.set(false);
    }

    private void disoverFromConnection(final EJBClientConnection connection, final CountDownLatch outstandingLatch) {
        final URI newUri = connection.getDestination();

        HttpTargetContext targetContext = WildflyHttpContext.getCurrent().getTargetContext(newUri);
        AuthenticationContext authenticationContext = AuthenticationContext.captureCurrent();

        final AuthenticationContextConfigurationClient client = AUTH_CONFIGURATION_CLIENT;
        final SSLContext sslContext;
        try {
            sslContext = client.getSSLContext(newUri, authenticationContext);
        } catch (GeneralSecurityException e) {
            return;
        }

        final AuthenticationConfiguration authenticationConfiguration = client.getAuthenticationConfiguration(newUri, authenticationContext, -1, "ejb", "jboss");

        ClientRequest request = new ClientRequest()
                .setPath(targetContext.getUri().getPath() + DISCOVERY_PATH)
                .setMethod(Methods.GET);
        request.getRequestHeaders().add(Headers.ACCEPT, DISCOVERY_ACCEPT);

        targetContext.sendRequest(request, sslContext, authenticationConfiguration, null,
                ((result, response, closeable) -> {
                    try {
                        final MarshallingConfiguration marshallingConfiguration = createMarshallingConfig();
                        final Unmarshaller unmarshaller = targetContext.createUnmarshaller(marshallingConfiguration);

                        unmarshaller.start(new InputStreamByteInput(result));
                        int size = unmarshaller.readInt();

                        for (int i = 0; i < size; i++) {
                            EJBModuleIdentifier ejbModuleIdentifier = (EJBModuleIdentifier) unmarshaller.readObject();
                            ServiceURL url = createServiceURL(newUri, ejbModuleIdentifier);
                            serviceURLCache.add(url);
                        }
                    } catch (Exception e) {
                        EjbHttpClientMessages.MESSAGES.unableToPerformEjbDiscovery(e);
                    } finally {
                        outstandingLatch.countDown();
                        IoUtils.safeClose(closeable);
                    }
                }),
                (e) -> {
                    EjbHttpClientMessages.MESSAGES.unableToPerformEjbDiscovery(e);
                    outstandingLatch.countDown();
                },
                EjbHeaders.EJB_DISCOVERY_RESPONSE_VERSION_ONE, null);
    }

    private MarshallingConfiguration createMarshallingConfig() {
        final MarshallingConfiguration marshallingConfiguration = new MarshallingConfiguration();
        marshallingConfiguration.setObjectTable(HttpProtocolV1ObjectTable.INSTANCE);
        marshallingConfiguration.setVersion(2);
        return marshallingConfiguration;
    }

    private ServiceURL createServiceURL(final URI newUri, final EJBModuleIdentifier moduleIdentifier) {
        final ServiceURL.Builder builder = new ServiceURL.Builder();
        builder.setUri(newUri);
        builder.setAbstractType(EJBClientContext.EJB_SERVICE_TYPE.getAbstractType());
        builder.setAbstractTypeAuthority(EJBClientContext.EJB_SERVICE_TYPE.getAbstractTypeAuthority());

        final String appName = moduleIdentifier.getAppName();
        final String moduleName = moduleIdentifier.getModuleName();
        final String distinctName = moduleIdentifier.getDistinctName();
        if (distinctName.isEmpty()) {
            if (appName.isEmpty()) {
                builder.addAttribute(EJBClientContext.FILTER_ATTR_EJB_MODULE, AttributeValue.fromString(moduleName));
            } else {
                builder.addAttribute(EJBClientContext.FILTER_ATTR_EJB_MODULE, AttributeValue.fromString(appName + "/" + moduleName));
            }
        } else {
            if (appName.isEmpty()) {
                builder.addAttribute(EJBClientContext.FILTER_ATTR_EJB_MODULE_DISTINCT, AttributeValue.fromString(moduleName + "/" + distinctName));
            } else {
                builder.addAttribute(EJBClientContext.FILTER_ATTR_EJB_MODULE_DISTINCT, AttributeValue.fromString(appName + "/" + moduleName + "/" + distinctName));
            }
        }
        return builder.create();
    }

    @Override
    public void processMissingTarget(URI location, Exception cause) {
        shouldRefreshCache.set(true);
    }
}

