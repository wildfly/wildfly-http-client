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

package org.wildfly.httpclient.transaction;

import java.net.URI;

import javax.transaction.xa.Xid;

import io.undertow.client.ClientRequest;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.httpclient.common.HTTPTestServer;
import org.wildfly.httpclient.common.HttpStickinessHelper;
import org.wildfly.httpclient.common.HttpTargetContext;
import org.wildfly.httpclient.common.WildflyHttpContext;
import org.wildfly.httpclient.transaction.HttpSubordinateTransactionHandle.SubordinateTransactionStickinessHandler;
import org.wildfly.transaction.client.SimpleXid;

/**
 * Verifies that the XA subordinate completion path emits per-transaction routing headers. Because a
 * single {@link HttpTargetContext} is shared by every transaction targeting the same load balancer,
 * each transaction's {@link SubordinateTransactionStickinessHandler} must read back and emit exactly
 * its own pinned route/session and never leak a sibling transaction's route.
 *
 * @author Tomasz Adamski
 */
@RunWith(HTTPTestServer.class)
public class SubordinateTransactionStickinessTestCase {

    private static final HttpString STRICT_STICKINESS_HOST = new HttpString("StrictStickinessHost");

    private static Xid xid(int global) {
        byte[] g = new byte[] {(byte) global};
        byte[] b = new byte[] {(byte) (global + 100)};
        return new SimpleXid(1, g, b);
    }

    private static String cookie(ClientRequest request) {
        return request.getRequestHeaders().getFirst(Headers.COOKIE);
    }

    private static String stickinessHost(ClientRequest request) {
        return request.getRequestHeaders().getFirst(STRICT_STICKINESS_HOST);
    }

    @Test
    public void testHandlerEmitsPinnedRoutePerTransaction() throws Exception {
        final HttpTargetContext targetContext =
                WildflyHttpContext.getCurrent().getTargetContext(new URI(HTTPTestServer.getDefaultServerURL()));

        final Xid xidA = xid(1);
        final Xid xidB = xid(2);

        // Simulate what the EJB invocation path pins on first response, keyed per transaction.
        targetContext.setTransactionStickiness(HttpStickinessHelper.stickinessKey(xidA.getFormatId(), xidA.getGlobalTransactionId()), "pod-a", "session-a");
        targetContext.setTransactionStickiness(HttpStickinessHelper.stickinessKey(xidB.getFormatId(), xidB.getGlobalTransactionId()), "pod-b", "session-b");

        final ClientRequest requestA = new ClientRequest();
        new SubordinateTransactionStickinessHandler(targetContext, xidA).prepareRequest(requestA);

        final ClientRequest requestB = new ClientRequest();
        new SubordinateTransactionStickinessHandler(targetContext, xidB).prepareRequest(requestB);

        // Each completion request must carry its own transaction's route, not the sibling's.
        Assert.assertEquals("pod-a", stickinessHost(requestA));
        Assert.assertEquals("JSESSIONID=session-a.pod-a", cookie(requestA));

        Assert.assertEquals("pod-b", stickinessHost(requestB));
        Assert.assertEquals("JSESSIONID=session-b.pod-b", cookie(requestB));
    }

    @Test
    public void testHandlerEmitsNothingForUnpinnedTransaction() throws Exception {
        final HttpTargetContext targetContext =
                WildflyHttpContext.getCurrent().getTargetContext(new URI(HTTPTestServer.getDefaultServerURL()));

        final Xid unpinned = xid(42);

        final ClientRequest request = new ClientRequest();
        new SubordinateTransactionStickinessHandler(targetContext, unpinned).prepareRequest(request);

        Assert.assertNull("no route should be emitted when the transaction was never pinned", stickinessHost(request));
        Assert.assertNull("no JSESSIONID should be emitted when the transaction was never pinned", cookie(request));
    }

    @Test
    public void testNoArgHandlerIsNoOp() {
        // The recover() path uses the no-arg handler (no target context, no xid) and must not route.
        final ClientRequest request = new ClientRequest();
        new SubordinateTransactionStickinessHandler().prepareRequest(request);

        Assert.assertNull(stickinessHost(request));
        Assert.assertNull(cookie(request));
    }
}
