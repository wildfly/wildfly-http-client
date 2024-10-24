/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2024 Red Hat, Inc., and individual contributors
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

import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * Utility class providing security manager sensitive methods for setting and getting current context class loaders.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ClassLoaderUtils {

    private ClassLoaderUtils() {
        // forbidden instantiation
    }

    private static final class GetTCCLAction implements PrivilegedAction<ClassLoader> {
        private static final GetTCCLAction INSTANCE = new GetTCCLAction();

        @Override
        public ClassLoader run() {
            return Thread.currentThread().getContextClassLoader();
        }
    }

    private static final class SetTCCLAction implements PrivilegedAction<ClassLoader> {
        private final ClassLoader newClassLoader;

        private SetTCCLAction(final ClassLoader newClassLoader) {
            this.newClassLoader = newClassLoader;
        }

        @Override
        public ClassLoader run() {
            final ClassLoader oldClassLoader = GetTCCLAction.INSTANCE.run();
            Thread.currentThread().setContextClassLoader(newClassLoader);
            return oldClassLoader;
        }
    }

    static ClassLoader getContextClassLoader() {
        if (System.getSecurityManager() == null) {
            return GetTCCLAction.INSTANCE.run();
        } else {
            return AccessController.doPrivileged(GetTCCLAction.INSTANCE);
        }
    }

    static ClassLoader setContextClassLoader(final ClassLoader newClassLoader) {
        if (System.getSecurityManager() == null) {
            return new SetTCCLAction(newClassLoader).run();
        } else {
            return AccessController.doPrivileged(new SetTCCLAction(newClassLoader));
        }
    }

}
