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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

import org.junit.Assert;
import org.junit.Test;
import org.wildfly.client.config.ConfigXMLParseException;

/**
 * @author Stuart Douglas
 */
public class WildflyClientParserTestCase {

    @Test
    public void testXMLParsing10() throws URISyntaxException, ConfigXMLParseException {
        WildflyHttpContext.Builder builder = HttpClientXmlParser.parseConfig(getClass().getClassLoader().getResource("ejb-client-1.0.xml").toURI());
        testValues10(builder);
        WildflyHttpContext.Builder.HttpConfigBuilder context = builder.getTargets().get(0);
        Assert.assertFalse(context.getTcpNoDelay());
    }

    @Test
    public void testXMLParsing11() throws URISyntaxException, ConfigXMLParseException {
        WildflyHttpContext.Builder builder = HttpClientXmlParser.parseConfig(getClass().getClassLoader().getResource("ejb-client-1.1.xml").toURI());
        testValues10(builder);

        WildflyHttpContext.Builder.HttpConfigBuilder context = builder.getTargets().get(0);
        Assert.assertTrue(context.getTcpNoDelay());
    }

    private static void testValues10(WildflyHttpContext.Builder builder) throws URISyntaxException {
        Assert.assertEquals(InetSocketAddress.createUnresolved("127.0.0.1", 3456), builder.getDefaultBindAddress());

        Assert.assertEquals(10000, builder.getIdleTimeout());
        Assert.assertEquals(1, builder.getMaxConnections());
        Assert.assertEquals(1, builder.getMaxStreamsPerConnection());
        Assert.assertEquals(false, builder.getEagerlyAcquireSession());


        Assert.assertEquals(1, builder.getTargets().size());
        WildflyHttpContext.Builder.HttpConfigBuilder context = builder.getTargets().get(0);
        Assert.assertEquals(InetSocketAddress.createUnresolved("127.0.0.1", 5678), context.getBindAddress());
        Assert.assertEquals(30000, context.getIdleTimeout());
        Assert.assertEquals(20, context.getMaxConnections());
        Assert.assertEquals(20, context.getMaxStreamsPerConnection());
        Assert.assertEquals(true, context.getEagerlyAcquireSession());

        Assert.assertEquals(new URI("http://localhost:8080"), context.getUri());
    }

}
