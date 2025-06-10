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

import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;

import org.wildfly.client.config.ClientConfiguration;
import org.wildfly.client.config.ConfigXMLParseException;
import org.wildfly.client.config.ConfigurationXMLStreamReader;

/**
 * @author Stuart Douglas
 */
final class HttpClientXmlParser {
    private static final String NS_EJB_HTTP_CLIENT_1_0 = "urn:wildfly-http-client:1.0";
    private static final String NS_EJB_HTTP_CLIENT_1_1 = "urn:wildfly-http-client:1.1";

    private static final String ATTR_ADDRESS = "address";
    private static final String ATTR_BUFFER_SIZE = "buffer-size";
    private static final String ATTR_DIRECT = "direct";
    private static final String ATTR_MAX_SIZE = "max-size";
    private static final String ATTR_PORT = "port";
    private static final String ATTR_THREAD_LOCAL_SIZE = "thread-local-size";
    private static final String ATTR_URI = "uri";
    private static final String ATTR_VALUE = "value";

    private static final String ELEM_BIND_ADDRESS = "bind-address";
    private static final String ELEM_BUFFER_POOL = "buffer-pool";
    private static final String ELEM_CONFIG = "config";
    private static final String ELEM_CONFIGS = "configs";
    private static final String ELEM_DEFAULTS = "defaults";
    private static final String ELEM_EAGERLY_ACQUIRE_SESSION = "eagerly-acquire-session";
    private static final String ELEM_ENABLE_HTTP2 = "enable-http2";
    private static final String ELEM_IDLE_TIMEOUT = "idle-timeout";
    private static final String ELEM_MAX_CONNECTIONS = "max-connections";
    private static final String ELEM_MAX_STREAMS_PER_CONNECTION = "max-streams-per-connection";
    private static final String ELEM_TCP_NO_DELAY = "tcp-no-delay";
    private static final String ELEM_HTTP_CLIENT = "http-client";

    static WildflyHttpContext parseHttpContext() throws ConfigXMLParseException, IOException {
        final ClientConfiguration clientConfiguration = ClientConfiguration.getInstance();
        final WildflyHttpContext.Builder builder = new WildflyHttpContext.Builder();
        if (clientConfiguration != null) {
            try (final ConfigurationXMLStreamReader streamReader = clientConfiguration.readConfiguration(Set.of(NS_EJB_HTTP_CLIENT_1_0, NS_EJB_HTTP_CLIENT_1_1))) {
                parseDocument(streamReader, builder);
            }
        }
        return builder.build();
    }

    //for testing
    static WildflyHttpContext.Builder parseConfig(URI uri) throws ConfigXMLParseException {
        final WildflyHttpContext.Builder builder = new WildflyHttpContext.Builder();
        try (final ConfigurationXMLStreamReader streamReader = ClientConfiguration.getInstance(uri).readConfiguration(Set.of(NS_EJB_HTTP_CLIENT_1_0, NS_EJB_HTTP_CLIENT_1_1))) {
            parseDocument(streamReader, builder);
            return builder;
        }
    }

    private static void parseDocument(final ConfigurationXMLStreamReader reader, final WildflyHttpContext.Builder builder) throws ConfigXMLParseException {
        if (reader.hasNext()) {
            switch (reader.nextTag()) {
                case START_ELEMENT: {
                    switch (reader.getNamespaceURI()) {
                        case NS_EJB_HTTP_CLIENT_1_0:
                        case NS_EJB_HTTP_CLIENT_1_1:
                            break;
                        default:
                            throw reader.unexpectedElement();
                    }
                    switch (reader.getLocalName()) {
                        case ELEM_HTTP_CLIENT: {
                            parseRootElement(reader, builder);
                            break;
                        }
                        default:
                            throw reader.unexpectedElement();
                    }
                    break;
                }
                default: {
                    throw reader.unexpectedContent();
                }
            }
        }
    }

    private static void parseRootElement(final ConfigurationXMLStreamReader reader, final WildflyHttpContext.Builder builder) throws ConfigXMLParseException {

        final int attributeCount = reader.getAttributeCount();
        for (int i = 0; i < attributeCount; i++) {
            switch (reader.getAttributeLocalName(i)) {
                default: {
                    throw reader.unexpectedAttribute(i);
                }
            }
        }
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case START_ELEMENT: {
                    switch (reader.getNamespaceURI()) {
                        case NS_EJB_HTTP_CLIENT_1_0:
                        case NS_EJB_HTTP_CLIENT_1_1:
                            break;
                        default:
                            throw reader.unexpectedElement();
                    }
                    switch (reader.getLocalName()) {
                        case ELEM_CONFIGS: {
                            parseConfigsElement(reader, builder);
                            break;
                        }
                        case ELEM_DEFAULTS: {
                            parseDefaults(reader, builder);
                            break;
                        }
                        default:
                            throw reader.unexpectedElement();
                    }
                    break;
                }
                case END_ELEMENT: {
                    return;
                }
            }
        }
    }

    private static InetSocketAddress parseBind(final ConfigurationXMLStreamReader reader) throws ConfigXMLParseException {
        final int attributeCount = reader.getAttributeCount();
        String address = null;
        int port = 0;
        for (int i = 0; i < attributeCount; i++) {
            switch (reader.getAttributeLocalName(i)) {
                case ATTR_ADDRESS: {
                    address = reader.getAttributeValueResolved(i);
                    break;
                }
                case ATTR_PORT: {
                    port = reader.getIntAttributeValueResolved(i);
                    if (port < 0 || port > 65535) {
                        throw HttpClientMessages.MESSAGES.portValueOutOfRange(port);
                    }
                    break;
                }
                default: {
                    throw reader.unexpectedAttribute(i);
                }
            }
        }
        if (address == null) {
            throw reader.missingRequiredAttribute(null, ATTR_ADDRESS);
        }
        final InetSocketAddress bindAddress = InetSocketAddress.createUnresolved(address, port);
        switch (reader.nextTag()) {
            case END_ELEMENT: {
                return bindAddress;
            }
            default: {
                throw reader.unexpectedElement();
            }
        }
    }

    private static long parseLongElement(final ConfigurationXMLStreamReader reader) throws ConfigXMLParseException {
        final int attributeCount = reader.getAttributeCount();
        Long value = null;
        for (int i = 0; i < attributeCount; i++) {
            switch (reader.getAttributeLocalName(i)) {
                case ATTR_VALUE: {
                    value = reader.getLongAttributeValueResolved(i);
                    break;
                }
                default: {
                    throw reader.unexpectedAttribute(i);
                }
            }
        }
        if (value == null) {
            throw reader.missingRequiredAttribute(null, ATTR_VALUE);
        }
        switch (reader.nextTag()) {
            case END_ELEMENT: {
                return value;
            }
            default: {
                throw reader.unexpectedElement();
            }
        }
    }

    private static int parseIntElement(final ConfigurationXMLStreamReader reader) throws ConfigXMLParseException {
        final int attributeCount = reader.getAttributeCount();
        Integer value = null;
        for (int i = 0; i < attributeCount; i++) {
            switch (reader.getAttributeLocalName(i)) {
                case ATTR_VALUE: {
                    value = reader.getIntAttributeValueResolved(i);
                    break;
                }
                default: {
                    throw reader.unexpectedAttribute(i);
                }
            }
        }
        if (value == null) {
            throw reader.missingRequiredAttribute(null, ATTR_VALUE);
        }
        switch (reader.nextTag()) {
            case END_ELEMENT: {
                return value;
            }
            default: {
                throw reader.unexpectedElement();
            }
        }
    }

    private static boolean parseBooleanElement(final ConfigurationXMLStreamReader reader) throws ConfigXMLParseException {
        final int attributeCount = reader.getAttributeCount();
        Boolean value = null;
        for (int i = 0; i < attributeCount; i++) {
            switch (reader.getAttributeLocalName(i)) {
                case ATTR_VALUE: {
                    value = reader.getBooleanAttributeValueResolved(i);
                    break;
                }
                default: {
                    throw reader.unexpectedAttribute(i);
                }
            }
        }
        if (value == null) {
            throw reader.missingRequiredAttribute(null, ATTR_VALUE);
        }
        switch (reader.nextTag()) {
            case END_ELEMENT: {
                return value;
            }
            default: {
                throw reader.unexpectedElement();
            }
        }
    }

    private static void parseConfigsElement(final ConfigurationXMLStreamReader reader, final WildflyHttpContext.Builder builder) throws ConfigXMLParseException {
        final int attributeCount = reader.getAttributeCount();
        if (attributeCount > 0) {
            throw reader.unexpectedAttribute(0);
        }
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case START_ELEMENT: {
                    switch (reader.getNamespaceURI()) {
                        case NS_EJB_HTTP_CLIENT_1_0:
                        case NS_EJB_HTTP_CLIENT_1_1:
                            break;
                        default:
                            throw reader.unexpectedElement();
                    }
                    switch (reader.getLocalName()) {
                        case ELEM_CONFIG: {
                            parseConfig(reader, builder);
                            break;
                        }
                        default:
                            throw reader.unexpectedElement();
                    }
                    break;
                }
                case END_ELEMENT: {
                    return;
                }
            }
        }
    }

    private static void parseDefaults(final ConfigurationXMLStreamReader reader, final WildflyHttpContext.Builder builder) throws ConfigXMLParseException {
        if (reader.getAttributeCount() > 0) {
            throw reader.unexpectedAttribute(0);
        }

        HttpClientSchemaVersion version = HttpClientSchemaVersion.V1_0;
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case START_ELEMENT: {
                    switch (reader.getNamespaceURI()) {
                        case NS_EJB_HTTP_CLIENT_1_0:
                            break;
                        case NS_EJB_HTTP_CLIENT_1_1:
                            version = HttpClientSchemaVersion.V1_1;
                            break;
                        default:
                            throw reader.unexpectedElement();
                    }

                    final String localName = version.getLocalName(reader);
                    switch (localName) {
                        case ELEM_BIND_ADDRESS: {
                            builder.setDefaultBindAddress(parseBind(reader));
                            break;
                        }
                        case ELEM_IDLE_TIMEOUT: {
                            builder.setIdleTimeout(parseLongElement(reader));
                            break;
                        }
                        case ELEM_MAX_CONNECTIONS: {
                            builder.setMaxConnections(parseIntElement(reader));
                            break;
                        }
                        case ELEM_MAX_STREAMS_PER_CONNECTION: {
                            builder.setMaxStreamsPerConnection(parseIntElement(reader));
                            break;
                        }
                        case ELEM_EAGERLY_ACQUIRE_SESSION: {
                            builder.setEagerlyAcquireSession(parseBooleanElement(reader));
                            break;
                        }
                        case ELEM_ENABLE_HTTP2: {
                            builder.setEnableHttp2(parseBooleanElement(reader));
                            break;
                        }
                        case ELEM_TCP_NO_DELAY: {
                            builder.setTcpNoDelay(parseBooleanElement(reader));
                            break;
                        }
                        case ELEM_BUFFER_POOL: {
                            builder.setBufferConfig(parseBufferConfig(reader));
                            break;
                        }
                        default:
                            throw reader.unexpectedElement();
                    }
                    break;
                }
                case END_ELEMENT: {
                    return;
                }
            }
        }
    }

    private static WildflyHttpContext.BufferBuilder parseBufferConfig(final ConfigurationXMLStreamReader reader) throws ConfigXMLParseException {
        final int attributeCount = reader.getAttributeCount();
        Integer bufferSize = null;
        Integer maxSize = null;
        Integer threadLocalSize = null;
        Boolean direct = null;
        for (int i = 0; i < attributeCount; i++) {
            switch (reader.getAttributeLocalName(i)) {
                case ATTR_BUFFER_SIZE: {
                    bufferSize = reader.getIntAttributeValueResolved(i);
                    break;
                }
                case ATTR_MAX_SIZE: {
                    maxSize = reader.getIntAttributeValueResolved(i);
                    break;
                }
                case ATTR_THREAD_LOCAL_SIZE: {
                    threadLocalSize = reader.getIntAttributeValueResolved(i);
                    break;
                }
                case ATTR_DIRECT: {
                    direct = reader.getBooleanAttributeValueResolved(i);
                    break;
                }
                default: {
                    throw reader.unexpectedAttribute(i);
                }
            }
        }
        if (bufferSize == null) {
            throw reader.missingRequiredAttribute(null, ATTR_VALUE);
        }
        WildflyHttpContext.BufferBuilder value = new WildflyHttpContext.BufferBuilder();
        value.setBufferSize(bufferSize);
        if(maxSize != null) {
            value.setMaxSize(maxSize);
        }
        if(threadLocalSize != null) {
            value.setThreadLocalSize(threadLocalSize);
        }
        if(direct != null) {
            value.setDirect( direct);
        }
        switch (reader.nextTag()) {
            case END_ELEMENT: {
                return value;
            }
            default: {
                throw reader.unexpectedElement();
            }
        }
    }

    private static void parseConfig(final ConfigurationXMLStreamReader reader, final WildflyHttpContext.Builder builder) throws ConfigXMLParseException {

        final int attributeCount = reader.getAttributeCount();
        URI uri = null;
        for (int i = 0; i < attributeCount; i++) {
            switch (reader.getAttributeLocalName(i)) {
                case ATTR_URI: {
                    uri = reader.getURIAttributeValueResolved(i);
                    break;
                }
                default: {
                    throw reader.unexpectedAttribute(i);
                }
            }
        }
        if (uri == null) {
            throw reader.missingRequiredAttribute(null, ATTR_URI);
        }
        final WildflyHttpContext.Builder.HttpConfigBuilder targetBuilder = builder.addConfig(uri);

        HttpClientSchemaVersion version = HttpClientSchemaVersion.V1_0;
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case START_ELEMENT: {
                    switch (reader.getNamespaceURI()) {
                        case NS_EJB_HTTP_CLIENT_1_0:
                            break;
                        case NS_EJB_HTTP_CLIENT_1_1:
                            version = HttpClientSchemaVersion.V1_1;
                            break;
                        default:
                            throw reader.unexpectedElement();
                    }

                    final String localName = version.getLocalName(reader);
                    switch (localName) {
                        case ELEM_IDLE_TIMEOUT: {
                            targetBuilder.setIdleTimeout(parseLongElement(reader));
                            break;
                        }
                        case ELEM_MAX_CONNECTIONS: {
                            targetBuilder.setMaxConnections(parseIntElement(reader));
                            break;
                        }
                        case ELEM_MAX_STREAMS_PER_CONNECTION: {
                            targetBuilder.setMaxStreamsPerConnection(parseIntElement(reader));
                            break;
                        }
                        case ELEM_EAGERLY_ACQUIRE_SESSION: {
                            targetBuilder.setEagerlyAcquireSession(parseBooleanElement(reader));
                            break;
                        }
                        case ELEM_ENABLE_HTTP2: {
                            targetBuilder.setEnableHttp2(parseBooleanElement(reader));
                            break;
                        }
                        case ELEM_BIND_ADDRESS: {
                            targetBuilder.setBindAddress(parseBind(reader));
                            break;
                        }
                        case ELEM_TCP_NO_DELAY: {
                            targetBuilder.setTcpNoDelay(parseBooleanElement(reader));
                            break;
                        }
                        default:
                            throw reader.unexpectedElement();
                    }
                    break;
                }
                case END_ELEMENT: {
                    return;
                }
            }
        }
    }

    enum HttpClientSchemaVersion {
        V1_0(NS_EJB_HTTP_CLIENT_1_0,
            List.of(
                ELEM_BIND_ADDRESS, ELEM_BUFFER_POOL, ELEM_CONFIG, ELEM_CONFIGS, ELEM_DEFAULTS,
                ELEM_EAGERLY_ACQUIRE_SESSION, ELEM_ENABLE_HTTP2, ELEM_IDLE_TIMEOUT, ELEM_MAX_CONNECTIONS,
                ELEM_MAX_STREAMS_PER_CONNECTION
            )),
        V1_1(NS_EJB_HTTP_CLIENT_1_1,
            List.of(
                ELEM_BIND_ADDRESS, ELEM_BUFFER_POOL, ELEM_CONFIG, ELEM_CONFIGS, ELEM_DEFAULTS,
                ELEM_EAGERLY_ACQUIRE_SESSION, ELEM_ENABLE_HTTP2, ELEM_IDLE_TIMEOUT, ELEM_MAX_CONNECTIONS,
                ELEM_MAX_STREAMS_PER_CONNECTION, ELEM_TCP_NO_DELAY
            ));

        private final String namespace;
        private final List<String> elements;

        HttpClientSchemaVersion(String namespace, List<String> elements) {
            this.namespace = namespace;
            this.elements = elements;
        }

        public String getNamespace() {
            return namespace;
        }

        public String getLocalName(ConfigurationXMLStreamReader reader) throws ConfigXMLParseException {
            String element = reader.getLocalName();
            if (!elements.contains(element)) {
                throw reader.unexpectedElement();
            }

            return element;
        }
    }
}
