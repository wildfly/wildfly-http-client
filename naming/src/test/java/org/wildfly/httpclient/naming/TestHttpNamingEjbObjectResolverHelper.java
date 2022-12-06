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
package org.wildfly.httpclient.naming;

import java.io.Serializable;
import java.net.URI;
import java.util.Objects;
import org.jboss.marshalling.ObjectResolver;

/**
 * <p>A test HttpNamingEjbObjectResolverHelper that transform a TestObject
 * with value <em>test</em> to <em>readResolve/writeReplace->URI</em></p>.
 *
 * @author rmartinc
 */
public class TestHttpNamingEjbObjectResolverHelper implements HttpNamingEjbObjectResolverHelper {

    /**
     * Serializable test class.
     */
    public static class TestObject implements Serializable {
        private String value;

        private TestObject(String value) {
            this.value = value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public int hashCode() {
            return 217 + Objects.hashCode(this.value);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof TestObject) {
                return Objects.equals(this.value, ((TestObject) obj).value);
            }
            return false;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * Creates a TestObject with the passed value.
     *
     * @param value The test value.
     * @return The created TestObject.
     */
    public static TestObject create(String value) {
        return new TestObject(value);
    }

    @Override
    public ObjectResolver getObjectResolver(URI uri) {
        return new ObjectResolver() {
            @Override
            public Object readResolve(Object replacement) {
                if (create("test").equals(replacement)) {
                    return create("readResolve->" + uri);
                }
                return replacement;
            }

            @Override
            public Object writeReplace(Object original) {
                if (create("test").equals(original)) {
                    return create("writeReplace->" + uri);
                }
                return original;
            }
        };
    }

}
