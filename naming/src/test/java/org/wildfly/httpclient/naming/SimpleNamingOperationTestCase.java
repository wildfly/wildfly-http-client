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

package org.wildfly.httpclient.naming;

import java.util.Hashtable;
import java.util.function.Function;
import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameClassPair;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.httpclient.common.HTTPTestServer;
import io.undertow.server.handlers.CookieImpl;

/**
 * @author Stuart Douglas
 */
@RunWith(HTTPTestServer.class)
public class SimpleNamingOperationTestCase {

    /*
     * Reject unmarshalling an instance of IAE, as a kind of 'blocklist'.
     * In normal tests this type would never be sent, which is analogous to
     * how blocklisted classes are normally not sent. And then we can
     * deliberately send an IAE in tests to confirm it is rejected.
     */
    private static final Function<String, Boolean> DEFAULT_CLASS_FILTER = cName -> !cName.equals(IllegalArgumentException.class.getName());

    @Before
    public void setup() {
        HTTPTestServer.registerServicesHandler("common/v1/affinity", exchange -> exchange.getResponseCookies().put("JSESSIONID", new CookieImpl("JSESSIONID", "foo")));
        HTTPTestServer.registerServicesHandler("naming", new HttpRemoteNamingService(new LocalContext(false), DEFAULT_CLASS_FILTER).createHandler());
    }

    @Test //@Ignore // FIXME WEJBHTTP-37
    public void testJNDIlookup() throws NamingException {
        HttpNamingClientMessages.MESSAGES.info("========= testJNDILookup: start =========");
        InitialContext ic = createContext();
        Object result = ic.lookup("test");
        Assert.assertEquals("test value", result);
        result = ic.lookup("comp/UserTransaction");
        Assert.assertEquals("transaction", result);
        try {
            ic.lookup("missing");
            Assert.fail();
        } catch (NameNotFoundException expected) {
        }
        HttpNamingClientMessages.MESSAGES.info("========= testJNDILookup: stop =========");
    }

    @Test //@Ignore // FIXME WEJBHTTP-37
    public void testJNDIlookupTimeoutTestCase() throws NamingException, InterruptedException {
        HttpNamingClientMessages.MESSAGES.info("========= testJNDILookupTimeoutTestCase: start =========");
        InitialContext ic = createContext();
        Object result = ic.lookup("test");
        Assert.assertEquals("test value", result);
        result = ic.lookup("comp/UserTransaction");
        Assert.assertEquals("transaction", result);
        Thread.sleep(1500);
        result = ic.lookup("comp/UserTransaction");
        Assert.assertEquals("transaction", result);
        HttpNamingClientMessages.MESSAGES.info("========= testJNDILookupTimeoutTestCase: stop =========");
    }

    @Test
    public void testJNDIBindings() throws NamingException {
        HttpNamingClientMessages.MESSAGES.info("========= testJNDIBindings: start =========");
        InitialContext ic = createContext();
        try {
            ic.lookup("bound");
            Assert.fail();
        } catch (NameNotFoundException e) {
        }
        ic.bind("bound", "test binding");
        Assert.assertEquals("test binding", ic.lookup("bound"));
        ic.rebind("bound", "test binding 2");
        Assert.assertEquals("test binding 2", ic.lookup("bound"));

//        ic.rename("bound", "bound2");
//        try {
//            ic.lookup("bound");
//            Assert.fail();
//        } catch (NameNotFoundException e) {}
//        Assert.assertEquals("test binding 2", ic.lookup("bound2"));
        HttpNamingClientMessages.MESSAGES.info("========= testJNDIBindings: stop =========");

    }

    @Test
    public void testUnmarshallingFilter() throws NamingException {
        HttpNamingClientMessages.MESSAGES.info("========= testUnmarshallingFilter: start =========");
        InitialContext ic = createContext();
        try {
            ic.lookup("unmarshal");
            Assert.fail();
        } catch (NameNotFoundException e) {
        }
        try {
            ic.bind("unmarshal", new IllegalArgumentException());
            Assert.fail("Should not be able to bind an IAE");
        } catch (NamingException good) {
            // good
        }
        ic.bind("unmarshal", new IllegalStateException());
        Assert.assertEquals(IllegalStateException.class, ic.lookup("unmarshal").getClass());
        try {
            ic.rebind("unmarshal", new IllegalArgumentException());
            Assert.fail("Should not be able to rebind an IAE");
        } catch (NamingException good) {
            // good
        }
        ic.rebind("unmarshal", new IllegalStateException());
        Assert.assertEquals(IllegalStateException.class, ic.lookup("unmarshal").getClass());
        HttpNamingClientMessages.MESSAGES.info("========= testUnmarshallingFilter: start =========");
    }

    private InitialContext createContext() throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "org.wildfly.naming.client.WildFlyInitialContextFactory");
        env.put(Context.PROVIDER_URL, HTTPTestServer.getDefaultServerURL());
        return new InitialContext(env);
    }

    @Test
    public void testSimpleUnbind() throws Exception {
        HttpNamingClientMessages.MESSAGES.info("========= testSimpleUnbind: start =========");
        InitialContext ic = createContext();
        Assert.assertEquals("test value", ic.lookup("test").toString());
        ic.unbind("test");
        try {
            ic.lookup("test");
            Assert.fail("test is not available anymore");
        } catch (NameNotFoundException e) {
        }
        HttpNamingClientMessages.MESSAGES.info("========= testSimpleUnbind: stop =========");
    }

    @Test
    public void testSimpleSubContext() throws Exception {
        HttpNamingClientMessages.MESSAGES.info("========= testSimpleSubContext: start =========");
        InitialContext ic = createContext();
        ic.createSubcontext("subContext");
        Context subContext = (Context)ic.lookup("subContext");
        Assert.assertNotNull(subContext);
        ic.destroySubcontext("subContext");
        try {
            ic.lookup("subContext");
            Assert.fail("subContext is not available anymore");
        } catch (NameNotFoundException e) {
        }
        HttpNamingClientMessages.MESSAGES.info("========= testSimpleSubContext: stop =========");
    }

    @Test
    public void testSimpleRename() throws Exception {
        HttpNamingClientMessages.MESSAGES.info("========= testSimpleRename: start =========");
        InitialContext ic = createContext();
        HttpNamingClientMessages.MESSAGES.info("=== lookup:");
        Assert.assertEquals("test value", ic.lookup("test").toString());
        delay(10) ;
        HttpNamingClientMessages.MESSAGES.info("=== rename:");
        ic.rename("test", "testB");
        HttpNamingClientMessages.MESSAGES.info("=== relookup:");
        delay(10) ;
        try {
            ic.lookup("test");
            Assert.fail("test is not available anymore");
        } catch (NameNotFoundException e) {
            HttpNamingClientMessages.MESSAGES.info("=== result: got exception: " + e);
        }
        delay(10) ;
        Assert.assertEquals("test value", ic.lookup("testB").toString());
        HttpNamingClientMessages.MESSAGES.info("=== testSimpleRename: stop ");
    }

    @Test   // WEJBHTTP-69
    public void testListCanBeSerialized() throws Exception {
        HttpNamingClientMessages.MESSAGES.info("========= testListCanBeSerialized: start =========");
        InitialContext ic = createContext();
        NamingEnumeration<NameClassPair> list = ic.list("test");
        Assert.assertNotNull(list);
        HttpNamingClientMessages.MESSAGES.info("========= testListCanBeSerialized: stop =========");
    }

    @Test   // WEJBHTTP-69
    public void testListBindingsCanBeSerialized() throws Exception {
        HttpNamingClientMessages.MESSAGES.info("========= testBindingsCanBeSerialized: start =========");
        InitialContext ic = createContext();
        NamingEnumeration<Binding> list = ic.listBindings("test");
        Assert.assertNotNull(list);
        HttpNamingClientMessages.MESSAGES.info("========= testBindingsCanBeSerialized: stop =========");
    }

    @Test
    public void testHttpNamingEjbObjectResolverHelper() throws NamingException {
        HttpNamingClientMessages.MESSAGES.info("========= testHttpNamingEjbObjectResolverHelper: start =========");
        InitialContext ic = createContext();
        Assert.assertEquals(TestHttpNamingEjbObjectResolverHelper.create("readResolve->" + HTTPTestServer.getDefaultServerURL()),
                ic.lookup("test-resolver-helper"));

        ic.rebind("test-resolver-helper", TestHttpNamingEjbObjectResolverHelper.create("test"));
        Assert.assertEquals(TestHttpNamingEjbObjectResolverHelper.create("writeReplace->" + HTTPTestServer.getDefaultServerURL()),
                ic.lookup("test-resolver-helper"));
        HttpNamingClientMessages.MESSAGES.info("========= testHttpNamingEjbObjectResolverHelper: stop =========");
    }

    private void delay(int ms) {
        try {
            Thread.sleep(ms);
        } catch(java.lang.InterruptedException e) {
            // noop
        }
    }
}
