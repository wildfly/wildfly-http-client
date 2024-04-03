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


import java.net.URI;
import java.net.URISyntaxException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.security.auth.client.AuthenticationContext;
import io.undertow.server.handlers.CookieImpl;

/**
 * @author Stuart Douglas
 */
@RunWith(HTTPTestServer.class)
public class AcquireAffinityTestCase {

    @Test
    public void testAcquireAffinity() throws URISyntaxException {
        // when in interop mode, the first invocation will always be /v1
        HTTPTestServer.registerServicesHandler("/common/v1/affinity", exchange -> exchange.setResponseCookie(new CookieImpl("JSESSIONID", "foo")));
        HTTPTestServer.registerServicesHandler("/common/v2/affinity", exchange -> exchange.setResponseCookie(new CookieImpl("JSESSIONID", "foo")));

        AuthenticationContext cc = AuthenticationContext.captureCurrent();
        HttpTargetContext context = WildflyHttpContext.getCurrent().getTargetContext(new URI(HTTPTestServer.getDefaultServerURL()));
        context.clearSessionId();
        Assert.assertEquals("foo", context.awaitSessionId(true, null));

    }
}
