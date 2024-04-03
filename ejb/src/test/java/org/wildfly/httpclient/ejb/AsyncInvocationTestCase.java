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

package org.wildfly.httpclient.ejb;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.undertow.server.handlers.CookieImpl;
import jakarta.ejb.ApplicationException;

import org.jboss.ejb.client.EJBClient;
import org.jboss.ejb.client.StatelessEJBLocator;
import org.jboss.ejb.client.URIAffinity;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import io.undertow.util.Headers;
import org.wildfly.httpclient.common.HTTPTestServer;

/**
 * @author Stuart Douglas
 */
@RunWith(EJBTestServer.class)
public class AsyncInvocationTestCase {

    public static final String APP = "wildfly-app";
    public static final String MODULE = "wildfly-ejb-remote-server-side";

    @Before
    public void before() {
        // when in interop mode, the first invocation will always be /v1
        HTTPTestServer.registerServicesHandler("/common/v1/affinity", exchange -> exchange.setResponseCookie(new CookieImpl("JSESSIONID", "foo")));
        HTTPTestServer.registerServicesHandler("/common/v2/affinity", exchange -> exchange.setResponseCookie(new CookieImpl("JSESSIONID", "foo")));
    }

    @Test
    public void testSimpleAsyncInvocation() throws Exception {
        EJBTestServer.setHandler((invocation, affinity, out, method, handle, attachments) -> {
            if (method.getMethodName().equals("asyncMessage")) {
                return "a message";
            }
            return null;
        });
        final StatelessEJBLocator<EchoRemote> statelessEJBLocator = new StatelessEJBLocator<>(EchoRemote.class, APP, MODULE, "CalculatorBean", "", URIAffinity.forUri(new URI(EJBTestServer.getDefaultServerURL())));
        final EchoRemote proxy = EJBClient.createProxy(statelessEJBLocator);
        final Future<String> echo = proxy.asyncMessage();
        Assert.assertEquals("Unexpected echo message", "a message", echo.get());
    }

    @Test
    public void testSimpleAsyncException() throws Exception {
        EJBTestServer.setHandler((invocation, affinity, out, method, handle, attachments) -> {
            if (method.getMethodName().equals("asyncMessage")) {
                return "a message";
            } else if (method.getMethodName().equals("asyncException")) {
                throw new TestException("exception");
            }
            return null;
        });
        final StatelessEJBLocator<EchoRemote> statelessEJBLocator = new StatelessEJBLocator<>(EchoRemote.class, APP, MODULE, "CalculatorBean", "", URIAffinity.forUri(new URI(EJBTestServer.getDefaultServerURL())));
        final EchoRemote proxy = EJBClient.createProxy(statelessEJBLocator);
        final Future<String> echo = proxy.asyncException();
        try {
            echo.get();
            Assert.fail();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof TestException);
            Assert.assertEquals("exception", cause.getMessage());
        }
    }

    @Test
    public void testSimpleAsyncCancellation() throws Exception {
        final CompletableFuture<Boolean> resultFuture = new CompletableFuture<>();
        EJBTestServer.setHandler((invocation, affinity, out, method, handle, attachments) -> {
            if (method.getMethodName().equals("asyncMessage")) {
                return "a message";
            } else if (method.getMethodName().equals("asyncException")) {
                throw new TestException("exception");
            } else if (method.getMethodName().equals("asyncCancel")) {
                resultFuture.complete(handle.awaitResult());
            }
            return null;
        });
        final StatelessEJBLocator<EchoRemote> statelessEJBLocator = new StatelessEJBLocator<>(EchoRemote.class, APP, MODULE, "CalculatorBean", "", URIAffinity.forUri(new URI(EJBTestServer.getDefaultServerURL())));
        final EchoRemote proxy = EJBClient.createProxy(statelessEJBLocator);
        final Future<String> echo = proxy.asyncCancel();
        //TODO: the cancellation can be a bit racey
        Thread.sleep(1000);
        echo.cancel(true);
        Assert.assertEquals(true, resultFuture.get(10, TimeUnit.SECONDS));
    }

    @ApplicationException
    private static class TestException extends Exception {
        public TestException(String message) {
            super(message);
        }
    }
}
