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
import javax.naming.InvalidNameException;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;

import org.wildfly.naming.client.util.FastHashtable;

/**
 * @author Stuart Douglas
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class HttpRemoteContext implements Context {

    private final HttpRootContext rootContext;
    private final String rootName;
    private final Hashtable<Object, Object> environment;

    public HttpRemoteContext(final HttpRootContext rootContext, final String rootName) {
        this.rootContext = rootContext;
        this.rootName = rootName;
        try {
            this.environment = new FastHashtable<>(rootContext.getEnvironment());
        } catch (NamingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object lookup(final Name name) throws NamingException {
        return rootContext.lookupNative(getNameOf(name));
    }

    @Override
    public Object lookup(final String name) throws NamingException {
        return rootContext.lookupNative(getNameOf(name));
    }

    @Override
    public void bind(final Name name, final Object obj) throws NamingException {
        rootContext.bindNative(getNameOf(name), obj);
    }

    @Override
    public void bind(final String name, final Object obj) throws NamingException {
        rootContext.bindNative(getNameOf(name), obj);
    }

    @Override
    public void rebind(final Name name, final Object obj) throws NamingException {
        rootContext.rebindNative(getNameOf(name), obj);
    }

    @Override
    public void rebind(final String name, final Object obj) throws NamingException {
        rootContext.rebindNative(getNameOf(name), obj);
    }

    @Override
    public void unbind(final Name name) throws NamingException {
        rootContext.unbindNative(getNameOf(name));
    }

    @Override
    public void unbind(final String name) throws NamingException {
        rootContext.unbindNative(getNameOf(name));
    }

    @Override
    public void rename(final Name oldName, final Name newName) throws NamingException {
        rootContext.renameNative(getNameOf(oldName), getNameOf(newName));
    }

    @Override
    public void rename(final String oldName, final String newName) throws NamingException {
        rootContext.renameNative(getNameOf(oldName), getNameOf(newName));
    }

    @Override
    public NamingEnumeration<NameClassPair> list(final Name name) throws NamingException {
        return rootContext.listNative(getNameOf(name));
    }

    @Override
    public NamingEnumeration<NameClassPair> list(final String name) throws NamingException {
        return rootContext.listNative(getNameOf(name));
    }

    @Override
    public NamingEnumeration<Binding> listBindings(final Name name) throws NamingException {
        return rootContext.listBindingsNative(getNameOf(name));
    }

    @Override
    public NamingEnumeration<Binding> listBindings(final String name) throws NamingException {
        return rootContext.listBindingsNative(getNameOf(name));
    }

    @Override
    public void destroySubcontext(final Name name) throws NamingException {
        rootContext.destroySubcontextNative(getNameOf(name));
    }

    @Override
    public void destroySubcontext(final String name) throws NamingException {
        rootContext.destroySubcontextNative(getNameOf(name));
    }

    @Override
    public Context createSubcontext(final Name name) throws NamingException {
        return rootContext.createSubcontextNative(getNameOf(name));
    }

    @Override
    public Context createSubcontext(final String name) throws NamingException {
        return rootContext.createSubcontextNative(getNameOf(name));
    }

    @Override
    public Object lookupLink(final Name name) throws NamingException {
        return rootContext.lookupLinkNative(getNameOf(name));
    }

    @Override
    public Object lookupLink(final String name) throws NamingException {
        return rootContext.lookupLinkNative(getNameOf(name));
    }

    @Override
    public NameParser getNameParser(final Name name) throws NamingException {
        return rootContext.getNameParser(name);
    }

    @Override
    public NameParser getNameParser(final String name) throws NamingException {
        return rootContext.getNameParser(name);
    }

    @Override
    public Name composeName(final Name name, final Name prefix) throws NamingException {
        return rootContext.composeName(name, prefix);
    }

    @Override
    public String composeName(final String name, final String prefix) throws NamingException {
        return rootContext.composeName(name, prefix);
    }

    @Override
    public Object addToEnvironment(final String propName, final Object propVal) throws NamingException {
        return environment.put(propName, propVal);
    }

    @Override
    public Object removeFromEnvironment(final String propName) throws NamingException {
        return environment.remove(propName);
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

    // helper methods

    private CompositeName getNameOf(final Object name) throws InvalidNameException {
        if (name == null) {
            throw new InvalidNameException();
        }
        return new CompositeName(rootName + "/" + name);
    }

}
