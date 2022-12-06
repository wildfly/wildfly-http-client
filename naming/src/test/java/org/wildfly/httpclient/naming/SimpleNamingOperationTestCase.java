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

    @Test @Ignore // FIXME WEJBHTTP-37
    public void testJNDIlookup() throws NamingException {
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
    }

    @Test @Ignore // FIXME WEJBHTTP-37
    public void testJNDIlookupTimeoutTestCase() throws NamingException, InterruptedException {
        InitialContext ic = createContext();
        Object result = ic.lookup("test");
        Assert.assertEquals("test value", result);
        result = ic.lookup("comp/UserTransaction");
        Assert.assertEquals("transaction", result);
        Thread.sleep(1500);
        result = ic.lookup("comp/UserTransaction");
        Assert.assertEquals("transaction", result);
    }

    @Test
    public void testJNDIBindings() throws NamingException {
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

    }

    @Test
    public void testUnmarshallingFilter() throws NamingException {
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

    }

    private InitialContext createContext() throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "org.wildfly.naming.client.WildFlyInitialContextFactory");
        env.put(Context.PROVIDER_URL, HTTPTestServer.getDefaultServerURL());
        return new InitialContext(env);
    }

    @Test
    public void testSimpleUnbind() throws Exception {
        InitialContext ic = createContext();
        Assert.assertEquals("test value", ic.lookup("test").toString());
        ic.unbind("test");
        try {
            ic.lookup("test");
            Assert.fail("test is not available anymore");
        } catch (NameNotFoundException e) {
        }
    }

    @Test
    public void testSimpleSubContext() throws Exception {
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
    }

    @Test
    public void testSimpleRename() throws Exception {
        InitialContext ic = createContext();
        Assert.assertEquals("test value", ic.lookup("test").toString());
        ic.rename("test", "testB");
        try {
            ic.lookup("test");
            Assert.fail("test is not available anymore");
        } catch (NameNotFoundException e) {
        }
        Assert.assertEquals("test value", ic.lookup("testB").toString());
    }

    @Test   // WEJBHTTP-69
    public void testListCanBeSerialized() throws Exception {
        InitialContext ic = createContext();
        NamingEnumeration<NameClassPair> list = ic.list("test");
        Assert.assertNotNull(list);
    }

    @Test   // WEJBHTTP-69
    public void testListBindingsCanBeSerialized() throws Exception {
        InitialContext ic = createContext();
        NamingEnumeration<Binding> list = ic.listBindings("test");
        Assert.assertNotNull(list);
    }

    @Test
    public void testHttpNamingEjbObjectResolverHelper() throws NamingException {
        InitialContext ic = createContext();
        Assert.assertEquals(TestHttpNamingEjbObjectResolverHelper.create("readResolve->" + HTTPTestServer.getDefaultServerURL()),
                ic.lookup("test-resolver-helper"));

        ic.rebind("test-resolver-helper", TestHttpNamingEjbObjectResolverHelper.create("test"));
        Assert.assertEquals(TestHttpNamingEjbObjectResolverHelper.create("writeReplace->" + HTTPTestServer.getDefaultServerURL()),
                ic.lookup("test-resolver-helper"));
    }
}
