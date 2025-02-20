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
package org.wildfly.httpclient.common;

import org.jboss.marshalling.ByteInput;

import java.io.IOException;
import java.io.InputStream;

/**
 * Helper class. Provides various utility methods for example:
 * <ul>
 *     <li>transforming InputStreams to ByteInputs</li>
 * </ul>
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class ByteInputs {

    private ByteInputs() {
        // forbidden instantiation
    }

    public static ByteInput byteInputOf(final InputStream delegate) {
        if (delegate == null) throw new IllegalArgumentException();
        return new ByteInputStream(delegate);
    }

    private static final class ByteInputStream implements ByteInput {

        private final InputStream delegate;

        private ByteInputStream(final InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(final byte[] b) throws IOException {
            return delegate.read(b);
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public long skip(final long n) throws IOException {
            return delegate.skip(n);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

}
