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

import io.undertow.client.ClientRequest;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;
import org.wildfly.transaction.client.SimpleXid;
import org.wildfly.transaction.client.spi.RemoteTransactionPeer;
import org.wildfly.transaction.client.spi.SimpleTransactionControl;
import org.wildfly.transaction.client.spi.SubordinateTransactionControl;
import org.xnio.IoUtils;

import javax.net.ssl.SSLContext;
import jakarta.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.security.AccessController.doPrivileged;
import static org.wildfly.httpclient.transaction.Constants.NEW_TRANSACTION;

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
        final CompletableFuture<Xid[]> xidList = new CompletableFuture<>();

        final RequestBuilder builder = new RequestBuilder().setRequestType(RequestType.XA_RECOVER).setVersion(targetContext.getProtocolVersion()).setFlags(flag).setParent(parentName);
        final ClientRequest request = builder.createRequest(targetContext.getUri().getPath());

        final AuthenticationConfiguration authenticationConfiguration = getAuthenticationConfiguration(targetContext.getUri());
        final SSLContext sslContext;
        try {
            sslContext = getSslContext(targetContext.getUri());
        } catch (GeneralSecurityException e) {
            XAException xaException = new XAException(XAException.XAER_RMFAIL);
            xaException.initCause(e);
            throw xaException;
        }

        targetContext.sendRequest(request,  sslContext, authenticationConfiguration,null, (result, response, closeable) -> {
            try {
                Unmarshaller unmarshaller = targetContext.getHttpMarshallerFactory(request).createUnmarshaller();
                unmarshaller.start(new InputStreamByteInput(result));
                int length = unmarshaller.readInt();
                Xid[] ret = new Xid[length];
                for(int i = 0; i < length; ++ i) {
                    int formatId = unmarshaller.readInt();
                    int len = unmarshaller.readInt();
                    byte[] globalId = new byte[len];
                    unmarshaller.readFully(globalId);
                    len = unmarshaller.readInt();
                    byte[] branchId = new byte[len];
                    unmarshaller.readFully(branchId);
                    ret[i] = new SimpleXid(formatId, globalId, branchId);
                }
                xidList.complete(ret);
                unmarshaller.finish();
            } catch (Exception e) {
                xidList.completeExceptionally(e);
            } finally {
                IoUtils.safeClose(closeable);
            }
        }, xidList::completeExceptionally, NEW_TRANSACTION, null);
        try {
            return xidList.get();
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
        final CompletableFuture<Xid> beginXid = new CompletableFuture<>();

        final RequestBuilder builder = new RequestBuilder().setRequestType(RequestType.UT_BEGIN).setVersion(targetContext.getProtocolVersion()).setTimeout(timeout);
        final ClientRequest request = builder.createRequest(targetContext.getUri().getPath());

        final AuthenticationConfiguration authenticationConfiguration = getAuthenticationConfiguration(targetContext.getUri());
        final SSLContext sslContext;
        try {
            sslContext = getSslContext(targetContext.getUri());
        } catch (GeneralSecurityException e) {
            throw new SystemException(e.getMessage());
        }

        targetContext.sendRequest(request, sslContext, authenticationConfiguration, null, (result, response, closeable) -> {
            try {
                Unmarshaller unmarshaller = targetContext.getHttpMarshallerFactory(request).createUnmarshaller();
                unmarshaller.start(new InputStreamByteInput(result));
                int formatId = unmarshaller.readInt();
                int len = unmarshaller.readInt();
                byte[] globalId = new byte[len];
                unmarshaller.readFully(globalId);
                len = unmarshaller.readInt();
                byte[] branchId = new byte[len];
                unmarshaller.readFully(branchId);
                SimpleXid simpleXid = new SimpleXid(formatId, globalId, branchId);
                beginXid.complete(simpleXid);
                unmarshaller.finish();
            } catch (Exception e) {
                beginXid.completeExceptionally(e);
            } finally {
                IoUtils.safeClose(closeable);
            }
        }, beginXid::completeExceptionally, NEW_TRANSACTION, null);
        try {
            Xid xid = beginXid.get();
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
