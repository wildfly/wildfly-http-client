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

package org.wildfly.httpclient.naming;

import io.undertow.client.ClientRequest;
import io.undertow.util.StatusCodes;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.httpclient.common.WildflyHttpContext;
import org.wildfly.naming.client.AbstractContext;
import org.wildfly.naming.client.CloseableNamingEnumeration;
import org.wildfly.naming.client.ExhaustedDestinationsException;
import org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType;
import org.wildfly.naming.client.NamingOperation;
import org.wildfly.naming.client.ProviderEnvironment;
import org.wildfly.naming.client.RetryContext;
import org.wildfly.naming.client._private.Messages;
import org.wildfly.naming.client.util.FastHashtable;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;
import org.xnio.IoUtils;

import javax.naming.Binding;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.security.AccessController;
import java.security.GeneralSecurityException;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.security.AccessController.doPrivileged;
import static org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType.BIND;
import static org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType.CREATE_SUBCONTEXT;
import static org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType.DESTROY_SUBCONTEXT;
import static org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType.LIST;
import static org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType.LIST_BINDINGS;
import static org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType.LOOKUP;
import static org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType.LOOKUP_LINK;
import static org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType.REBIND;
import static org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType.RENAME;
import static org.wildfly.httpclient.naming.HttpNamingInvocationBuilder.InvocationType.UNBIND;
import static org.wildfly.httpclient.naming.NamingConstants.HTTPS_PORT;
import static org.wildfly.httpclient.naming.NamingConstants.HTTPS_SCHEME;
import static org.wildfly.httpclient.naming.NamingConstants.HTTP_PORT;
import static org.wildfly.httpclient.naming.NamingConstants.VALUE;

/**
 * Root naming context.
 *
 * @author Stuart Douglas
 * @author Flavia Rainone
 */
public class HttpRootContext extends AbstractContext {

    private static final int MAX_NOT_FOUND_RETRY = Integer.getInteger("org.wildfly.httpclient.naming.max-retries", 8);

    private static final AuthenticationContextConfigurationClient CLIENT = doPrivileged(AuthenticationContextConfigurationClient.ACTION);
    private static final PrivilegedAction<ClassLoader> GET_TCCL_ACTION = new PrivilegedAction<ClassLoader>() {
        @Override
        public ClassLoader run() {
            return Thread.currentThread().getContextClassLoader();
        }
    };
    private final HttpNamingProvider httpNamingProvider;
    private final String scheme;

    private static final HttpNamingEjbObjectResolverHelper helper;

    static {
        HttpNamingEjbObjectResolverHelper h = null;
        ServiceLoader<HttpNamingEjbObjectResolverHelper> sl = doPrivileged((PrivilegedAction<ServiceLoader<HttpNamingEjbObjectResolverHelper>>)
                () -> ServiceLoader.load(HttpNamingEjbObjectResolverHelper.class));
        for (Iterator<HttpNamingEjbObjectResolverHelper> it = sl.iterator(); it.hasNext(); ) {
            h = it.next();
            break;
        }
        helper = h;
    }

    protected HttpRootContext(FastHashtable<String, Object> environment, HttpNamingProvider httpNamingProvider, String scheme) {
        super(environment);
        this.httpNamingProvider = httpNamingProvider;
        this.scheme = scheme;
    }

    @Override
    public void bind(String name, Object obj) throws NamingException {
        super.bind(name, obj);
    }

    @Override
    protected Object lookupNative(Name name) throws NamingException {
        return processInvocation(LOOKUP, name);
    }

    @Override
    protected Object lookupLinkNative(Name name) throws NamingException {
        return processInvocation(LOOKUP_LINK, name);
    }

    @Override
    protected CloseableNamingEnumeration<NameClassPair> listNative(Name name) throws NamingException {
        Collection<NameClassPair> result = (Collection<NameClassPair>) processInvocation(LIST, name);
        return CloseableNamingEnumeration.fromIterable(result);
    }

    @Override
    protected CloseableNamingEnumeration<Binding> listBindingsNative(Name name) throws NamingException {
        Collection<Binding> result = (Collection<Binding>) processInvocation(LIST_BINDINGS, name);
        return CloseableNamingEnumeration.fromIterable(result);
    }

    @Override
    protected void bindNative(Name name, Object obj) throws NamingException {
        processInvocation(BIND, name, null, obj);
    }

    @Override
    protected void rebindNative(Name name, Object obj) throws NamingException {
        processInvocation(REBIND, name, null, obj);
    }

    @Override
    protected void unbindNative(Name name) throws NamingException {
        processInvocation(UNBIND, name, null, null);
    }

    @Override
    protected void renameNative(Name oldName, Name newName) throws NamingException {
        processInvocation(RENAME, oldName, newName, null);
    }

    @Override
    protected void destroySubcontextNative(Name name) throws NamingException {
        processInvocation(DESTROY_SUBCONTEXT, name, null, null);
    }

    @Override
    protected Context createSubcontextNative(Name name) throws NamingException {
        processInvocation(CREATE_SUBCONTEXT, name);
        return new HttpRemoteContext(this, name.toString());
    }

    private static Marshaller createMarshaller(URI uri, HttpMarshallerFactory httpMarshallerFactory) throws IOException {
        if (helper != null) {
            return httpMarshallerFactory.createMarshaller(helper.getObjectResolver(uri));
        }
        return httpMarshallerFactory.createMarshaller();
    }

    private static Unmarshaller createUnmarshaller(URI uri, HttpMarshallerFactory httpMarshallerFactory) throws IOException {
        if (helper != null) {
            return httpMarshallerFactory.createUnmarshaller(helper.getObjectResolver(uri));
        }
        return httpMarshallerFactory.createUnmarshaller();
    }

    private <T, R> R performWithRetry(NamingOperation<T, R> function, ProviderEnvironment environment, RetryContext context, Name name, T param) throws NamingException {
        // Directly pass-through single provider executions
        if (context == null) {
            return function.apply(null, name, param);
        }

        for (int notFound = 0; ; ) {
            try {
                R result = function.apply(context, name, param);
                environment.dropFromBlocklist(context.currentDestination());
                return result;
            } catch (NameNotFoundException e) {
                if (notFound++ > MAX_NOT_FOUND_RETRY) {
                    Messages.log.tracef("Maximum name not found attempts exceeded,");
                    throw e;
                }
                URI location = context.currentDestination();
                Messages.log.tracef("Provider (%s) did not have name \"%s\" (or a portion), retrying other nodes", location, name);

                // Always throw NameNotFoundException, unless we find it on another host
                context.addExplicitFailure(e);
                context.addTransientFail(location);
            } catch (ExhaustedDestinationsException e) {
                throw e;
            } catch (CommunicationException t) {
                URI location = context.currentDestination();
                Messages.log.tracef(t, "Communication error while contacting %s", location);
                updateBlocklist(environment, context, t);
                context.addFailure(injectDestination(t, location));
            } catch (NamingException e) {
                // All other naming exceptions are legit errors
                environment.dropFromBlocklist(context.currentDestination());
                throw e;
            } catch (Throwable t) {
                // Don't black-list generic throwables since it may indicate a client bug
                URI location = context.currentDestination();
                Messages.log.tracef(t, "Unexpected throwable while contacting %s", location);
                context.addTransientFail(location);
                context.addFailure(injectDestination(t, location));
            }
        }
    }

    private static Throwable injectDestination(Throwable t, URI destination) {
        StackTraceElement[] stackTrace = new StackTraceElement[5];
        System.arraycopy(t.getStackTrace(), 0, stackTrace, 1, 4);
        stackTrace[0] = new StackTraceElement("", "..use of destination...", destination.toString(), -1);
        t.setStackTrace(stackTrace);
        return t;
    }

    private void updateBlocklist(ProviderEnvironment environment, RetryContext context, Throwable t) {
        URI location = context.currentDestination();
        Messages.log.tracef(t, "Provider (%s) failed, blocklisting and retrying", location);
        environment.updateBlocklist(location);
    }

    private Object processInvocation(InvocationType invocationType, Name name) throws NamingException {
        ProviderEnvironment environment = httpNamingProvider.getProviderEnvironment();
        final RetryContext context = canRetry(environment) ? new RetryContext() : null;
        return performWithRetry((contextOrNull, name1, param) -> {
            HttpNamingProvider.HttpPeerIdentity peerIdentity = (HttpNamingProvider.HttpPeerIdentity) httpNamingProvider.getPeerIdentityForNamingUsingRetry(contextOrNull);
            final HttpTargetContext targetContext = WildflyHttpContext.getCurrent().getTargetContext(peerIdentity.getUri());
            HttpNamingInvocationBuilder builder = new HttpNamingInvocationBuilder().setInvocationType(invocationType).setName(name);
            final ClientRequest clientRequest = builder.createRequest(peerIdentity.getUri().getPath());
            return performOperation(name1, peerIdentity.getUri(), targetContext, clientRequest);
        }, environment, context, name, null);
    }

    private void processInvocation(InvocationType invocationType, Name name, Name newName, Object object) throws NamingException {
        ProviderEnvironment environment = httpNamingProvider.getProviderEnvironment();
        final RetryContext context = canRetry(environment) ? new RetryContext() : null;
        performWithRetry((contextOrNull, name1, param) -> {
            HttpNamingProvider.HttpPeerIdentity peerIdentity = (HttpNamingProvider.HttpPeerIdentity) httpNamingProvider.getPeerIdentityForNamingUsingRetry(contextOrNull);
            URI uri = peerIdentity.getUri();
            final HttpTargetContext targetContext = WildflyHttpContext.getCurrent().getTargetContext(uri);
            HttpNamingInvocationBuilder builder = new HttpNamingInvocationBuilder().setInvocationType(invocationType).setName(name).setNewName(newName).setObject(object);
            final ClientRequest clientRequest = builder.createRequest(uri.getPath());
            performOperation(uri, object, targetContext, clientRequest);
            return null;
        }, environment, context, name, object);

    }

    private Object performOperation(Name name, URI providerUri, HttpTargetContext targetContext, ClientRequest clientRequest) throws NamingException {
        final CompletableFuture<Object> result = new CompletableFuture<>();
        final ProviderEnvironment providerEnvironment = httpNamingProvider.getProviderEnvironment();
        final AuthenticationContext context = providerEnvironment.getAuthenticationContextSupplier().get();
        AuthenticationContextConfigurationClient client = CLIENT;
        final int defaultPort = providerUri.getScheme().equals(HTTPS_SCHEME) ? HTTPS_PORT : HTTP_PORT;
        final AuthenticationConfiguration authenticationConfiguration = client.getAuthenticationConfiguration(providerUri, context, defaultPort, "jndi", "jboss");
        final SSLContext sslContext;
        try {
            sslContext = client.getSSLContext(providerUri, context, "jndi", "jboss");
        } catch (GeneralSecurityException e) {
            final CommunicationException e2 = new CommunicationException(e.toString());
            e2.initCause(e);
            throw e2;
        }
        final ClassLoader tccl = getContextClassLoader();
        targetContext.sendRequest(clientRequest, sslContext, authenticationConfiguration, null, (input, response, closeable) -> {
            try {
                if (response.getResponseCode() == StatusCodes.NO_CONTENT) {
                    result.complete(new HttpRemoteContext(HttpRootContext.this, name.toString()));
                    IoUtils.safeClose(input);
                    return;
                }

                httpNamingProvider.performExceptionAction((a, b) -> {

                    Exception exception = null;
                    Object returned = null;
                    ClassLoader old = setContextClassLoader(tccl);
                    try {
                        final Unmarshaller unmarshaller = createUnmarshaller(providerUri, targetContext.getHttpMarshallerFactory(clientRequest));
                        unmarshaller.start(new InputStreamByteInput(input));
                        returned = unmarshaller.readObject();
                        // finish unmarshalling
                        if (unmarshaller.read() != -1) {
                            exception = HttpNamingClientMessages.MESSAGES.unexpectedDataInResponse();
                        }
                        unmarshaller.finish();

                        if (response.getResponseCode() >= 400) {
                            exception = (Exception) returned;
                        }

                    } catch (Exception e) {
                        exception = e;
                    } finally {
                        setContextClassLoader(old);
                    }
                    if (exception != null) {
                        result.completeExceptionally(exception);
                    } else {
                        result.complete(returned);
                    }
                    return null;
                }, null, null);
            } finally {
                IoUtils.safeClose(closeable);
            }
        }, result::completeExceptionally, VALUE, null, true);

        try {
            return result.get();
        } catch (InterruptedException e) {
            NamingException namingException = new NamingException(e.getMessage());
            namingException.initCause(e);
            throw namingException;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NamingException) {
                throw (NamingException) cause;
            } else if (cause instanceof IOException) {
                CommunicationException communicationException = new CommunicationException(cause.getMessage());
                communicationException.initCause(cause);
                throw communicationException;
            } else {
                NamingException namingException = new NamingException();
                namingException.initCause(cause);
                throw namingException;
            }
        }
    }

    private boolean canRetry(ProviderEnvironment environment) {
        return environment.getProviderUris().size() > 1;
    }

    private void performOperation(URI providerUri, Object object, HttpTargetContext targetContext, ClientRequest clientRequest) throws NamingException {
        final CompletableFuture<Object> result = new CompletableFuture<>();
        final ProviderEnvironment providerEnvironment = httpNamingProvider.getProviderEnvironment();
        final AuthenticationContext context = providerEnvironment.getAuthenticationContextSupplier().get();
        AuthenticationContextConfigurationClient client = CLIENT;
        final int defaultPort = providerUri.getScheme().equals(HTTPS_SCHEME) ? HTTPS_PORT : HTTP_PORT;
        final AuthenticationConfiguration authenticationConfiguration = client.getAuthenticationConfiguration(providerUri, context, defaultPort, "jndi", "jboss");
        final SSLContext sslContext;
        try {
            sslContext = client.getSSLContext(providerUri, context, "jndi", "jboss");
        } catch (GeneralSecurityException e) {
            final CommunicationException e2 = new CommunicationException(e.toString());
            e2.initCause(e);
            throw e2;
        }
        targetContext.sendRequest(clientRequest, sslContext, authenticationConfiguration, output -> {
            if (object != null) {
                Marshaller marshaller = createMarshaller(providerUri, targetContext.getHttpMarshallerFactory(clientRequest));
                marshaller.start(Marshalling.createByteOutput(output));
                marshaller.writeObject(object);
                marshaller.finish();
            }
            IoUtils.safeClose(output);
        }, (input, response, closeable) -> {
            try {
                result.complete(null);
            } finally {
                IoUtils.safeClose(closeable);
            }
        }, result::completeExceptionally, null, null);

        try {
            result.get();
        } catch (InterruptedException e) {
            NamingException namingException = new NamingException(e.getMessage());
            namingException.initCause(e);
            throw namingException;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NamingException) {
                throw (NamingException) cause;
            } else {
                NamingException namingException = new NamingException();
                namingException.initCause(cause);
                throw namingException;
            }
        }
    }

    @Override
    public void close() throws NamingException {

    }

    @Override
    public String getNameInNamespace() throws NamingException {
        final String scheme = this.scheme;
        return scheme == null || scheme.isEmpty() ? "" : scheme + ":";
    }

    static ClassLoader getContextClassLoader() {
        if(System.getSecurityManager() == null) {
            return Thread.currentThread().getContextClassLoader();
        } else {
            return AccessController.doPrivileged(GET_TCCL_ACTION);
        }
    }

    static ClassLoader setContextClassLoader(final ClassLoader cl) {

        if(System.getSecurityManager() == null) {
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(cl);
            return old;
        } else {
            return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                @Override
                public ClassLoader run() {
                    ClassLoader old = Thread.currentThread().getContextClassLoader();
                    Thread.currentThread().setContextClassLoader(cl);
                    return old;
                }
            });
        }
    }
}
