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

import static org.xnio.Bits.allAreClear;
import static org.xnio.Bits.anyAreSet;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

import org.wildfly.common.Assert;
import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.channels.StreamSourceChannel;

import io.undertow.connector.ByteBufferPool;
import io.undertow.connector.PooledByteBuffer;

class WildflyClientInputStream extends InputStream {
    private final Object lock = new Object();
    private final ByteBufferPool bufferPool;
    private final StreamSourceChannel channel;
    private final Closeable doneCallback;

    private PooledByteBuffer pooledByteBuffer;
    private IOException ioException;
    private int state;
    private static final int FLAG_CLOSED = 1;
    private static final int FLAG_MINUS_ONE_READ = 1 << 2;

    private final ChannelListener<StreamSourceChannel> channelListener = new ChannelListener<StreamSourceChannel>() {
        @Override
        public void handleEvent(StreamSourceChannel streamSourceChannel) {
            synchronized (lock) {
                if (pooledByteBuffer != null) {
                    return;
                }
                boolean free = true;
                final PooledByteBuffer pooled = bufferPool.allocate();
                try {
                    for (; ; ) {
                        int res = streamSourceChannel.read(pooled.getBuffer());
                        if (res == 0) {
                            pooled.getBuffer().flip();
                            if (pooled.getBuffer().hasRemaining()) {
                                pooledByteBuffer = pooled;
                                free = false;
                                streamSourceChannel.suspendReads();
                            }
                            lock.notifyAll();
                            return;
                        }
                        if (res == -1) {
                            pooled.getBuffer().flip();
                            if (pooled.getBuffer().hasRemaining()) {
                                pooledByteBuffer = pooled;
                                free = false;
                                streamSourceChannel.suspendReads();
                            }
                            state |= FLAG_MINUS_ONE_READ;
                            lock.notifyAll();
                            return;
                        } else if (!pooled.getBuffer().hasRemaining()) {
                            pooled.getBuffer().flip();
                            pooledByteBuffer = pooled;
                            free = false;
                            lock.notifyAll();
                            streamSourceChannel.suspendReads();
                            return;
                        }
                    }
                } catch (IOException e) {
                    ioException = e;
                    if (pooledByteBuffer != null) {
                        pooledByteBuffer.close();
                        pooledByteBuffer = null;
                    }
                    lock.notifyAll();
                } finally {
                    if (free) {
                        pooled.close();
                    }
                }
            }
        }
    };


    WildflyClientInputStream(ByteBufferPool bufferPool, StreamSourceChannel channel, Closeable doneCallback) {
        this.bufferPool = bufferPool;
        this.channel = channel;
        this.doneCallback = doneCallback;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int res = read(b);
        if (res == 1) {
            return b[0] & 0xFF;
        }
        return res;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        synchronized (lock) {
            if (len < 1) {
                return 0;
            }
            if (Thread.currentThread() == channel.getIoThread()) {
                throw HttpClientMessages.MESSAGES.blockingIoFromIOThread();
            }
            if (anyAreSet(state, FLAG_CLOSED) && !anyAreSet(state, FLAG_MINUS_ONE_READ)) {
                throw HttpClientMessages.MESSAGES.streamIsClosed();
            }
            if (ioException != null) {
                throw new IOException(ioException);
            }
            while (pooledByteBuffer == null) {
                if (anyAreSet(state, FLAG_MINUS_ONE_READ)) {
                    state |= FLAG_CLOSED;
                    return -1;
                }
                runReadTask();
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException(e.getMessage());
                }
                if (ioException != null) {
                    throw new IOException(ioException);
                }
            }
            int toRead = Math.min(pooledByteBuffer.getBuffer().remaining(), len);
            pooledByteBuffer.getBuffer().get(b, off, toRead);
            if (!pooledByteBuffer.getBuffer().hasRemaining()) {
                pooledByteBuffer.close();
                pooledByteBuffer = null;
            }
            return toRead;
        }

    }

    private void runReadTask() {
        Assert.assertTrue(pooledByteBuffer == null);
        channel.getReadSetter().set(channelListener);
        channel.resumeReads();
    }

    @Override
    public int available() throws IOException {
        synchronized (lock) {
            if (pooledByteBuffer != null) {
                return pooledByteBuffer.getBuffer().remaining();
            }
        }
        return 0;
    }

    @Override
    public void close() throws IOException {
        if (anyAreSet(state, FLAG_CLOSED)) {
            return;
        }
        try {
            synchronized (lock) {
                IoUtils.safeClose(pooledByteBuffer);
                pooledByteBuffer = null;
                while (allAreClear(state, FLAG_MINUS_ONE_READ) && ioException == null) {
                    runReadTask();
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        IoUtils.safeClose(pooledByteBuffer, channel);
                        throw new InterruptedIOException(e.getMessage());
                    }
                    IoUtils.safeClose(pooledByteBuffer);
                    pooledByteBuffer = null;
                }
                if (ioException != null) {
                    throw new IOException(ioException);
                }
            }
        } finally {
            IoUtils.safeClose(doneCallback);
        }
    }
}
