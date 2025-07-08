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

import static java.lang.Boolean.TRUE;
import static java.lang.Boolean.parseBoolean;
import static org.wildfly.httpclient.common.HeadersHelper.getResponseHeader;
import static org.wildfly.httpclient.transaction.ClientHandlers.xidHttpBodyEncoder;
import static org.wildfly.httpclient.transaction.ClientHandlers.emptyHttpBodyDecoder;
import static org.wildfly.httpclient.transaction.Constants.READ_ONLY;
import static org.wildfly.httpclient.transaction.RequestType.XA_BEFORE_COMPLETION;
import static org.wildfly.httpclient.transaction.RequestType.XA_COMMIT;
import static org.wildfly.httpclient.transaction.RequestType.XA_FORGET;
import static org.wildfly.httpclient.transaction.RequestType.XA_PREPARE;
import static org.wildfly.httpclient.transaction.RequestType.XA_ROLLBACK;

import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import org.jboss.marshalling.Marshaller;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.transaction.client.spi.SubordinateTransactionControl;

import javax.net.ssl.SSLContext;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Represents a remote subordinate transaction that is managed over HTTP protocol.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
class HttpSubordinateTransactionHandle implements SubordinateTransactionControl {

    private final HttpTargetContext targetContext;
    private final Xid id;
    private final SSLContext sslContext;
    private final AuthenticationConfiguration authenticationConfiguration;

    HttpSubordinateTransactionHandle(final Xid id, final HttpTargetContext targetContext, SSLContext sslContext, AuthenticationConfiguration authenticationConfiguration) {
        this.id = id;
        this.targetContext = targetContext;
        this.sslContext = sslContext;
        this.authenticationConfiguration = authenticationConfiguration;
    }

    Xid getId() {
        return id;
    }

    @Override
    public void commit(boolean onePhase) throws XAException {
        processOperation(XA_COMMIT, null, onePhase ? TRUE : null);
    }

    @Override
    public void rollback() throws XAException {
        processOperation(XA_ROLLBACK);
    }

    @Override
    public void end(int flags) throws XAException {
        //TODO:
    }

    @Override
    public void beforeCompletion() throws XAException {
        processOperation(XA_BEFORE_COMPLETION);
    }

    @Override
    public int prepare() throws XAException {
        boolean readOnly = processOperation(XA_PREPARE, (result) -> {
            String header = getResponseHeader(result, READ_ONLY);
            return parseBoolean(header);
        }, null);
        return readOnly ? XAResource.XA_RDONLY : XAResource.XA_OK;
    }

    @Override
    public void forget() throws XAException {
        processOperation(XA_FORGET);
    }

    private void processOperation(RequestType requestType) throws XAException {
        processOperation(requestType, null, null);
    }

    private <T> T processOperation(RequestType requestType, Function<ClientResponse, T> resultFunction, Boolean onePhase) throws XAException {
        final RequestBuilder builder = new RequestBuilder(targetContext, requestType).setOnePhase(onePhase);
        final ClientRequest request = builder.createRequest();
        final CompletableFuture<T> result = new CompletableFuture<>();
        final HttpMarshallerFactory marshallerFactory = targetContext.getHttpMarshallerFactory(request);
        final Marshaller marshaller = marshallerFactory.createMarshaller(result);
        if (marshaller != null) {
            targetContext.sendRequest(request, sslContext, authenticationConfiguration,
                    xidHttpBodyEncoder(marshaller, id), emptyHttpBodyDecoder(result, resultFunction), result::completeExceptionally, null, null);
        }
        try {
            try {
                return result.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw HttpRemoteTransactionMessages.MESSAGES.interruptedXA(XAException.XAER_RMERR);
            }
        } catch (ExecutionException e) {
            try {
                throw e.getCause();
            } catch (XAException ex) {
                throw ex;
            } catch (Throwable ex) {
                XAException xaException = new XAException(XAException.XAER_RMERR);
                xaException.initCause(ex);
                throw xaException;
            }
        }
    }

}
