/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022 Red Hat, Inc., and individual contributors
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

import io.undertow.client.ClientRequest;
import java.net.URI;
import java.net.URISyntaxException;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author rmartinc
 */
public class PoolAuthenticationContextTestCase {

    @Test
    public void testCreateTargetUriWithParams() throws URISyntaxException {
        ClientRequest req = new ClientRequest();
        req.setPath("/te%2Bst/test.html?param1=value1&param2=val%20ue2");

        Assert.assertEquals("http://localhost:8080/te%2Bst/test.html?param1=value1&param2=val%20ue2",
                PoolAuthenticationContext.createTargetUri(new URI("http://localhost:8080"), req));
        Assert.assertEquals("http://localhost/te%2Bst/test.html?param1=value1&param2=val%20ue2",
                PoolAuthenticationContext.createTargetUri(new URI("http://localhost"), req));
        Assert.assertEquals("http://localhost/te%2Bst/test.html?param1=value1&param2=val%20ue2",
                PoolAuthenticationContext.createTargetUri(new URI("http://localhost:80"), req));
        Assert.assertEquals("https://localhost/te%2Bst/test.html?param1=value1&param2=val%20ue2",
                PoolAuthenticationContext.createTargetUri(new URI("https://localhost"), req));
        Assert.assertEquals("https://localhost/te%2Bst/test.html?param1=value1&param2=val%20ue2",
                PoolAuthenticationContext.createTargetUri(new URI("https://localhost:443"), req));
    }

    @Test
    public void testCreateTargetUriWithoutParams() throws URISyntaxException {
        ClientRequest req = new ClientRequest();
        req.setPath("/te%2Bst/test.html");

        Assert.assertEquals("http://localhost:8080/te%2Bst/test.html",
                PoolAuthenticationContext.createTargetUri(new URI("http://localhost:8080"), req));
        Assert.assertEquals("http://localhost/te%2Bst/test.html",
                PoolAuthenticationContext.createTargetUri(new URI("http://localhost"), req));
        Assert.assertEquals("http://localhost/te%2Bst/test.html",
                PoolAuthenticationContext.createTargetUri(new URI("http://localhost:80"), req));
        Assert.assertEquals("https://localhost/te%2Bst/test.html",
                PoolAuthenticationContext.createTargetUri(new URI("https://localhost"), req));
        Assert.assertEquals("https://localhost/te%2Bst/test.html",
                PoolAuthenticationContext.createTargetUri(new URI("https://localhost:443"), req));
    }

    @Test
    public void testCreateTargetUriIPv6() throws URISyntaxException {
        ClientRequest req = new ClientRequest();
        req.setPath("/te%2Bst/test.html");

        Assert.assertEquals("http://[::1]/te%2Bst/test.html",
                PoolAuthenticationContext.createTargetUri(new URI("http", "::1", null, null), req));
    }

}
