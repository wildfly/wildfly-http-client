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
import io.undertow.client.ClientResponse;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.httpclient.common.Version;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.transaction.client.spi.SubordinateTransactionControl;
import org.xnio.IoUtils;

import javax.net.ssl.SSLContext;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static org.wildfly.httpclient.common.Protocol.VERSION_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.EXCEPTION;
import static org.wildfly.httpclient.transaction.TransactionConstants.READ_ONLY;
import static org.wildfly.httpclient.transaction.TransactionConstants.TXN_CONTEXT;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_BC_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_COMMIT_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_FORGET_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_PREP_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_ROLLBACK_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XID;

/**
 * Represents a remote subordinate transaction that is managed over HTTP protocol.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author Richard Achmatowicz
 */
class HttpSubordinateTransactionHandle implements SubordinateTransactionControl {

    private final Version version;
    private final HttpTargetContext targetContext;
    private final Xid id;
    private final SSLContext sslContext;
    private final AuthenticationConfiguration authenticationConfiguration;

    HttpSubordinateTransactionHandle(final Version version, final Xid id, final HttpTargetContext targetContext, SSLContext sslContext, AuthenticationConfiguration authenticationConfiguration) {
        this.version = version;
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
        String operationPath = XA_COMMIT_PATH + (onePhase ? "?opc=true" : "");
        processOperation(operationPath);
    }

    @Override
    public void rollback() throws XAException {
        processOperation(XA_ROLLBACK_PATH);
    }

    @Override
    public void end(int flags) throws XAException {
        //TODO:
    }

    @Override
    public void beforeCompletion() throws XAException {
        processOperation(XA_BC_PATH);
    }

    @Override
    public int prepare() throws XAException {
        boolean readOnly = processOperation(XA_PREP_PATH, (result) -> {
            String header = result.getResponseHeaders().getFirst(READ_ONLY);
            return header != null && Boolean.parseBoolean(header);
        });
        return readOnly ? XAResource.XA_RDONLY : XAResource.XA_OK;
    }

    @Override
    public void forget() throws XAException {
        processOperation(XA_FORGET_PATH);
    }

    private void processOperation(String operationPath) throws XAException {
        processOperation(operationPath, null);
    }

    private <T> T processOperation(String operationPath, Function<ClientResponse, T> resultFunction) throws XAException {
        final CompletableFuture<T> result = new CompletableFuture<>();
        ClientRequest cr = new ClientRequest()
                .setMethod(Methods.POST)
                .setPath(targetContext.getUri().getPath() + TXN_CONTEXT + VERSION_PATH + targetContext.getProtocolVersion() + operationPath);
        cr.getRequestHeaders().put(Headers.ACCEPT, EXCEPTION.toString());
        cr.getRequestHeaders().put(Headers.CONTENT_TYPE, XID.toString());
        targetContext.sendRequest(cr, sslContext, authenticationConfiguration, output -> {
            Marshaller marshaller = targetContext.getHttpMarshallerFactory(cr).createMarshaller();
            marshaller.start(Marshalling.createByteOutput(output));
            marshaller.writeInt(id.getFormatId());
            final byte[] gtid = id.getGlobalTransactionId();
            marshaller.writeInt(gtid.length);
            marshaller.write(gtid);
            final byte[] bq = id.getBranchQualifier();
            marshaller.writeInt(bq.length);
            marshaller.write(bq);
            marshaller.finish();
            output.close();
        }, (input, response, closeable) -> {
            try {
                result.complete(resultFunction != null ? resultFunction.apply(response) : null);
            } finally {
                IoUtils.safeClose(closeable);
            }
        }, result::completeExceptionally, null, null);

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
