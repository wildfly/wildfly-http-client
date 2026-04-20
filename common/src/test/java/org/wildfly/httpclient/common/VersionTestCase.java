/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2026 Red Hat, Inc., and individual contributors
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

import org.junit.Assert;
import org.junit.Test;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class VersionTestCase {

    @Test
    public void testJakartaEE8VersionConstant() {
        final Version version = Version.JAVA_EE_8;
        Assert.assertNotSame(Version.LATEST, version);
        Assert.assertSame(Version.Encoding.JBOSS_MARSHALLING, version.encoding());
        Assert.assertSame(Version.Handler.VERSION_1, version.handler());
        Assert.assertSame(Version.Specification.JAVA_EE_8, version.specitication());
        Assert.assertEquals("1", version.toString());
    }

    @Test
    public void testJakartaEE10VersionConstant() {
        final Version version = Version.JAKARTA_EE_10;
        Assert.assertSame(Version.LATEST, version);
        Assert.assertSame(Version.Encoding.JBOSS_MARSHALLING, version.encoding());
        Assert.assertSame(Version.Handler.VERSION_2, version.handler());
        Assert.assertSame(Version.Specification.JAKARTA_EE_10, version.specitication());
        Assert.assertEquals("2", version.toString());
    }

    @Test
    public void testJakartaEE8VersionOfMethod() {
        final Version version = Version.of(1);
        Assert.assertNotSame(Version.LATEST, version);
        Assert.assertSame(Version.Encoding.JBOSS_MARSHALLING, version.encoding());
        Assert.assertSame(Version.Handler.VERSION_1, version.handler());
        Assert.assertSame(Version.Specification.JAVA_EE_8, version.specitication());
        Assert.assertEquals("1", version.toString());
    }

    @Test
    public void testJakartaEE10VersionOfMethod() {
        final Version version = Version.of(2);
        Assert.assertSame(Version.LATEST, version);
        Assert.assertSame(Version.Encoding.JBOSS_MARSHALLING, version.encoding());
        Assert.assertSame(Version.Handler.VERSION_2, version.handler());
        Assert.assertSame(Version.Specification.JAKARTA_EE_10, version.specitication());
        Assert.assertEquals("2", version.toString());
    }

}
