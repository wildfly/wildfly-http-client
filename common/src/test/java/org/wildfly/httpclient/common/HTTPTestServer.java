/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
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

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.connector.ByteBufferPool;
import io.undertow.security.api.AuthenticationMode;
import io.undertow.security.handlers.AuthenticationCallHandler;
import io.undertow.security.idm.Account;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.CanonicalPathHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.error.SimpleErrorPageHandler;
import io.undertow.util.NetworkUtils;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;
import org.wildfly.elytron.web.undertow.server.ElytronContextAssociationHandler;
import org.wildfly.security.WildFlyElytronProvider;
import org.wildfly.security.auth.permission.LoginPermission;
import org.wildfly.security.auth.realm.SimpleMapBackedSecurityRealm;
import org.wildfly.security.auth.realm.SimpleRealmEntry;
import org.wildfly.security.auth.server.SecurityDomain;
import org.wildfly.security.auth.server.http.HttpAuthenticationFactory;
import org.wildfly.security.http.HttpAuthenticationException;
import org.wildfly.security.http.HttpServerAuthenticationMechanism;
import org.wildfly.security.http.HttpServerAuthenticationMechanismFactory;
import org.wildfly.security.http.basic.BasicMechanismFactory;
import org.wildfly.security.http.cert.ClientCertMechanismFactory;
import org.wildfly.security.http.digest.DigestMechanismFactory;
import org.wildfly.security.http.util.AggregateServerMechanismFactory;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.spec.ClearPasswordSpec;
import org.wildfly.security.permission.PermissionVerifier;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.SslClientAuthMode;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.wildfly.security.password.interfaces.ClearPassword.ALGORITHM_CLEAR;

/**
 * @author Stuart Douglas
 */
public class HTTPTestServer extends BlockJUnit4ClassRunner {

    public static final int BUFFER_SIZE = Integer.getInteger("test.bufferSize", 8192 * 3);
    private static final PathHandler PATH_HANDLER = new PathHandler();
    private static final PathHandler SERVICES_HANDLER = new PathHandler();
    public static final String WILDFLY_SERVICES = "/wildfly-services";
    public static final String INITIAL_SESSION_AFFINITY = "initial-session-affinity";

    private static final String SERVER_KEY_STORE = "server.keystore";
    private static final String SERVER_TRUST_STORE = "server.truststore";
    public static final String CLIENT_KEY_STORE = "client.keystore";
    public static final String CLIENT_TRUST_STORE = "client.truststore";
    public static final char[] STORE_PASSWORD = "password".toCharArray();

    private static boolean first = true;
    private static Undertow undertow;

    private static XnioWorker worker;

    private static final DefaultByteBufferPool pool = new DefaultByteBufferPool(true, BUFFER_SIZE, 1000, 10, 100);

    private static final Set<String> registeredPaths = new HashSet<>();
    private static final Set<String> registeredServices = new HashSet<>();
    private SecurityDomain securityDomain;

    static {
        Security.addProvider(new WildFlyElytronProvider());
    }

    /**
     * @return The base URL that can be used to make connections to this server
     */
    public static String getDefaultServerURL() {
        return getDefaultRootServerURL() + WILDFLY_SERVICES;
    }

    public static String getDefaultRootServerURL() {
        return "http://" + NetworkUtils.formatPossibleIpv6Address(getHostAddress()) + ":" + getHostPort();
    }

    public static String getDefaultSSLRootServerURL() {
        return "https://" + NetworkUtils.formatPossibleIpv6Address(getHostAddress()) + ":" + getSSLHostPort();
    }

    /**
     * @return The base URL that can be used to make connections to this server
     */
    public static String getDefaultSSLServerURL() {
        return getDefaultSSLRootServerURL() + WILDFLY_SERVICES;
    }

    public HTTPTestServer(Class<?> klass) throws InitializationError {
        super(klass);
    }

    public static ByteBufferPool getBufferPool() {
        return pool;
    }

    @Override
    public Description getDescription() {
        return super.getDescription();
    }

    @Override
    public void run(final RunNotifier notifier) {
        runInternal(notifier);
        notifier.addListener(new RunListener() {
            @Override
            public void testFinished(Description description) throws Exception {
                for (String reg : registeredPaths) {
                    PATH_HANDLER.removePrefixPath(reg);
                }
                registeredPaths.clear();
                for (String reg : registeredServices) {
                    SERVICES_HANDLER.removePrefixPath(reg);
                }
                registeredServices.clear();
            }
        });
        super.run(notifier);
    }

    public static void registerPathHandler(String path, HttpHandler handler) {
        PATH_HANDLER.addPrefixPath(path, handler);
        registeredPaths.add(path);
    }

    public static void registerServicesHandler(String path, HttpHandler handler) {
        SERVICES_HANDLER.addPrefixPath(path, handler);
        registeredServices.add(path);
    }

    public static XnioWorker getWorker() {
        return worker;
    }

    private HttpHandler securityContextAssociationHandlerElytron() {
        ElytronContextAssociationHandler.Builder builder = ElytronContextAssociationHandler.builder();
        builder.setAuthenticationMode(AuthenticationMode.PRO_ACTIVE)
                .setSecurityDomain(getSecurityDomain())
                .setMechanismSupplier(this::authenticationMechanisms)
                .setNext(getRootHandler());
        return builder.build();
    }

    private List<HttpServerAuthenticationMechanism> authenticationMechanisms() {
        HttpServerAuthenticationMechanismFactory basic = new BasicMechanismFactory();
        HttpServerAuthenticationMechanismFactory digest = new DigestMechanismFactory();
        HttpServerAuthenticationMechanismFactory clientCert = new ClientCertMechanismFactory();
        HttpServerAuthenticationMechanismFactory aggregated = new AggregateServerMechanismFactory(basic, digest, clientCert);
        HttpAuthenticationFactory httpAuthenticationFactory = HttpAuthenticationFactory.builder()
                .setSecurityDomain(getSecurityDomain())
                .setFactory(aggregated)
                .build();
        return httpAuthenticationFactory.getMechanismNames().stream()
                .map(mechanismName -> {
                    try {
                        return httpAuthenticationFactory.createMechanism(mechanismName);
                    } catch (HttpAuthenticationException e) {
                        throw new RuntimeException("Failed to create mechanism.", e);
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private SecurityDomain getSecurityDomain() {
        if (securityDomain != null) {
            return securityDomain;
        }
        try {
            PasswordFactory passwordFactory = PasswordFactory.getInstance(ALGORITHM_CLEAR);
            Map<String, SimpleRealmEntry> passwordMap = new HashMap<>();
            passwordMap.put("administrator", new SimpleRealmEntry(Collections.singletonList(new org.wildfly.security.credential.PasswordCredential(passwordFactory.generatePassword(new ClearPasswordSpec("password1!".toCharArray()))))));

            SimpleMapBackedSecurityRealm simpleRealm = new SimpleMapBackedSecurityRealm();
            simpleRealm.setIdentityMap(passwordMap);

            SecurityDomain.Builder builder = SecurityDomain.builder()
                    .setPermissionMapper((principal, roles) -> PermissionVerifier.from(new LoginPermission()));
            builder.addRealm("test", simpleRealm);

            securityDomain = builder.build();
            return securityDomain;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SecurityDomain", e);
        }
    }

    private void runInternal(final RunNotifier notifier) {
        try {
            if (first) {
                first = false;
                Xnio xnio = Xnio.getInstance("nio");
                PATH_HANDLER.addPrefixPath("/wildfly-services", SERVICES_HANDLER);
                worker = xnio.createWorker(OptionMap.create(Options.WORKER_TASK_CORE_THREADS, 20, Options.WORKER_IO_THREADS, 10));
                registerPaths(SERVICES_HANDLER);
                undertow = Undertow.builder()
                        .addHttpListener(getHostPort(), getHostAddress())
                        .addHttpsListener(getSSLHostPort(), getHostAddress(), createServerSslContext())
                        .setServerOption(UndertowOptions.REQUIRE_HOST_HTTP11, true)
                        .setServerOption(UndertowOptions.NO_REQUEST_TIMEOUT, 1000)
                        .setSocketOption(Options.SSL_CLIENT_AUTH_MODE, SslClientAuthMode.REQUIRED)
                        .setHandler(securityContextAssociationHandlerElytron())
                        .build();
                undertow.start();
                notifier.addListener(new RunListener() {
                    @Override
                    public void testRunFinished(final Result result) throws Exception {
                        undertow.stop();
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected HttpHandler getRootHandler() {
        HttpHandler root = new BlockingHandler(PATH_HANDLER);
        root = new AuthenticationCallHandler(root);
        root = new SimpleErrorPageHandler(root);
        root = new CanonicalPathHandler(root);
        return root;
    }

    private SSLContext createServerSslContext() {
        return createSSLContext(loadKeyStore(SERVER_KEY_STORE), loadKeyStore(SERVER_TRUST_STORE));
    }

    public static SSLContext createClientSSLContext() {
        return createSSLContext(loadKeyStore(CLIENT_KEY_STORE), loadKeyStore(CLIENT_TRUST_STORE));
    }

    private static SSLContext createSSLContext(final KeyStore keyStore, final KeyStore trustStore) {
        KeyManager[] keyManagers;
        try {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, STORE_PASSWORD);
            keyManagers = keyManagerFactory.getKeyManagers();
        } catch (NoSuchAlgorithmException | UnrecoverableKeyException | KeyStoreException e) {
            throw new RuntimeException("Unable to initialise KeyManager[]", e);
        }

        TrustManager[] trustManagers = null;
        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            trustManagers = trustManagerFactory.getTrustManagers();
        } catch (NoSuchAlgorithmException | KeyStoreException e) {
            throw new RuntimeException("Unable to initialise TrustManager[]", e);
        }

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, trustManagers, null);
            return sslContext;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Unable to create and initialise the SSLContext", e);
        }
    }

    public static KeyStore loadKeyStore(final String name) {
        final InputStream stream = HTTPTestServer.class.getClassLoader().getResourceAsStream(name);
        if (stream == null) {
            throw new RuntimeException("Could not load keystore");
        }
        try {
            KeyStore loadedKeystore = KeyStore.getInstance("JKS");
            loadedKeystore.load(stream, STORE_PASSWORD);

            return loadedKeystore;
        } catch (KeyStoreException | NoSuchAlgorithmException | IOException | CertificateException e) {
            throw new RuntimeException(String.format("Unable to load KeyStore %s", name), e);
        } finally {
            IoUtils.safeClose(stream);
        }
    }

    protected void registerPaths(PathHandler servicesHandler) {

    }

    public static String getHostAddress() {
        return System.getProperty("server.address", "localhost");
    }

    public static int getHostPort() {
        return Integer.getInteger("server.port", 7788);
    }

    public static int getSSLHostPort() {
        return getHostPort() + 1;
    }

    private static class TestAccount implements Account {
        @Override
        public Principal getPrincipal() {
            return () -> "administrator";
        }

        @Override
        public Set<String> getRoles() {
            return Collections.emptySet();
        }
    }
}
