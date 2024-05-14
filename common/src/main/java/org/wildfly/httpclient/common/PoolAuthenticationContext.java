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

import static io.undertow.util.Headers.AUTHENTICATION_INFO;
import static io.undertow.util.Headers.AUTHORIZATION;
import static io.undertow.util.Headers.WWW_AUTHENTICATE;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.client.ClientExchange;
import io.undertow.security.impl.AuthenticationInfoToken;
import io.undertow.security.impl.DigestWWWAuthenticateToken;
import io.undertow.server.session.SecureRandomSessionIdGenerator;
import io.undertow.util.FlexBase64;
import io.undertow.util.HeaderValues;
import io.undertow.util.HexConverter;
import io.undertow.util.StatusCodes;
import io.undertow.util.AttachmentKey;
import org.wildfly.security.auth.principal.NamePrincipal;

/**
 * Class that holds authentication information for a connection
 *
 * @author Stuart Douglas
 */
class PoolAuthenticationContext {

    private static final AttachmentKey<DigestImpl> DIGEST = AttachmentKey.create(DigestImpl.class);

    private static final AuthenticationContextConfigurationClient AUTH_CONTEXT_CLIENT;

    static {
        AUTH_CONTEXT_CLIENT = AccessController.doPrivileged((PrivilegedAction<AuthenticationContextConfigurationClient>) AuthenticationContextConfigurationClient::new);
    }

    private volatile Type current;

    private static final LinkedBlockingDeque<DigestImpl> digestList = new LinkedBlockingDeque<>();

    private static final SecureRandomSessionIdGenerator cnonceGenerator = new SecureRandomSessionIdGenerator();

    boolean handleResponse(ClientResponse response) {
        if (response.getResponseCode() != StatusCodes.UNAUTHORIZED) {
            return false;
        }
        String authenticate = response.getResponseHeaders().getFirst(WWW_AUTHENTICATE);
        if (authenticate == null) {
            return false;
        }
        String auth = authenticate.toLowerCase(Locale.ENGLISH);
        if (auth.startsWith("basic ")) {
            current = Type.BASIC;
            return true;
        }
        if (auth.startsWith("digest ")) {
            current = Type.DIGEST;

            Map<DigestWWWAuthenticateToken, String> result = DigestWWWAuthenticateToken.parseHeader(authenticate.substring(7));
            DigestImpl current = new DigestImpl();
            current.domain = result.get(DigestWWWAuthenticateToken.DOMAIN);
            current.nonce = result.get(DigestWWWAuthenticateToken.NONCE);
            current.opaque = result.get(DigestWWWAuthenticateToken.OPAQUE);
            current.algorithm = result.get(DigestWWWAuthenticateToken.ALGORITHM);
            String s = result.get(DigestWWWAuthenticateToken.MESSAGE_QOP);
            current.qop = null;
            if (s != null) {
                for (String p : s.split(",")) {
                    if (p.equals("auth")) {
                        current.qop = p;
                    }
                }
                if (current.qop == null) {
                    throw HttpClientMessages.MESSAGES.unsupportedQopInDigest();
                }
            }
            current.realm = result.get(DigestWWWAuthenticateToken.REALM);
            current.nccount = 1;
            if (current.algorithm.startsWith("\"")) {
                current.algorithm = current.algorithm.substring(1, current.algorithm.length() - 1);
            }
            digestList.add(current);
            return true;

        }
        return false;
    }

    static String createTargetUri(URI uri, ClientRequest request) {
        String path;
        String query;
        int pos = request.getPath().indexOf("?");
        if (pos > 0) {
            path = request.getPath().substring(0, pos);
            query = request.getPath().substring(pos + 1);
        } else {
            path = request.getPath();
            query = null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        StringBuilder uriBuilder = new StringBuilder();
        if (scheme != null) {
            uriBuilder.append(scheme);
            uriBuilder.append(':');
        }
        if (host != null) {
            uriBuilder.append("//");
            boolean needBrackets = ((host.indexOf(':') >= 0)
                    && !host.startsWith("[")
                    && !host.endsWith("]"));
            if (needBrackets) {
                uriBuilder.append('[');
            }
            uriBuilder.append(host);
            if (needBrackets) {
                uriBuilder.append(']');
            }
        }
        if (port != -1 && !(("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443))) {
            uriBuilder.append(':');
            uriBuilder.append(port);
        }
        uriBuilder.append(path);
        if (query != null && !query.isEmpty()) {
            uriBuilder.append("?");
            uriBuilder.append(query);
        }
        return uriBuilder.toString();
    }

    boolean prepareRequest(URI uri, ClientRequest request, AuthenticationConfiguration authenticationConfiguration) {
        if (current == Type.NONE) {
            return false;
        }
        AuthenticationConfiguration config = authenticationConfiguration;
        if (config == null) {
            config = AUTH_CONTEXT_CLIENT.getAuthenticationConfiguration(uri, AuthenticationContext.captureCurrent());
        }

        final CallbackHandler callbackHandler = AUTH_CONTEXT_CLIENT.getCallbackHandler(config);

        // TODO: also try credential callback, passing in DIGEST parameters (if any) when DIGEST is in use
        NameCallback nameCallback = new NameCallback("user name");
        PasswordCallback passwordCallback = new PasswordCallback("password", false);
        try {
            callbackHandler.handle(new Callback[]{nameCallback, passwordCallback});
        } catch (IOException | UnsupportedCallbackException e) {
            return false;
        }
        final String name = nameCallback.getName();
        if (name == null) {
            return false;
        }
        char[] password = passwordCallback.getPassword();
        if (password == null) {
            return false;
        }
        Principal principal = new NamePrincipal(name);
        if (current == Type.BASIC) {
            String challenge = principal.getName() + ":" + new String(password);
            request.getRequestHeaders().put(AUTHORIZATION, "Basic " + FlexBase64.encodeString(challenge.getBytes(StandardCharsets.UTF_8), false));
            return true;
        } else if (current == Type.DIGEST) {
            DigestImpl current = digestList.poll();
            if (current == null) {
                return false;
            }
            String cnonce = cnonceGenerator.createSessionId();
            String digestUri = createTargetUri(uri, request);
            request.putAttachment(DIGEST, current);
            StringBuilder sb = new StringBuilder("Digest username=\"");
            sb.append(principal.getName());
            sb.append("\", uri=\"");
            sb.append(digestUri);
            sb.append("\", realm=\"");
            sb.append(current.realm);
            sb.append("\"");
            StringBuilder ncBuilder = new StringBuilder();
            if (current.qop != null) {
                sb.append(", nc=");
                String nonceCountString = Integer.toHexString(current.nccount++);
                for (int i = nonceCountString.length(); i < 8; ++i) {
                    ncBuilder.append("0"); //must be 8 digits long
                }
                ncBuilder.append(nonceCountString);
                sb.append(ncBuilder.toString());

                sb.append(", cnonce=\"");
                sb.append(cnonce);
                sb.append("\"");
            }
            sb.append(", algorithm=");
            sb.append(current.algorithm);
            sb.append(", nonce=\"");
            sb.append(current.nonce);
            sb.append("\", opaque=\"");
            sb.append(current.opaque);
            sb.append("\", qop=auth"); //TODO: fix this? What do we want to do about auth-int

            //calculate the response
            String a1 = principal.getName() + ":" + current.realm + ":" + new String(password);
            String a2 = request.getMethod().toString() + ":" + digestUri;

            try {
                MessageDigest digest = MessageDigest.getInstance(current.algorithm);
                digest.update(a1.getBytes(StandardCharsets.UTF_8));
                byte[] hashedA1 = HexConverter.convertToHexBytes(digest.digest());
                digest.reset();
                digest.update(a2.getBytes(StandardCharsets.UTF_8));
                String hashedA2 = HexConverter.convertToHexString(digest.digest());
                digest.reset();
                digest.update(hashedA1);
                digest.update((byte) ':');
                digest.update(current.nonce.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                if (current.qop != null) {
                    digest.update(ncBuilder.toString().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) ':');
                    digest.update(cnonce.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) ':');
                    digest.update("auth".getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) ':');
                }
                digest.update(hashedA2.getBytes(StandardCharsets.UTF_8));
                sb.append(", response=\"");
                sb.append(HexConverter.convertToHexString(digest.digest()));
                sb.append("\"");
                request.getRequestHeaders().put(AUTHORIZATION, sb.toString());
                return true;
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }

    boolean isStale(ClientExchange exchange) {
        if (current != Type.DIGEST) {
            return false;
        }
        ClientResponse response = exchange.getResponse();
        if (response.getResponseCode() != StatusCodes.UNAUTHORIZED) {
            DigestImpl digest = exchange.getRequest().getAttachment(DIGEST);
            if(digest != null) {
                String authInfo = response.getResponseHeaders().getFirst(AUTHENTICATION_INFO);
                if(authInfo != null) {
                    try {
                        Map<AuthenticationInfoToken, String> result = AuthenticationInfoToken.parseHeader(authInfo);
                        String next = result.get(AuthenticationInfoToken.NEXT_NONCE);
                        if (next != null) {
                            digest.nonce = next;
                        }
                    } catch (Exception e) {
                        HttpClientMessages.MESSAGES.failedToParseAuthenticationInfo(e);
                    }
                }
                digestList.add(digest);
            }
            return false;
        }
        HeaderValues headers = response.getResponseHeaders().get(WWW_AUTHENTICATE);
        if (headers == null) {
            return false;
        }
        for (String authenticate : headers) {
            String auth = authenticate.toLowerCase(Locale.ENGLISH);
            if (!auth.startsWith("digest ")) {
                continue;
            }
            Map<DigestWWWAuthenticateToken, String> result = DigestWWWAuthenticateToken.parseHeader(authenticate.substring(7));
            if (result.containsKey(DigestWWWAuthenticateToken.STALE)) {
                return true;
            }
        }
        return false;
    }

    enum Type {
        NONE,
        BASIC,
        DIGEST
    }

    private static final class DigestImpl {

        private String realm;
        private String domain;
        private String nonce;
        private String opaque;
        private String algorithm;
        private String qop;
        private int nccount = 1;
    }

}
