/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
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

import io.undertow.server.handlers.CookieImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.httpclient.common.HTTPTestServer;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.Hashtable;

@RunWith(HTTPTestServer.class)
public class ReadOnlyNamingOperationTestCase {

    @Before
    public void setup() {
        HTTPTestServer.registerServicesHandler("/common/v1/affinity", exchange -> exchange.getResponseCookies().put("JSESSIONID", new CookieImpl("JSESSIONID", "foo")));
        HTTPTestServer.registerServicesHandler("/naming", new HttpRemoteNamingService(new LocalContext(true), f -> false).createHandler());
    }

    private InitialContext createContext() throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "org.wildfly.naming.client.WildFlyInitialContextFactory");
        env.put(Context.PROVIDER_URL, HTTPTestServer.getDefaultServerURL());
        return new InitialContext(env);
    }

    @Test
    public void testReadOnlyBind() throws Exception {
        InitialContext ic = createContext();
        try {
            ic.bind("name", "value");
            Assert.fail("should fail");
        } catch (Exception e) {
            Assert.assertTrue(e instanceof NamingException);
            Assert.assertEquals("bind is read-only", e.getMessage());
        } finally {
            ic.close();
        }
    }

    @Test
    public void testReadOnlyReBind() throws Exception {
        HttpNamingClientMessages.MESSAGES.info("========= testReadOnlyRebind: start =========");
        InitialContext ic = createContext();
        try {
            ic.rebind("name", "value");
            Assert.fail("should fail");
        } catch (Exception e) {
            HttpNamingClientMessages.MESSAGES.info("result: got exception: " + e);
            Assert.assertTrue(e instanceof NamingException);
            Assert.assertEquals("rebind is read-only", e.getMessage());
        } finally {
            ic.close();
            HttpNamingClientMessages.MESSAGES.info("========= testReadOnlyRebind: stop =========");
        }
    }

    @Test
    public void testReadOnlyReName() throws Exception {
        InitialContext ic = createContext();
        try {
            ic.rename("oldName", "newName");
            Assert.fail("should fail");
        } catch (Exception e) {
            Assert.assertTrue(e instanceof NamingException);
            Assert.assertEquals("rename is read-only", e.getMessage());
        } finally {
            ic.close();
        }
    }

    @Test
    public void testReadOnlyCreateSubContext() throws Exception {
        InitialContext ic = createContext();
        try {
            ic.createSubcontext("subContext");
            Assert.fail("should fail");
        } catch (Exception e) {
            Assert.assertTrue(e instanceof NamingException);
            Assert.assertEquals("createSubcontext is read-only", e.getMessage());
        } finally {
            ic.close();
        }
    }

    @Test
    public void testReadOnlyUnBind() throws Exception {
        InitialContext ic = createContext();
        try {
            ic.unbind("name");
            Assert.fail("should fail");
        } catch (Exception e) {
            Assert.assertTrue(e instanceof NamingException);
            Assert.assertEquals("unbind is read-only", e.getMessage());
        } finally {
            ic.close();
        }
    }

    @Test
    public void testReadOnlyDestroySubContext() throws Exception {
        InitialContext ic = createContext();
        try {
            ic.destroySubcontext("subContext");
            Assert.fail("should fail");
        } catch (Exception e) {
            Assert.assertTrue(e instanceof NamingException);
            Assert.assertEquals("destroySubcontext is read-only", e.getMessage());
        } finally {
            ic.close();
        }
    }

}
