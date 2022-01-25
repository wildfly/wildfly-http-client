package org.wildfly.httpclient.common;

import java.io.BufferedInputStream;
import java.io.IOException;

import org.xnio.channels.StreamSourceChannel;
import org.xnio.streams.ChannelInputStream;

import io.undertow.connector.ByteBufferPool;

/**
 * Replaces buggy WildflyClientInputStream from the wildfly-http-client-common
 * dependency. Original implementation causes SSL read loop errors.
 *
 * We replace it with org.xnio.streams.ChannelInputStream and
 * java.io.BufferedInputStream
 *
 */
public class WildflyClientInputStream extends BufferedInputStream {

    // we only use the bufferPool to get the expected size of our buffer
    public WildflyClientInputStream(ByteBufferPool bufferPool, StreamSourceChannel channel) {
        super(new ChannelInputStream(channel), bufferPool.getBufferSize());
    }

    @Override
    public void close() throws IOException {
        /*
         * In case the stream was empty and not read before, we need to do some magic
         * here otherwise the Wildfly http client gets stuck after the call.
         */
        read();
        super.close();
    }
}
