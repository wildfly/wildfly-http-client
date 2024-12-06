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

import static io.undertow.util.Headers.CONTENT_TYPE;
import static io.undertow.util.StatusCodes.BAD_REQUEST;
import static io.undertow.util.StatusCodes.INTERNAL_SERVER_ERROR;
import static io.undertow.util.StatusCodes.NO_CONTENT;
import static io.undertow.util.StatusCodes.OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.wildfly.httpclient.common.ByteInputs.byteInputOf;
import static org.wildfly.httpclient.common.ByteOutputs.byteOutputOf;
import static org.wildfly.httpclient.common.HeadersHelper.getRequestHeader;
import static org.wildfly.httpclient.common.HeadersHelper.putResponseHeader;
import static org.wildfly.httpclient.naming.Constants.NAME_PATH_PARAMETER;
import static org.wildfly.httpclient.naming.Constants.NEW_QUERY_PARAMETER;
import static org.wildfly.httpclient.naming.Constants.VALUE;
import static org.wildfly.httpclient.naming.Serializer.deserializeObject;
import static org.wildfly.httpclient.naming.Serializer.serializeObject;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.PathTemplateMatch;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.ContextClassResolver;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.AbstractServerHttpHandler;
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

    private ServerHandlers(final HttpServiceConfig config, final Context ctx, final Function<String, Boolean> classFilter) {
        this.config = config;
        this.ctx = ctx;
        this.classFilter = classFilter;
    }

    static ServerHandlers newInstance(final HttpServiceConfig config, final Context ctx, final Function<String, Boolean> classFilter) {
        return new ServerHandlers(config, ctx, classFilter);
    }

    HttpHandler handlerOf(final RequestType requestType) {
        switch (requestType) {
            case BIND:
                return new BindHandler(config, ctx, classFilter);
            case CREATE_SUBCONTEXT:
                return new CreateSubContextHandler(config, ctx);
            case DESTROY_SUBCONTEXT:
                return new DestroySubContextHandler(config, ctx);
            case LIST:
                return new ListHandler(config, ctx);
            case LIST_BINDINGS:
                return new ListBindingsHandler(config, ctx);
            case LOOKUP:
                return new LookupHandler(config, ctx);
            case LOOKUP_LINK:
                return new LookupLinkHandler(config, ctx);
            case REBIND:
                return new RebindHandler(config, ctx, classFilter);
            case RENAME:
                return new RenameHandler(config, ctx);
            case UNBIND:
                return new UnbindHandler(config, ctx);
            default:
                throw new IllegalStateException();
        }
    }

    private abstract static class AbstractNamingHandler extends AbstractServerHttpHandler {
        protected final Context ctx;
        protected final Function<String, Boolean> classFilter;

        private AbstractNamingHandler(final HttpServiceConfig config, final Context ctx) {
            this(config, ctx, null);
        }

        private AbstractNamingHandler(final HttpServiceConfig config, final Context ctx, final Function<String, Boolean> classFilter) {
            super(config);
            this.ctx = ctx;
            this.classFilter = classFilter;
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
                    exchange.setStatusCode(OK);
                } else if (result instanceof Context) {
                    exchange.setStatusCode(NO_CONTENT);
                } else {
                    putResponseHeader(exchange, CONTENT_TYPE, VALUE);
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
                sendException(exchange, INTERNAL_SERVER_ERROR, e);
            }
        }

        protected abstract Object doOperation(HttpServerExchange exchange, String name) throws NamingException;
    }

    private static final class LookupHandler extends AbstractNamingHandler {
        private LookupHandler(final HttpServiceConfig config, final Context ctx) {
            super(config, ctx);
        }

        @Override
        protected Object doOperation(final HttpServerExchange exchange, final String name) throws NamingException {
            return ctx.lookup(name);
        }
    }

    private static final class LookupLinkHandler extends AbstractNamingHandler {
        private LookupLinkHandler(final HttpServiceConfig config, final Context ctx) {
            super(config, ctx);
        }

        @Override
        protected Object doOperation(final HttpServerExchange exchange, final String name) throws NamingException {
            return ctx.lookupLink(name);
        }
    }

    private static final class CreateSubContextHandler extends AbstractNamingHandler {
        private CreateSubContextHandler(final HttpServiceConfig config, final Context ctx) {
            super(config, ctx);
        }

        @Override
        protected Object doOperation(final HttpServerExchange exchange, final String name) throws NamingException {
            return ctx.createSubcontext(name);
        }
    }

    private static final class UnbindHandler extends AbstractNamingHandler {
        private UnbindHandler(final HttpServiceConfig config, final Context ctx) {
            super(config, ctx);
        }

        @Override
        protected Object doOperation(final HttpServerExchange exchange, final String name) throws NamingException {
            ctx.unbind(name);
            return null;
        }
    }

    private static final class ListBindingsHandler extends AbstractNamingHandler {
        private ListBindingsHandler(final HttpServiceConfig config, final Context ctx) {
            super(config, ctx);
        }

        @Override
        protected Object doOperation(final HttpServerExchange exchange, final String name) throws NamingException {
            final NamingEnumeration<Binding> namingEnumeration = ctx.listBindings(name);
            return Collections.list(namingEnumeration);
        }
    }

    private static final class RenameHandler extends AbstractNamingHandler {
        private RenameHandler(final HttpServiceConfig config, final Context ctx) {
            super(config, ctx);
        }

        @Override
        protected Object doOperation(final HttpServerExchange exchange, final String name) throws NamingException {
            Deque<String> newName = exchange.getQueryParameters().get(NEW_QUERY_PARAMETER);
            if (newName == null || newName.isEmpty()) {
                exchange.setStatusCode(BAD_REQUEST);
                exchange.endExchange();
                return null;
            }
            String nn = URLDecoder.decode(newName.getFirst(), UTF_8);
            ctx.rename(name, nn);
            return null;
        }
    }

    private static final class DestroySubContextHandler extends AbstractNamingHandler {
        private DestroySubContextHandler(final HttpServiceConfig config, final Context ctx) {
            super(config, ctx);
        }

        @Override
        protected Object doOperation(final HttpServerExchange exchange, final String name) throws NamingException {
            ctx.destroySubcontext(name);
            return null;
        }
    }

    private static final class ListHandler extends AbstractNamingHandler {
        private ListHandler(final HttpServiceConfig config, final Context ctx) {
            super(config, ctx);
        }

        @Override
        protected Object doOperation(final HttpServerExchange exchange, final String name) throws NamingException {
            final NamingEnumeration<NameClassPair> namingEnumeration = ctx.list(name);
            return Collections.list(namingEnumeration);
        }
    }

    private class RebindHandler extends AbstractClassFilteringNamingHandler {
        private RebindHandler(final HttpServiceConfig config, final Context ctx, final Function<String, Boolean> classFilter) {
            super(config, ctx, classFilter);
        }


        @Override
        protected void doOperation(final String name, final Object object) throws NamingException {
            ctx.rebind(name, object);
        }
    }

    private class BindHandler extends AbstractClassFilteringNamingHandler {
        private BindHandler(final HttpServiceConfig config, final Context ctx, final Function<String, Boolean> classFilter) {
            super(config, ctx, classFilter);
        }


        @Override
        protected void doOperation(final String name, final Object object) throws NamingException {
            ctx.bind(name, object);
        }
    }

    private abstract static class AbstractClassFilteringNamingHandler extends AbstractNamingHandler {
        private AbstractClassFilteringNamingHandler(final HttpServiceConfig config, final Context ctx, final Function<String, Boolean> classFilter) {
            super(config, ctx, classFilter);
        }

        @Override
        protected Object doOperation(final HttpServerExchange exchange, final String name) throws NamingException {
            ContentType contentType = ContentType.parse(getRequestHeader(exchange, CONTENT_TYPE));
            if (contentType == null || !contentType.getType().equals(VALUE.getType()) || contentType.getVersion() != 1) {
                exchange.setStatusCode(BAD_REQUEST);
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
