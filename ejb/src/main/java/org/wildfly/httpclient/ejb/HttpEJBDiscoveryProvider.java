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
import org.jboss.ejb.client.EJBClientConnection;
import org.jboss.ejb.client.EJBClientContext;
import org.jboss.ejb.client.EJBModuleIdentifier;
import org.jboss.marshalling.InputStreamByteInput;
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
import org.wildfly.security.manager.WildFlySecurityManager;
import org.xnio.IoUtils;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.security.AccessController.doPrivileged;
import static org.jboss.ejb.client.EJBClientContext.getCurrent;
import static org.wildfly.httpclient.ejb.EjbConstants.HTTPS_SCHEME;
import static org.wildfly.httpclient.ejb.EjbConstants.HTTP_SCHEME;

/**
 * @author <a href="mailto:tadamski@redhat.com">Tomasz Adamski</a>
 */

public final class HttpEJBDiscoveryProvider implements DiscoveryProvider {

    private static final AuthenticationContextConfigurationClient AUTH_CONFIGURATION_CLIENT = doPrivileged(AuthenticationContextConfigurationClient.ACTION);

    private static final long CACHE_REFRESH_TIMEOUT = TimeUnit.MILLISECONDS.toNanos(Long.parseLong(
            WildFlySecurityManager.getPropertyPrivileged("org.wildfly.httpclient.ejb.discovery.cache-refresh-timeout", "300000")));

    private Set<ServiceURL> serviceURLCache = new HashSet<>();
    private AtomicBoolean cacheInvalid = new AtomicBoolean(true);
    private long cacheRefreshTimestamp = 0L;

    HttpEJBDiscoveryProvider() {
    }

    public DiscoveryRequest discover(final ServiceType serviceType, final FilterSpec filterSpec, final DiscoveryResult discoveryResult) {
        final EJBClientContext ejbClientContext = getCurrent();

        if (shouldRefreshCache()) {
            refreshCache(ejbClientContext);
        }

        searchCache(discoveryResult, filterSpec, ejbClientContext);

        return DiscoveryRequest.NULL;
    }

    private boolean shouldRefreshCache() {
        if (cacheInvalid.get() || ((System.nanoTime() - cacheRefreshTimestamp) > CACHE_REFRESH_TIMEOUT)) {
            return true;
        }
        return false;
    }

    private boolean supportsScheme(String s) {
        switch (s) {
            case HTTP_SCHEME:
            case HTTPS_SCHEME:
                return true;
        }
        return false;
    }

    private void searchCache(final DiscoveryResult discoveryResult, final FilterSpec filterSpec, final EJBClientContext ejbClientContext) {
        final boolean resultsPresent = doSearchCache(discoveryResult, filterSpec);
        if(!resultsPresent){
            refreshCache(ejbClientContext);
        }
        discoveryResult.complete();
    }

    private boolean doSearchCache(final DiscoveryResult discoveryResult, final FilterSpec filterSpec) {
        boolean resultsPresent = false;
        for (ServiceURL serviceURL : serviceURLCache) {
            if (serviceURL.satisfies(filterSpec)) {
                discoveryResult.addMatch(serviceURL.getLocationURI());
                resultsPresent = true;
            }
        }
        return resultsPresent;
    }

    private void refreshCache(final EJBClientContext ejbClientContext){
        serviceURLCache.clear();
        final List<EJBClientConnection> httpConnections = ejbClientContext.getConfiguredConnections().stream().filter(
                (connection) -> supportsScheme(connection.getDestination().getScheme())
        ).collect(Collectors.toList());
        final CountDownLatch outstandingLatch = new CountDownLatch(httpConnections.size());
        for (EJBClientConnection connection : httpConnections) {
            discoverFromConnection(connection, outstandingLatch);
        }
        try {
            outstandingLatch.await();
            cacheInvalid.set(false);
            cacheRefreshTimestamp = System.nanoTime();
        } catch(InterruptedException e){
            EjbHttpClientMessages.MESSAGES.httpDiscoveryInterrupted(e);
        }
        cacheInvalid.set(false);
    }

    private void discoverFromConnection(final EJBClientConnection connection, final CountDownLatch outstandingLatch) {
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
        RequestBuilder builder = new RequestBuilder().setRequestType(RequestType.DISCOVER);
        ClientRequest request = builder.createRequest(targetContext.getUri().getPath());
        targetContext.sendRequest(request, sslContext, authenticationConfiguration, null,
                ((result, response, closeable) -> {
                    try {
                        final Unmarshaller unmarshaller = targetContext.getHttpMarshallerFactory(request).createUnmarshaller();

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
                EjbConstants.EJB_DISCOVERY_RESPONSE, null);
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
        cacheInvalid.set(true);
    }
}

