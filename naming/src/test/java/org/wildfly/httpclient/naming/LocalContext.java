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

import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.Name;
import javax.naming.NameAlreadyBoundException;
import javax.naming.NameClassPair;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocalContext implements Context {

    private final Map<String, Object> bindings = new ConcurrentHashMap<>();

    private final boolean readOnly;

    public LocalContext(boolean readOnly) {
        bindings.put("test", "test value");
        bindings.put("comp/UserTransaction", "transaction");
        bindings.put("test-resolver-helper", TestHttpNamingEjbObjectResolverHelper.create("test"));
        this.readOnly = readOnly;
    }

    @Override
    public Object lookup(Name name) throws NamingException {
        return lookup(name.toString());
    }

    @Override
    public Object lookup(String name) throws NamingException {
        Object res = bindings.get(name);
        if (res == null) {
            throw new NameNotFoundException();
        }
        return res;
    }

    @Override
    public void bind(Name name, Object obj) throws NamingException {
        bind(name.toString(), obj);
    }

    @Override
    public void bind(String name, Object obj) throws NamingException {
        if (readOnly) {
            throw new OperationNotSupportedException("bind is read-only");
        }
        if (bindings.containsKey(name)) {
            throw new NameAlreadyBoundException("Name: " + name + " has been bound");
        }
        bindings.put(name, obj);
    }

    @Override
    public void rebind(Name name, Object obj) throws NamingException {
        rebind(name.toString(), obj);
    }

    @Override
    public void rebind(String name, Object obj) throws NamingException {
        if (readOnly) {
            throw new OperationNotSupportedException("rebind is read-only");
        }
        bindings.put(name, obj);
    }

    @Override
    public void unbind(Name name) throws NamingException {
        unbind(name.toString());
    }

    @Override
    public void unbind(String name) throws NamingException {
        if (readOnly) {
            throw new OperationNotSupportedException("unbind is read-only");
        }
        Object obj = bindings.remove(name);
        if (obj == null) {
            throw new NameNotFoundException();
        }
    }

    @Override
    public void rename(Name oldName, Name newName) throws NamingException {
        rename(oldName.toString(), newName.toString());
    }

    @Override
    public void rename(String oldName, String newName) throws NamingException {
        if (readOnly) {
            throw new OperationNotSupportedException("rename is read-only");
        }
        Object obj = bindings.remove(oldName);
        if (obj == null) {
            throw new NameNotFoundException();
        }
        bindings.put(newName, obj);
    }

    @Override
    public NamingEnumeration<NameClassPair> list(Name name) throws NamingException {
        return list(name.toString());
    }

    @Override
    public NamingEnumeration<NameClassPair> list(String name) throws NamingException {
        final Iterator<String> iterator = bindings.keySet().iterator();
        return new NamingEnumeration<NameClassPair>() {
            public NameClassPair next() {
                return nextElement();
            }

            public boolean hasMore() {
                return hasMoreElements();
            }

            public void close() {
            }

            public boolean hasMoreElements() {
                return iterator.hasNext();
            }

            public NameClassPair nextElement() {
                final String name = iterator.next();
                return new NameClassPair(name, (String)null);
            }
        };
    }

    @Override
    public NamingEnumeration<Binding> listBindings(Name name) throws NamingException {
        return listBindings(name.toString());
    }

    @Override
    public NamingEnumeration<Binding> listBindings(String name) throws NamingException {
        final Iterator<String> iterator = bindings.keySet().iterator();
        return new NamingEnumeration<Binding>() {
            public Binding next() {
                return nextElement();
            }

            public boolean hasMore() {
                return hasMoreElements();
            }

            public void close() {
            }

            public boolean hasMoreElements() {
                return iterator.hasNext();
            }

            public Binding nextElement() {
                final String name = iterator.next();
                return new Binding(name, bindings.get(name));
            }
        };
    }

    @Override
    public void destroySubcontext(Name name) throws NamingException {
        destroySubcontext(name.toString());
    }

    @Override
    public void destroySubcontext(String name) throws NamingException {
        if (readOnly) {
            throw new OperationNotSupportedException("destroySubcontext is read-only");
        }
        Object obj = bindings.get(name);
        if (obj == null) {
            throw new NameNotFoundException();
        }
        if (!(obj instanceof LocalContext)) {
            throw new InvalidNameException("Name: " + name + " is not a LocalContext");
        }
        bindings.remove(name);
    }

    @Override
    public Context createSubcontext(Name name) throws NamingException {
        return createSubcontext(name.toString());
    }

    @Override
    public Context createSubcontext(String name) throws NamingException {
        if (readOnly) {
            throw new OperationNotSupportedException("createSubcontext is read-only");
        }
        if (bindings.containsKey(name)) {
            throw new NameAlreadyBoundException("Name: " + name + " has been bound");
        }
        LocalContext ctx = new LocalContext(false);
        bindings.put(name, ctx);
        return ctx;
    }

    @Override
    public Object lookupLink(Name name) throws NamingException {
        return lookupLink(name.toString());
    }

    @Override
    public Object lookupLink(String name) throws NamingException {
        return null;
    }

    @Override
    public NameParser getNameParser(Name name) throws NamingException {
        return null;
    }

    @Override
    public NameParser getNameParser(String name) throws NamingException {
        return null;
    }

    @Override
    public Name composeName(Name name, Name prefix) throws NamingException {
        return null;
    }

    @Override
    public String composeName(String name, String prefix) throws NamingException {
        return null;
    }

    @Override
    public Object addToEnvironment(String propName, Object propVal) throws NamingException {
        return null;
    }

    @Override
    public Object removeFromEnvironment(String propName) throws NamingException {
        return null;
    }

    @Override
    public Hashtable<?, ?> getEnvironment() throws NamingException {
        return null;
    }

    @Override
    public void close() throws NamingException {

    }

    @Override
    public String getNameInNamespace() throws NamingException {
        return null;
    }
}
