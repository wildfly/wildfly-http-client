/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2025 Red Hat, Inc., and individual contributors
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

import static java.lang.Integer.decode;
import static org.wildfly.httpclient.common.HeadersHelper.getRequestHeader;
import static org.wildfly.httpclient.common.HeadersHelper.getResponseHeader;
import static org.wildfly.httpclient.common.HeadersHelper.putRequestHeader;
import static org.wildfly.httpclient.common.HeadersHelper.putResponseHeader;

import java.util.Objects;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class Version implements Comparable<Version>{

    static final AttachmentKey<Version> KEY = AttachmentKey.create(Version.class);

    private static final HttpString PROTOCOL_VERSION = new HttpString("x-wf-version");
    private static final int MASK_HANDLER  = 0b00000000_00000000_00000000_01111111;
    private static final int MASK_SPEC     = 0b00000000_00000000_00011111_10000000;
    private static final int MASK_ENCODING = 0b00000000_00000000_11100000_00000000;

    public static final Version JAVA_EE_8 = new Version(Handler.VERSION_1, Specification.JAVA_EE_8, Encoding.JBOSS_MARSHALLING);
    public static final Version JAKARTA_EE_10 = new Version(Handler.VERSION_2, Specification.JAKARTA_EE_10, Encoding.JBOSS_MARSHALLING);
    public static final Version LATEST = JAKARTA_EE_10;

    private final Handler handlerVersion;
    private final Specification specVersion;
    private final Encoding encodingVersion;
    private final int version;

    private Version(final Handler handlerVersion, final Specification specVersion, final Encoding encodingVersion) {
        this.handlerVersion = handlerVersion;
        this.specVersion = specVersion;
        this.encodingVersion = encodingVersion;
        this.version = (encodingVersion.value << 13) | (specVersion.value << 7) | (handlerVersion.value);
    }

    static Version of(final int version) {
        // return identities for known constants
        if (version == 1) return JAVA_EE_8;
        if (version == JAKARTA_EE_10.version) return JAKARTA_EE_10;
        // create new instances for unknown contants
        final Handler handlerVersion = Handler.of((version & MASK_HANDLER) >>> 1);
        final Specification specVersion = Specification.of((version & MASK_SPEC) >>> 7);
        final Encoding encodingVersion = Encoding.of((version & MASK_ENCODING) >>> 13);
        return new Version(handlerVersion, specVersion, encodingVersion);
    }

    static Version of(final Handler handlerVersion, final Specification specVersion) {
        if (Handler.VERSION_1.equals(handlerVersion) && Specification.JAVA_EE_8.equals(specVersion)) return JAVA_EE_8;
        if (Handler.VERSION_2.equals(handlerVersion) && Specification.JAKARTA_EE_10.equals(specVersion)) return JAKARTA_EE_10;
        return new Version(handlerVersion, specVersion, Encoding.JBOSS_MARSHALLING);
    }

    @Override
    public String toString() {
        return String.valueOf(this == JAVA_EE_8 ? 1 : version);
    }

    @Override
    public int compareTo(final Version o) {
        return Integer.compare(version, o.version);
    }

    public Handler handler() {
        return handlerVersion;
    }

    public Specification specitication() {
        return specVersion;
    }

    public Encoding encoding() {
        return encodingVersion;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) return true;
        if (!(o instanceof Version)) return false;
        final Version v = (Version)o;
        return handlerVersion.equals(v.handlerVersion) &&
               specVersion.equals(v.specVersion) &&
               encodingVersion.equals(v.encodingVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(handlerVersion, specVersion, encodingVersion);
    }

    public static Version readFrom(final HttpServerExchange exchange) {
        final String versionHeader = getRequestHeader(exchange, PROTOCOL_VERSION);
        return versionHeader == null || "1".equals(versionHeader) ? JAVA_EE_8 : Version.of(decode(versionHeader));
    }

    public static Version readFrom(final ClientExchange exchange) {
        final String versionHeader = getResponseHeader(exchange.getResponse(), PROTOCOL_VERSION);
        return versionHeader == null || "1".equals(versionHeader) ? JAVA_EE_8 : Version.of(decode(versionHeader));
    }

    public void writeTo(final HttpServerExchange exchange) {
        if (this == JAVA_EE_8) {
            putResponseHeader(exchange, PROTOCOL_VERSION, String.valueOf(1));
        } else {
            putResponseHeader(exchange, PROTOCOL_VERSION, String.valueOf(version));
        }
    }

    public void writeTo(final ClientRequest request) {
        if (this == JAVA_EE_8) {
            putRequestHeader(request, PROTOCOL_VERSION, String.valueOf(1));
        } else {
            putRequestHeader(request, PROTOCOL_VERSION, String.valueOf(version));
        }
    }

    public enum Handler {
        VERSION_1(1),
        VERSION_2(2);

        private final int value;

        Handler(final int value) {
            this.value = value;
        }

        static Handler of(final int value) {
            for (Handler handler : values()) {
                if (value == handler.value) return handler;
            }
            throw new IllegalArgumentException("Unsupported Handler Version");
        }
    }

    public enum Specification {
        JAVA_EE_8(-1),
        JAKARTA_EE_10(0);

        private final int value;

        Specification(final int value) {
            this.value = value;
        }

        static Specification of(final int value) {
            for (Specification spec : values()) {
                if (value == spec.value) return spec;
            }
            throw new IllegalArgumentException("Unsupported Specification Version");
        }
    }

    public enum Encoding {
        JBOSS_MARSHALLING(0);

        private final int value;

        Encoding(final int value) {
            this.value = value;
        }

        private static Encoding of(final int value) {
            for (Encoding encoding : values()) {
                if (value == encoding.value) return encoding;
            }
            throw new IllegalArgumentException("Unsupported Encoding Version");
        }
    }

}
