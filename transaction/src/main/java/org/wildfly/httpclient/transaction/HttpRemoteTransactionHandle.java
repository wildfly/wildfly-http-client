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

import static org.wildfly.httpclient.transaction.ClientHandlers.emptyHttpResultHandler;
import static org.wildfly.httpclient.transaction.ClientHandlers.xidHttpMarshaller;

import io.undertow.client.ClientRequest;
import org.jboss.marshalling.Marshaller;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.transaction.client.spi.SimpleTransactionControl;

import javax.net.ssl.SSLContext;
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import javax.transaction.xa.Xid;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a remote transaction that is managed over HTTP protocol.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
class HttpRemoteTransactionHandle implements SimpleTransactionControl {

    private final HttpTargetContext targetContext;
    private final AtomicInteger statusRef = new AtomicInteger(Status.STATUS_ACTIVE);
    private final Xid id;
    private final SSLContext sslContext;
    private final AuthenticationConfiguration authenticationConfiguration;

    HttpRemoteTransactionHandle(final Xid id, final HttpTargetContext targetContext, SSLContext sslContext, AuthenticationConfiguration authenticationConfiguration) {
        this.id = id;
        this.targetContext = targetContext;
        this.sslContext = sslContext;
        this.authenticationConfiguration = authenticationConfiguration;
    }

    Xid getId() {
        return id;
    }

    public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException, SystemException {
        final AtomicInteger statusRef = this.statusRef;
        int oldVal = statusRef.get();
        if (oldVal != Status.STATUS_ACTIVE && oldVal != Status.STATUS_MARKED_ROLLBACK) {
            throw HttpRemoteTransactionMessages.MESSAGES.invalidTxnState();
        }
        synchronized (statusRef) {
            oldVal = statusRef.get();
            if (oldVal == Status.STATUS_MARKED_ROLLBACK) {
                rollback();
                throw HttpRemoteTransactionMessages.MESSAGES.rollbackOnlyRollback();
            }
            if (oldVal != Status.STATUS_ACTIVE) {
                throw HttpRemoteTransactionMessages.MESSAGES.invalidTxnState();
            }
            statusRef.set(Status.STATUS_COMMITTING);

            final RequestBuilder builder = new RequestBuilder(targetContext, RequestType.UT_COMMIT);
            final ClientRequest request = builder.createRequest();

            final CompletableFuture<Void> result = new CompletableFuture<>();
            final HttpMarshallerFactory marshallerFactory = targetContext.getHttpMarshallerFactory(request);
            final Marshaller marshaller = marshallerFactory.createMarshaller(result);
            if (marshaller != null) {
                targetContext.sendRequest(request, sslContext, authenticationConfiguration,
                        xidHttpMarshaller(marshaller, id), emptyHttpResultHandler(result, null), result::completeExceptionally, null, null);
            }

            try {
                result.get();
                statusRef.set(Status.STATUS_COMMITTED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                statusRef.set(Status.STATUS_UNKNOWN);
                throw HttpRemoteTransactionMessages.MESSAGES.operationInterrupted();
            } catch (ExecutionException e) {
                try {
                    throw e.getCause();
                } catch (RollbackException ex) {
                    statusRef.set(Status.STATUS_ROLLEDBACK);
                    throw ex;
                } catch (SecurityException ex) {
                    statusRef.set(oldVal);
                    throw ex;
                } catch (HeuristicMixedException | HeuristicRollbackException | SystemException ex) {
                    statusRef.set(Status.STATUS_UNKNOWN);
                    throw ex;
                } catch (Throwable throwable) {
                    SystemException ex = new SystemException(throwable.getMessage());
                    statusRef.set(Status.STATUS_UNKNOWN);
                    ex.initCause(throwable);
                    throw ex;
                }
            }
        }
    }

    public void rollback() throws SecurityException, SystemException {
        final AtomicInteger statusRef = this.statusRef;
        int oldVal = statusRef.get();
        if (oldVal != Status.STATUS_ACTIVE && oldVal != Status.STATUS_MARKED_ROLLBACK) {
            throw HttpRemoteTransactionMessages.MESSAGES.invalidTxnState();
        }
        synchronized (statusRef) {
            oldVal = statusRef.get();
            if (oldVal != Status.STATUS_ACTIVE && oldVal != Status.STATUS_MARKED_ROLLBACK) {
                throw HttpRemoteTransactionMessages.MESSAGES.invalidTxnState();
            }
            statusRef.set(Status.STATUS_ROLLING_BACK);

            statusRef.set(Status.STATUS_COMMITTING);

            final RequestBuilder builder = new RequestBuilder(targetContext, RequestType.UT_ROLLBACK);
            final ClientRequest request = builder.createRequest();

            final CompletableFuture<Void> result = new CompletableFuture<>();
            final HttpMarshallerFactory marshallerFactory = targetContext.getHttpMarshallerFactory(request);
            final Marshaller marshaller = marshallerFactory.createMarshaller(result);
            if (marshaller != null) {
                targetContext.sendRequest(request, sslContext, authenticationConfiguration,
                        xidHttpMarshaller(marshaller, id), emptyHttpResultHandler(result, null), result::completeExceptionally, null, null);
            }

            try {
                result.get();
                statusRef.set(Status.STATUS_ROLLEDBACK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                statusRef.set(Status.STATUS_UNKNOWN);
                throw HttpRemoteTransactionMessages.MESSAGES.operationInterrupted();
            } catch (ExecutionException e) {
                try {
                    throw e.getCause();
                } catch (SecurityException ex) {
                    statusRef.set(oldVal);
                    throw ex;
                } catch (SystemException ex) {
                    statusRef.set(Status.STATUS_UNKNOWN);
                    throw ex;
                } catch (Throwable throwable) {
                    SystemException ex = new SystemException(throwable.getMessage());
                    statusRef.set(Status.STATUS_UNKNOWN);
                    ex.initCause(throwable);
                    throw ex;
                }
            }
        }
    }

    public void setRollbackOnly() throws SystemException {
        final AtomicInteger statusRef = this.statusRef;
        int oldVal = statusRef.get();
        if (oldVal == Status.STATUS_MARKED_ROLLBACK) {
            return;
        } else if (oldVal != Status.STATUS_ACTIVE) {
            throw HttpRemoteTransactionMessages.MESSAGES.invalidTxnState();
        }
        synchronized (statusRef) {
            // re-check under lock
            oldVal = statusRef.get();
            if (oldVal == Status.STATUS_MARKED_ROLLBACK) {
                return;
            } else if (oldVal != Status.STATUS_ACTIVE) {
                throw HttpRemoteTransactionMessages.MESSAGES.invalidTxnState();
            }
            statusRef.set(Status.STATUS_MARKED_ROLLBACK);
        }
    }

    @Override
    public <T> T getProviderInterface(Class<T> providerInterfaceType) {
        if(providerInterfaceType == XidProvider.class) {
            return (T) (XidProvider) () -> id;
        }
        return null;
    }
}
