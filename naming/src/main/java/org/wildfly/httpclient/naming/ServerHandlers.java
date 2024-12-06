/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2024 Red Hat, Inc., and individual contributors
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.wildfly.httpclient.common.ByteInputs.byteInputOf;
import static org.wildfly.httpclient.common.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.common.HttpServerHelper.sendException;
import static org.wildfly.httpclient.naming.Constants.NAME_PATH_PARAMETER;
import static org.wildfly.httpclient.naming.Constants.NEW_QUERY_PARAMETER;
import static org.wildfly.httpclient.naming.Constants.VALUE;
import static org.wildfly.httpclient.naming.Serializer.deserializeObject;
import static org.wildfly.httpclient.naming.Serializer.serializeObject;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.PathTemplateMatch;
import io.undertow.util.StatusCodes;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.ContextClassResolver;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.ContentType;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpServiceConfig;

import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.NameClassPair;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Deque;
import java.util.function.Function;

/**
 * Utility class providing factory methods for creating server-side handlers of Remote JNDI over HTTP protocol.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ServerHandlers {

    private final Context ctx;
    private final Function<String, Boolean> classFilter;
    private final HttpServiceConfig config;

    private ServerHandlers(final Context ctx, final Function<String, Boolean> classFilter, final HttpServiceConfig config) {
        this.ctx = ctx;
        this.classFilter = classFilter;
        this.config = config;
    }

    static ServerHandlers newInstance(final Context ctx, final Function<String, Boolean> classFilter, final HttpServiceConfig config) {
        return new ServerHandlers(ctx, classFilter, config);
    }

    HttpHandler handlerOf(final RequestType requestType) {
        switch (requestType) {
            case BIND:
                return new BindHandler(ctx, config, classFilter);
            case CREATE_SUBCONTEXT:
                return new CreateSubContextHandler(ctx, config);
            case DESTROY_SUBCONTEXT:
                return new DestroySubContextHandler(ctx, config);
            case LIST:
                return new ListHandler(ctx, config);
            case LIST_BINDINGS:
                return new ListBindingsHandler(ctx, config);
            case LOOKUP:
                return new LookupHandler(ctx, config);
            case LOOKUP_LINK:
                return new LookupLinkHandler(ctx, config);
            case REBIND:
                return new RebindHandler(ctx, config, classFilter);
            case RENAME:
                return new RenameHandler(ctx, config);
            case UNBIND:
                return new UnbindHandler(ctx, config);
            default:
                throw new IllegalStateException();
        }
    }

    private abstract static class AbstractNamingHandler implements HttpHandler {
        protected final Context ctx;
        protected final HttpServiceConfig config;
        protected final Function<String, Boolean> classFilter;

        private AbstractNamingHandler(final Context ctx, final HttpServiceConfig config) {
            this(ctx, null, config);
        }

        private AbstractNamingHandler(final Context ctx, final Function<String, Boolean> classFilter, final HttpServiceConfig config) {
            this.ctx = ctx;
            this.classFilter = classFilter;
            this.config = config;
        }

        @Override
        public final void handleRequest(HttpServerExchange exchange) throws Exception {
            PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
            String name = URLDecoder.decode(params.getParameters().get(NAME_PATH_PARAMETER), UTF_8);
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
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, VALUE.toString());
                    HttpNamingServerObjectResolver resolver = new HttpNamingServerObjectResolver(exchange);
                    Marshaller marshaller = config.getHttpMarshallerFactory(exchange).createMarshaller(resolver);
                    ByteOutput out = byteOutputOf(exchange.getOutputStream());
                    try (out) {
                        marshaller.start(out);
                        serializeObject(marshaller, result);
                        marshaller.finish();
                    }
                }
            } catch (Throwable e) {
                sendException(exchange, config, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }

        protected abstract Object doOperation(HttpServerExchange exchange, String name) throws NamingException;
    }

    private static final class LookupHandler extends AbstractNamingHandler {
        private LookupHandler(final Context ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            return ctx.lookup(name);
        }
    }

    private static final class LookupLinkHandler extends AbstractNamingHandler {
        private LookupLinkHandler(final Context ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            return ctx.lookupLink(name);
        }
    }

    private static final class CreateSubContextHandler extends AbstractNamingHandler {
        private CreateSubContextHandler(final Context ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            return ctx.createSubcontext(name);
        }
    }

    private static final class UnbindHandler extends AbstractNamingHandler {
        private UnbindHandler(final Context ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            ctx.unbind(name);
            return null;
        }
    }

    private static final class ListBindingsHandler extends AbstractNamingHandler {
        private ListBindingsHandler(final Context ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            final NamingEnumeration<Binding> namingEnumeration = ctx.listBindings(name);
            return Collections.list(namingEnumeration);
        }
    }

    private static final class RenameHandler extends AbstractNamingHandler {
        private RenameHandler(final Context ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            Deque<String> newName = exchange.getQueryParameters().get(NEW_QUERY_PARAMETER);
            if (newName == null || newName.isEmpty()) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                exchange.endExchange();
                return null;
            }
            String nn = URLDecoder.decode(newName.getFirst(), UTF_8);
            ctx.rename(name, nn);
            return null;
        }
    }

    private static final class DestroySubContextHandler extends AbstractNamingHandler {
        private DestroySubContextHandler(final Context ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            ctx.destroySubcontext(name);
            return null;
        }
    }

    private static final class ListHandler extends AbstractNamingHandler {
        private ListHandler(final Context ctx, final HttpServiceConfig config) {
            super(ctx, config);
        }

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            final NamingEnumeration<NameClassPair> namingEnumeration = ctx.list(name);
            return Collections.list(namingEnumeration);
        }
    }

    private class RebindHandler extends AbstractClassFilteringNamingHandler {
        private RebindHandler(final Context ctx, final HttpServiceConfig config, final Function<String, Boolean> classFilter) {
            super(ctx, config, classFilter);
        }


        @Override
        protected void doOperation(String name, Object object) throws NamingException {
            ctx.rebind(name, object);
        }
    }

    private class BindHandler extends AbstractClassFilteringNamingHandler {
        private BindHandler(final Context ctx, final HttpServiceConfig config, final Function<String, Boolean> classFilter) {
            super(ctx, config, classFilter);
        }


        @Override
        protected void doOperation(String name, Object object) throws NamingException {
            ctx.bind(name, object);
        }
    }

    private abstract static class AbstractClassFilteringNamingHandler extends AbstractNamingHandler {
        private AbstractClassFilteringNamingHandler(final Context ctx, final HttpServiceConfig config, final Function<String, Boolean> classFilter) {
            super(ctx, classFilter, config);
        }

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            ContentType contentType = ContentType.parse(exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE));
            if (contentType == null || !contentType.getType().equals(VALUE.getType()) || contentType.getVersion() != 1) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                exchange.endExchange();
                return null;
            }
            final HttpMarshallerFactory marshallerFactory = config.getHttpUnmarshallerFactory(exchange);
            final InputStream is = exchange.getInputStream();
            try (ByteInput in = byteInputOf(is)) {
                Unmarshaller unmarshaller = classFilter != null ?
                        marshallerFactory.createUnmarshaller(new FilterClassResolver(classFilter)):
                        marshallerFactory.createUnmarshaller();
                unmarshaller.start(in);
                Object object = deserializeObject(unmarshaller);
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

        protected abstract void doOperation(String name, Object object) throws NamingException;
    }

    private static final class FilterClassResolver extends ContextClassResolver {
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
