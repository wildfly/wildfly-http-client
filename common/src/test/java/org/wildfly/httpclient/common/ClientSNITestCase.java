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

import io.undertow.client.ClientRequest;
import io.undertow.server.SSLSessionInfo;
import io.undertow.util.Methods;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import org.assertj.core.api.Assertions;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.security.auth.client.AuthenticationConfiguration;

/**
 * <p>Test that checks the SNI server is set to the real hostname used in the URI</p>
 *
 * @author rmartinc
 */
@RunWith(HTTPTestServer.class)
public class ClientSNITestCase {

    @Test
    public void testSNIWithHostname() throws Throwable {
        InetAddress address = InetAddress.getByName(HTTPTestServer.getHostAddress());
        Assume.assumeTrue("Assuming the test if no resolution for the address", !address.getHostName().equals(address.getHostAddress()));

        SSLContext sslContext = HTTPTestServer.createClientSSLContext();
        final String path = "/host";
        final List<SNIServerName> result = new ArrayList<>(1);
        HTTPTestServer.registerPathHandler(path, exchange -> {
            if (path.equals(exchange.getRequestURI())) {
                SSLSessionInfo ssl = exchange.getConnection().getSslSessionInfo();
                if (ssl != null && ssl.getSSLSession() instanceof ExtendedSSLSession) {
                    result.addAll(((ExtendedSSLSession) ssl.getSSLSession()).getRequestedServerNames());
                }
            }
        });

        ClientRequest request = new ClientRequest().setMethod(Methods.GET).setPath(path);
        URI uri = new URI("https://" + address.getHostName() + ":" + HTTPTestServer.getSSLHostPort() + request.getPath());
        doClientRequest(request, uri, sslContext);

        Assertions.assertThat(result)
                .as("Check sni names contains " + address.getHostName())
                .containsExactly(new SNIHostName(address.getHostName()));
    }

    @Test
    public void testNoSNIWithIP() throws Throwable {
        InetAddress address = InetAddress.getByName(HTTPTestServer.getHostAddress());
        Assume.assumeTrue("Assuming the test if no resolution for the address", !address.getHostName().equals(address.getHostAddress()));
        String hostname = address instanceof Inet6Address? "[" + address.getHostAddress() + "]" : address.getHostAddress();

        SSLContext sslContext = HTTPTestServer.createClientSSLContext();
        final String path = "/host";
        final List<SNIServerName> result = new ArrayList<>(1);
        HTTPTestServer.registerPathHandler(path, exchange -> {
            if (path.equals(exchange.getRequestURI())) {
                SSLSessionInfo ssl = exchange.getConnection().getSslSessionInfo();
                if (ssl != null && ssl.getSSLSession() instanceof ExtendedSSLSession) {
                    result.addAll(((ExtendedSSLSession) ssl.getSSLSession()).getRequestedServerNames());
                }
            }
        });

        ClientRequest request = new ClientRequest().setMethod(Methods.GET).setPath(path);
        URI uri = new URI("https://" + hostname + ":" + HTTPTestServer.getSSLHostPort() + request.getPath());
        doClientRequest(request, uri, sslContext);

        Assertions.assertThat(result)
                .as("Check no SNI names with IP")
                .isEmpty();
    }

    private void doClientRequest(ClientRequest request, URI uri, SSLContext sslContext) throws Throwable {
        ClientAuthUtils.setupBasicAuth(request, uri);

        final CompletableFuture<Void> future = new CompletableFuture<>();
        HttpTargetContext context = WildflyHttpContext.getCurrent().getTargetContext(uri);
        context.sendRequest(request, sslContext, AuthenticationConfiguration.empty(), null,
                (result, response) -> future.complete(null),
                throwable -> future.completeExceptionally(throwable),
                null, null, true);
        future.get(10, TimeUnit.SECONDS);
    }
}
