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
package org.wildfly.httpclient.ejb;

import org.jboss.marshalling.ByteOutput;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Helper class. Provides utility method for transforming OutputStreams to ByteOutputs.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ByteOutputs {

    private ByteOutputs() {
        // forbidden instantiation
    }

    static ByteOutput byteOutputOf(final OutputStream delegate) {
        if (delegate == null) throw new IllegalArgumentException();
        return new ByteOutputStream(delegate);
    }

    private static final class ByteOutputStream implements ByteOutput {

        private final OutputStream delegate;

        ByteOutputStream(final OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void write(final int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(final byte[] b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            delegate.write(b, off, len);
        }

    }

}
