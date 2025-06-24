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

package org.wildfly.httpclient.transaction;

import static java.security.AccessController.doPrivileged;
import static org.wildfly.httpclient.transaction.ClientHandlers.xidArrayHttpResultHandler;
import static org.wildfly.httpclient.transaction.ClientHandlers.xidHttpResultHandler;
import static org.wildfly.httpclient.transaction.Constants.NEW_TRANSACTION;

import io.undertow.client.ClientRequest;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;
import org.wildfly.transaction.client.spi.RemoteTransactionPeer;
import org.wildfly.transaction.client.spi.SimpleTransactionControl;
import org.wildfly.transaction.client.spi.SubordinateTransactionControl;

import javax.net.ssl.SSLContext;
import jakarta.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * @author Stuart Douglas
 */
public class HttpRemoteTransactionPeer implements RemoteTransactionPeer {
    private static final AuthenticationContextConfigurationClient CLIENT = doPrivileged(AuthenticationContextConfigurationClient.ACTION);

    private final HttpTargetContext targetContext;
    private final SSLContext sslContext;
    private final AuthenticationConfiguration authenticationConfiguration;
    private final AuthenticationContext authenticationContext;

    public HttpRemoteTransactionPeer(HttpTargetContext targetContext, SSLContext sslContext, AuthenticationConfiguration authenticationConfiguration) {
        this.targetContext = targetContext;
        this.sslContext = sslContext;
        this.authenticationConfiguration = authenticationConfiguration;
        this.authenticationContext = AuthenticationContext.captureCurrent();
    }

    @Override
    public SubordinateTransactionControl lookupXid(Xid xid) throws XAException {
        try {
            return new HttpSubordinateTransactionHandle(xid, targetContext, getSslContext(targetContext.getUri()), authenticationConfiguration);
        } catch (GeneralSecurityException e) {
            XAException xaException = new XAException(XAException.XAER_RMFAIL);
            xaException.initCause(e);
            throw xaException;
        }
    }

    @Override
    public Xid[] recover(int flag, String parentName) throws XAException {

        final RequestBuilder builder = new RequestBuilder(targetContext, RequestType.XA_RECOVER).setFlags(flag).setParent(parentName);
        final ClientRequest request = builder.createRequest();

        final AuthenticationConfiguration authenticationConfiguration = getAuthenticationConfiguration(targetContext.getUri());
        final SSLContext sslContext;
        try {
            sslContext = getSslContext(targetContext.getUri());
        } catch (GeneralSecurityException e) {
            XAException xaException = new XAException(XAException.XAER_RMFAIL);
            xaException.initCause(e);
            throw xaException;
        }

        final CompletableFuture<Xid[]> result = new CompletableFuture<>();
        final HttpMarshallerFactory marshallerFactory = targetContext.getHttpMarshallerFactory(request);
        final Unmarshaller unmarshaller = marshallerFactory.createUnmarshaller(result);
        if (unmarshaller != null) {
            targetContext.sendRequest(request, sslContext, authenticationConfiguration, null,
                    xidArrayHttpResultHandler(unmarshaller, result), result::completeExceptionally, NEW_TRANSACTION, null);
        }
        try {
            return result.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if(cause instanceof XAException) {
                throw (XAException)cause;
            }
            XAException xaException = new XAException(XAException.XAER_RMFAIL);
            xaException.initCause(cause);
            throw xaException;
        }
    }

    @Override
    public SimpleTransactionControl begin(int timeout) throws SystemException {
        final RequestBuilder builder = new RequestBuilder(targetContext, RequestType.UT_BEGIN).setTimeout(timeout);
        final ClientRequest request = builder.createRequest();

        final AuthenticationConfiguration authenticationConfiguration = getAuthenticationConfiguration(targetContext.getUri());
        final SSLContext sslContext;
        try {
            sslContext = getSslContext(targetContext.getUri());
        } catch (GeneralSecurityException e) {
            throw new SystemException(e.getMessage());
        }

        final CompletableFuture<Xid> result = new CompletableFuture<>();
        final HttpMarshallerFactory marshallerFactory = targetContext.getHttpMarshallerFactory(request);
        final Unmarshaller unmarshaller = marshallerFactory.createUnmarshaller(result);
        if (unmarshaller != null) {
            targetContext.sendRequest(request, sslContext, authenticationConfiguration, null,
                    xidHttpResultHandler(unmarshaller, result), result::completeExceptionally, NEW_TRANSACTION, null);
        }
        try {
            Xid xid = result.get();
            return new HttpRemoteTransactionHandle(xid, targetContext, sslContext, authenticationConfiguration);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            SystemException ex = new SystemException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }
    }

    private AuthenticationConfiguration getAuthenticationConfiguration(URI location) {
        if (authenticationConfiguration == null) {
            return CLIENT.getAuthenticationConfiguration(location, authenticationContext, -1, "jta", "jboss");
        } else {
            return authenticationConfiguration;
        }
    }

    private SSLContext getSslContext(URI location) throws GeneralSecurityException {
        if (sslContext == null) {
            return CLIENT.getSSLContext(location, authenticationContext, "jta", "jboss");
        } else {
            return sslContext;
        }
    }
}
