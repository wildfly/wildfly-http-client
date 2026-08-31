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

import io.undertow.client.ClientResponse;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import io.undertow.util.Protocols;
import org.junit.Assert;
import org.junit.Test;

/**
 * Locks the client-side "strict stickiness failed means fatal" contract.
 * <p>
 * When a strict-stickiness request lands on a node other than the one it was pinned to, the server
 * signals this with an HTTP 200 response carrying a {@code StrictStickinessResult: failed} header
 * (see the server-side rejection re-added in the EJB {@code ServerHandlers}). The client must treat
 * that as a hard failure - {@link HttpStickinessHelper#getStrictStickinessResult(ClientResponse)}
 * throws - so a mis-routed invocation surfaces as an error instead of being silently accepted. This
 * is the primitive that makes a transaction-scoped invocation that failed over fatal rather than
 * quietly rerouted; {@code HttpEJBReceiver} calls it on both the session-creation and invocation
 * response paths.
 *
 * @author Tomasz Adamski
 */
public class StrictStickinessResultTestCase {

    private static final HttpString STRICT_STICKINESS_RESULT = new HttpString("StrictStickinessResult");
    private static final HttpString STRICT_STICKINESS_HOST = new HttpString("StrictStickinessHost");

    private static ClientResponse responseWith(HttpString header, String value) {
        HeaderMap headers = new HeaderMap();
        if (value != null) {
            headers.put(header, value);
        }
        return new ClientResponse(200, "OK", Protocols.HTTP_1_1, headers);
    }

    private static ClientResponse responseWithNoHeaders() {
        return new ClientResponse(200, "OK", Protocols.HTTP_1_1, new HeaderMap());
    }

    @Test
    public void testSuccessResultIsSticky() throws Exception {
        ClientResponse response = responseWith(STRICT_STICKINESS_RESULT, "success");

        Assert.assertTrue(HttpStickinessHelper.hasStrictStickinessResult(response));
        Assert.assertTrue("a success result must report sticky", HttpStickinessHelper.getStrictStickinessResult(response));
    }

    /**
     * The crux: a failover the client asked not to happen must not be swallowed. A transactional
     * invocation that lands on the wrong pod gets StrictStickinessResult=failed and the client turns
     * that into a thrown exception instead of accepting the response or rerouting.
     */
    @Test
    public void testFailedResultIsFatal() {
        ClientResponse response = responseWith(STRICT_STICKINESS_RESULT, "failed");

        try {
            HttpStickinessHelper.getStrictStickinessResult(response);
            Assert.fail("a failed strict-stickiness result must be treated as fatal (exception expected)");
        } catch (Exception expected) {
            Assert.assertTrue("exception should explain the unexpected failover, was: " + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().toLowerCase().contains("fail"));
        }
    }

    @Test
    public void testAbsentResultIsNotStickyAndNotFatal() throws Exception {
        ClientResponse response = responseWithNoHeaders();

        // No strict-stickiness result header: the request was free to be load balanced, which is not
        // an error - the client must not treat its absence as a failure.
        Assert.assertFalse(HttpStickinessHelper.hasStrictStickinessResult(response));
        Assert.assertFalse(HttpStickinessHelper.getStrictStickinessResult(response));
    }

    @Test
    public void testFailedResultEchoesIntendedHost() throws Exception {
        // On rejection the server echoes back the node the client intended to reach, so the client can
        // report exactly which pinned pod was missed.
        ClientResponse response = responseWith(STRICT_STICKINESS_HOST, "pod-a");

        Assert.assertTrue(HttpStickinessHelper.hasStrictStickinessHost(response));
        Assert.assertEquals("pod-a", HttpStickinessHelper.getStrictStickinessHost(response));
    }
}
