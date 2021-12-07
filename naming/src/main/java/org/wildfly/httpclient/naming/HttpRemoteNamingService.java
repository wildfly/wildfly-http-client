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

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Deque;
import java.util.function.Function;
import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.NameClassPair;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;

import org.jboss.marshalling.ContextClassResolver;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.marshalling.river.RiverMarshallerFactory;
import org.wildfly.httpclient.common.ContentType;
import org.wildfly.httpclient.common.ElytronIdentityHandler;
import org.wildfly.httpclient.common.HttpServerHelper;
import org.wildfly.httpclient.common.NoFlushByteOutput;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.PathTemplateMatch;
import io.undertow.util.StatusCodes;

/**
 * @author Stuart Douglas
 */
public class HttpRemoteNamingService {

    private static final String UTF_8 = "UTF-8";
    private static final String LOOKUP = "/v1/lookup/{name}";
    private static final String LOOKUPLINK = "/v1/lookuplink/{name}";
    private static final String BIND = "/v1/bind/{name}";
    private static final String REBIND = "/v1/rebind/{name}";
    private static final String UNBIND = "/v1/unbind/{name}";
    private static final String DESTROY_SUBCONTEXT = "/v1/dest-subctx/{name}";
    private static final String LIST = "/v1/list/{name}";
    private static final String LIST_BINDINGS = "/v1/list-bindings/{name}";
    private static final String RENAME = "/v1/rename/{name}";
    private static final String CREATE_SUBCONTEXT = "/v1/create-subcontext/{name}";

    private static final MarshallerFactory MARSHALLER_FACTORY = new RiverMarshallerFactory();

    private final Context localContext;
    private final Function<String, Boolean> classResolverFilter;

    public HttpRemoteNamingService(Context localContext) {
        this(localContext, null);
    }

    public HttpRemoteNamingService(Context localContext, Function<String, Boolean> classResolverFilter) {
        this.localContext = localContext;
        this.classResolverFilter = classResolverFilter;
    }


    public HttpHandler createHandler() {
        RoutingHandler routingHandler = new RoutingHandler();
        routingHandler.add(Methods.POST, LOOKUP, new LookupHandler());
        routingHandler.add(Methods.GET, LOOKUPLINK, new LookupLinkHandler());
        routingHandler.add(Methods.PUT, BIND, new BindHandler());
        routingHandler.add(Methods.PATCH, REBIND, new RebindHandler());
        routingHandler.add(Methods.DELETE, UNBIND, new UnbindHandler());
        routingHandler.add(Methods.DELETE, DESTROY_SUBCONTEXT, new DestroySubcontextHandler());
        routingHandler.add(Methods.GET, LIST, new ListHandler());
        routingHandler.add(Methods.GET, LIST_BINDINGS, new ListBindingsHandler());
        routingHandler.add(Methods.PATCH, RENAME, new RenameHandler());
        routingHandler.add(Methods.PUT, CREATE_SUBCONTEXT, new CreateSubContextHandler());
        return new BlockingHandler(new ElytronIdentityHandler(routingHandler));
    }


    private abstract class NameHandler implements HttpHandler {

        @Override
        public final void handleRequest(HttpServerExchange exchange) throws Exception {
            PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
            String name = URLDecoder.decode(params.getParameters().get("name"), UTF_8);
            try {
                Object result = doOperation(exchange, name);
                if (exchange.isComplete()) {
                    return;
                }
                if (result == null) {
                    exchange.setStatusCode(StatusCodes.OK);
                } else if (result instanceof Context) {
                    exchange.setStatusCode(StatusCodes.NO_CONTENT);
                } else {
                    doMarshall(exchange, result);
                }
            } catch (Throwable e) {
                sendException(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }

        protected abstract Object doOperation(HttpServerExchange exchange, String name) throws NamingException;
    }

    private final class LookupHandler extends NameHandler {

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            return localContext.lookup(name);
        }
    }

    private final class LookupLinkHandler extends NameHandler {

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            return localContext.lookupLink(name);
        }
    }

    private class CreateSubContextHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            return localContext.createSubcontext(name);
        }
    }

    private class UnbindHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            localContext.unbind(name);
            return null;
        }
    }

    private class ListBindingsHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            final NamingEnumeration<Binding> namingEnumeration = localContext.listBindings(name);
            return Collections.list(namingEnumeration);
        }
    }

    private class RenameHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            Deque<String> newName = exchange.getQueryParameters().get("new");
            if (newName == null || newName.isEmpty()) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                exchange.endExchange();
                return null;
            }
            try {
                String nn = URLDecoder.decode(newName.getFirst(), UTF_8);
                localContext.rename(name, nn);
                return null;
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private class DestroySubcontextHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            localContext.destroySubcontext(name);
            return null;
        }
    }

    private class ListHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            final NamingEnumeration<NameClassPair> namingEnumeration = localContext.list(name);
            return Collections.list(namingEnumeration);
        }
    }

    private class RebindHandler extends BindHandler {

        @Override
        protected void doOperation(String name, Object object) throws NamingException {
            localContext.rebind(name, object);
        }
    }

    private class BindHandler extends NameHandler {
        @Override
        protected final Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            ContentType contentType = ContentType.parse(exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE));
            if (contentType == null || !contentType.getType().equals("application/x-wf-jndi-jbmar-value") || contentType.getVersion() != 1) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                exchange.endExchange();
                return null;
            }
            final MarshallingConfiguration marshallingConfiguration = new MarshallingConfiguration();
            marshallingConfiguration.setVersion(2);
            if (classResolverFilter != null) {
                marshallingConfiguration.setClassResolver(new FilterClassResolver(classResolverFilter));
            }
            try (InputStream inputStream = exchange.getInputStream()) {
                Unmarshaller unmarshaller = MARSHALLER_FACTORY.createUnmarshaller(marshallingConfiguration);
                unmarshaller.start(new InputStreamByteInput(inputStream));
                Object object = unmarshaller.readObject();
                unmarshaller.finish();
                doOperation(name, object);
            } catch (Exception e) {
                if (e instanceof NamingException) {
                    throw (NamingException)e;
                }
                NamingException nm = new NamingException(e.getMessage());
                nm.initCause(e);
                throw nm;
            }
            return null;
        }

        protected void doOperation(String name, Object object) throws NamingException {
            localContext.bind(name, object);
        }
    }

    private static void doMarshall(HttpServerExchange exchange, Object result) throws IOException {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/x-wf-jndi-jbmar-value;version=1");
        final MarshallingConfiguration marshallingConfiguration = new MarshallingConfiguration();
        marshallingConfiguration.setVersion(2);
        Marshaller marshaller = MARSHALLER_FACTORY.createMarshaller(marshallingConfiguration);
        marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(exchange.getOutputStream())));
        marshaller.writeObject(result);
        marshaller.finish();
        marshaller.flush();
    }

    public static void sendException(HttpServerExchange exchange, int status, Throwable e) throws IOException {
        HttpServerHelper.sendException(exchange, status, e);
    }

    private static class FilterClassResolver extends ContextClassResolver {
        private final Function<String, Boolean> filter;

        private FilterClassResolver(Function<String, Boolean> filter) {
            this.filter = filter;
        }

        @Override
        public Class<?> resolveClass(Unmarshaller unmarshaller, String name, long serialVersionUID) throws IOException, ClassNotFoundException {
            checkFilter(name);
            return super.resolveClass(unmarshaller, name, serialVersionUID);
        }

        @Override
        public Class<?> resolveProxyClass(Unmarshaller unmarshaller, String[] interfaces) throws IOException, ClassNotFoundException {
            for (String name : interfaces) {
                checkFilter(name);
            }
            return super.resolveProxyClass(unmarshaller, interfaces);
        }

        private void checkFilter(String className) throws InvalidClassException {
            if (filter.apply(className) != Boolean.TRUE) {
                throw HttpNamingClientMessages.MESSAGES.cannotResolveFilteredClass(className);
            }
        }
    }
}
