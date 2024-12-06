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

/**
 * @author Stuart Douglas
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class ContentType {

    private static final String VERSION_PREFIX = "version=";
    private static final String VERSION_SEPARATOR = ";";
    private final String type;
    private final String typeAndVersion;
    private final int version;

    public ContentType(final String type, final int version) {
        this.type = type;
        this.version = version;
        this.typeAndVersion = type + VERSION_SEPARATOR + VERSION_PREFIX  + version;
    }

    public String getType() {
        return type;
    }

    public int getVersion() {
        return version;
    }

    public String toString() {
        return typeAndVersion;
    }

    @Override
    public int hashCode() {
        return typeAndVersion.hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof ContentType)) return false;
        final ContentType other = (ContentType) obj;
        return typeAndVersion.equals(other.typeAndVersion);
    }

    public static ContentType parse(final String type) {
        final String[] parts = type == null ? null : type.split(VERSION_SEPARATOR);
        if (parts == null || parts.length == 0) return null;
        final String part0 = parts[0].trim();
        if (part0.isEmpty()) return null;

        int version = -1;
        String part;
        for (int i = 1; i < parts.length; ++i) {
            part = parts[i].trim();
            if (part.startsWith(VERSION_PREFIX)) {
                version = Integer.parseInt(part.substring(VERSION_PREFIX.length()));
                break;
            }
        }
        return new ContentType(part0, version);
    }

}
