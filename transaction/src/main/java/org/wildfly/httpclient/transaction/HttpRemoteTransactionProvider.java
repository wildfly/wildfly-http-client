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

package org.wildfly.httpclient.transaction;

import static org.wildfly.httpclient.transaction.TransactionConstants.HTTP_SCHEME;
import static org.wildfly.httpclient.transaction.TransactionConstants.HTTPS_SCHEME;

import java.net.URI;

import javax.net.ssl.SSLContext;
import jakarta.transaction.SystemException;

import org.wildfly.httpclient.common.Version;
import org.wildfly.httpclient.common.WildflyHttpContext;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.transaction.client.spi.RemoteTransactionPeer;
import org.wildfly.transaction.client.spi.RemoteTransactionProvider;

/**
  * A class which provides a versioned peer for controlling non-XA and XA transactions on a target server.
 *
 * @author Stuart Douglas
 * @author Richard Achmatowicz
 */
public class HttpRemoteTransactionProvider implements RemoteTransactionProvider {

    @Override
    public RemoteTransactionPeer getPeerHandle(final URI uri, final SSLContext sslContext, final AuthenticationConfiguration authenticationConfiguration) throws SystemException {
        return new HttpRemoteTransactionPeer(Version.LATEST, WildflyHttpContext.getCurrent().getTargetContext(uri), sslContext, authenticationConfiguration);
    }

    @Override
    public boolean supportsScheme(String s) {
        switch (s) {
            case HTTP_SCHEME:
            case HTTPS_SCHEME:
                return true;
        }
        return false;
    }
}
