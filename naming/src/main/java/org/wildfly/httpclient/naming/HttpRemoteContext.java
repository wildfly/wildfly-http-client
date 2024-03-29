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
import javax.naming.Binding;
import javax.naming.CompositeName;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;

import org.wildfly.naming.client.util.FastHashtable;

/**
 * @author Stuart Douglas
 */
public class HttpRemoteContext implements Context {

    private final HttpRootContext rootContext;
    private final String rootName;
    private final Hashtable<Object, Object> environment;

    public HttpRemoteContext(HttpRootContext rootContext, String rootName) {
        this.rootContext = rootContext;
        this.rootName = rootName;
        try {
            this.environment = new FastHashtable<>(rootContext.getEnvironment());
        } catch (NamingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object lookup(Name name) throws NamingException {
        return rootContext.lookupNative(new CompositeName(this.rootName + "/" + name.toString()));
    }

    @Override
    public Object lookup(String s) throws NamingException {
        return rootContext.lookupNative(new CompositeName(this.rootName + "/" + s));
    }

    @Override
    public void bind(Name name, Object o) throws NamingException {
        rootContext.bindNative(new CompositeName(this.rootName + "/" + name.toString()), o);
    }

    @Override
    public void bind(String s, Object o) throws NamingException {
        rootContext.bindNative(new CompositeName(this.rootName + "/" + s), o);
    }

    @Override
    public void rebind(Name name, Object o) throws NamingException {
        rootContext.rebindNative(new CompositeName(this.rootName + "/" + name.toString()), o);
    }

    @Override
    public void rebind(String s, Object o) throws NamingException {
        rootContext.rebindNative(new CompositeName(this.rootName + "/" + s), o);
    }

    @Override
    public void unbind(Name name) throws NamingException {
        rootContext.unbindNative(new CompositeName(this.rootName + "/" + name.toString()));
    }

    @Override
    public void unbind(String s) throws NamingException {
        rootContext.unbindNative(new CompositeName(this.rootName + "/" + s));
    }

    @Override
    public void rename(Name name, Name name1) throws NamingException {
        rootContext.renameNative(new CompositeName(this.rootName + "/" + name.toString()), new CompositeName(this.rootName + "/" + name1.toString()));
    }

    @Override
    public void rename(String s, String s1) throws NamingException {
        rootContext.renameNative(new CompositeName(this.rootName + "/" + s), new CompositeName(this.rootName + "/" + s));
    }

    @Override
    public NamingEnumeration<NameClassPair> list(Name name) throws NamingException {
        return rootContext.listNative(new CompositeName(this.rootName + "/" + name.toString()));
    }

    @Override
    public NamingEnumeration<NameClassPair> list(String s) throws NamingException {
        return rootContext.listNative(new CompositeName(this.rootName + "/" + s));
    }

    @Override
    public NamingEnumeration<Binding> listBindings(Name name) throws NamingException {
        return rootContext.listBindingsNative(new CompositeName(this.rootName + "/" + name.toString()));
    }

    @Override
    public NamingEnumeration<Binding> listBindings(String s) throws NamingException {
        return rootContext.listBindingsNative(new CompositeName(this.rootName + "/" + s));
    }

    @Override
    public void destroySubcontext(Name name) throws NamingException {
        rootContext.destroySubcontextNative(new CompositeName(this.rootName + "/" + name.toString()));
    }

    @Override
    public void destroySubcontext(String s) throws NamingException {
        rootContext.destroySubcontextNative(new CompositeName(this.rootName + "/" + s));
    }

    @Override
    public Context createSubcontext(Name name) throws NamingException {
        return rootContext.createSubcontextNative(new CompositeName(this.rootName + "/" + name.toString()));
    }

    @Override
    public Context createSubcontext(String s) throws NamingException {
        return rootContext.createSubcontextNative(new CompositeName(this.rootName + "/" + s));
    }

    @Override
    public Object lookupLink(Name name) throws NamingException {
        return rootContext.lookupLinkNative(new CompositeName(this.rootName + "/" + name.toString()));
    }

    @Override
    public Object lookupLink(String s) throws NamingException {
        return rootContext.lookupLinkNative(new CompositeName(this.rootName + "/" + s.toString()));
    }

    @Override
    public NameParser getNameParser(Name name) throws NamingException {
        return rootContext.getNameParser(name);
    }

    @Override
    public NameParser getNameParser(String s) throws NamingException {
        return rootContext.getNameParser(s);
    }

    @Override
    public Name composeName(Name name, Name name1) throws NamingException {
        return rootContext.composeName(name, name1);
    }

    @Override
    public String composeName(String s, String s1) throws NamingException {
        return rootContext.composeName(s, s1);
    }

    @Override
    public Object addToEnvironment(String s, Object o) throws NamingException {
        return environment.put(s, o);
    }

    @Override
    public Object removeFromEnvironment(String s) throws NamingException {
        return environment.remove(s);
    }

    @Override
    public Hashtable<?, ?> getEnvironment() throws NamingException {
        return environment;
    }

    @Override
    public void close() throws NamingException {

    }

    @Override
    public String getNameInNamespace() throws NamingException {
        return rootName;
    }
}
