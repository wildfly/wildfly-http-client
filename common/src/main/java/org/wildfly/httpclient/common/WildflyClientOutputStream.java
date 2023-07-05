/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.wildfly.httpclient.common;

import static org.xnio.Bits.allAreClear;
import static org.xnio.Bits.anyAreClear;
import static org.xnio.Bits.anyAreSet;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.jboss.marshalling.ByteOutput;
import org.wildfly.common.Assert;
import org.xnio.ChannelListener;
import org.xnio.channels.StreamSinkChannel;

import io.undertow.connector.ByteBufferPool;
import io.undertow.connector.PooledByteBuffer;

/**
 * Buffering output stream that wraps a channel.
 * <p>
 * This stream delays channel creation, so if a response will fit in the buffer it is not necessary to
 * set the content length header.
 *
 * @author Stuart Douglas
 */
class WildflyClientOutputStream extends OutputStream implements ByteOutput {

    private final Object lock = new Object();

    private PooledByteBuffer pooledBuffer;
    private IOException ioException;
    private final StreamSinkChannel channel;
    private final ByteBufferPool bufferPool;
    private int state;

    private static final int FLAG_CLOSED = 1;
    private static final int FLAG_WRITING = 1 << 1;
    private static final int FLAG_DONE = 1 << 2;

    private final ChannelListener<StreamSinkChannel> channelListener = new ChannelListener<StreamSinkChannel>() {
        @Override
        public void handleEvent(StreamSinkChannel streamSinkChannel) {
            synchronized (lock) {
                if(anyAreClear(state, FLAG_WRITING)) {
                    return;
                }
                try {
                    boolean closed = anyAreSet(state, FLAG_CLOSED);
                    if (pooledBuffer != null) {
                        pooledBuffer.getBuffer().flip();
                    }
                    if (closed && (pooledBuffer == null || !pooledBuffer.getBuffer().hasRemaining())) {
                        if (pooledBuffer != null) {
                            pooledBuffer.close();
                            pooledBuffer = null;
                        }
                        //if we are just flushing the data
                        if (streamSinkChannel.flush()) {
                            state |= FLAG_DONE;
                            state &= ~FLAG_WRITING;
                            lock.notifyAll();
                            streamSinkChannel.shutdownWrites();
                        }
                    } else {
                        while (pooledBuffer.getBuffer().hasRemaining()) {
                            int res;
                            if (closed) {
                                res = streamSinkChannel.writeFinal(pooledBuffer.getBuffer());
                            } else {
                                res = streamSinkChannel.write(pooledBuffer.getBuffer());
                            }
                            if (res == 0) {
                                pooledBuffer.getBuffer().compact(); // WEJBHTTP-50 compact the buffer to be able to flip it when listener is invoked again
                                return;
                            }
                        }
                        lock.notifyAll();
                        streamSinkChannel.suspendWrites();
                        state &= ~FLAG_WRITING;
                        pooledBuffer.close();
                        pooledBuffer = null;
                        if (closed) {
                            if (streamSinkChannel.flush()) {
                                state |= FLAG_DONE;
                            }
                        }
                    }
                } catch (IOException e) {
                    if (pooledBuffer != null) {
                        pooledBuffer.close();
                        pooledBuffer = null;
                    }
                    state &= ~FLAG_WRITING;
                    ioException = e;
                    lock.notifyAll();
                }
            }
        }
    };

    WildflyClientOutputStream(StreamSinkChannel channel, ByteBufferPool byteBufferPool) {
        this.channel = channel;
        this.bufferPool = byteBufferPool;
    }

    /**
     * {@inheritDoc}
     */
    public void write(final int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    /**
     * {@inheritDoc}
     */
    public void write(final byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    /**
     * {@inheritDoc}
     */
    public void write(final byte[] b, final int off, final int len) throws IOException {
        if (len < 1) {
            return;
        }
        if (Thread.currentThread() == channel.getIoThread()) {
            throw HttpClientMessages.MESSAGES.blockingIoFromIOThread();
        }
        if (anyAreSet(state, FLAG_CLOSED)) {
            throw HttpClientMessages.MESSAGES.streamIsClosed();
        }
        int currentOff = off;
        int currentLen = len;
        synchronized (lock) {
            for (; ; ) {
                while (anyAreSet(state, FLAG_WRITING) && ioException == null) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException(e.getMessage());
                    }
                }
                if (ioException != null) {
                    throw new IOException(ioException);
                }

                ByteBuffer buffer = buffer();
                if (buffer.remaining() < currentLen) {
                    int put = buffer.remaining();
                    buffer.put(b, currentOff, buffer.remaining());
                    currentOff += put;
                    currentLen -= put;
                    runWriteTask();
                } else {
                    buffer.put(b, currentOff, currentLen);
                    if (buffer.remaining() == 0) {
                        runWriteTask();
                    }
                    return;
                }
            }


        }
    }

    private void runWriteTask() {
        new Exception(Thread.currentThread() + ": at run write task, look who is invoking me...").printStackTrace();
        Assert.assertHoldsLock(lock);
        state |= FLAG_WRITING;
        channel.getWriteSetter().set(channelListener);
        channel.wakeupWrites();
    }

    /**
     * {@inheritDoc}
     */
    public void flush() throws IOException {
        if (anyAreSet(state, FLAG_CLOSED)) {
            throw HttpClientMessages.MESSAGES.streamIsClosed();
        }
    }

    /**
     * {@inheritDoc}
     */
    public void close() throws IOException {
        synchronized (lock) {
            if (ioException != null) {
                throw new IOException(ioException);
            }
            if (anyAreSet(state, FLAG_CLOSED)) return;
            state |= FLAG_CLOSED;
            runWriteTask();
            while (allAreClear(state, FLAG_DONE) && ioException != null) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException(e.getMessage());
                }
            }
            if (ioException != null) {
                throw new IOException(ioException);
            }
        }
    }

    private ByteBuffer buffer() {
        PooledByteBuffer buffer = this.pooledBuffer;
        if (buffer != null) {
            return buffer.getBuffer();
        }
        this.pooledBuffer = bufferPool.allocate();
        return pooledBuffer.getBuffer();
    }

}
