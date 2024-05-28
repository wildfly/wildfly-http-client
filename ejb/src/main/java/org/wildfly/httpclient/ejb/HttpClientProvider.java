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

package org.wildfly.httpclient.ejb;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.jboss.ejb.client.AttachmentKey;
import org.jboss.ejb.client.EJBReceiver;
import org.jboss.ejb.client.EJBReceiverContext;
import org.jboss.ejb.client.EJBTransportProvider;

/**
 * @author Stuart Douglas
 */
public class HttpClientProvider implements EJBTransportProvider {

    public static final String HTTP = Constants.HTTP_SCHEME;
    public static final String HTTPS = Constants.HTTPS_SCHEME;
    public static final Set<String> PROTOCOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(HTTP, HTTPS)));

    private static final AttachmentKey<HttpEJBReceiver> RECEIVER = new AttachmentKey<>();

    @Override
    public void notifyRegistered(EJBReceiverContext receiverContext) {
        receiverContext.getClientContext().putAttachment(RECEIVER, new HttpEJBReceiver());
    }

    @Override
    public boolean supportsProtocol(String uriScheme) {
        return PROTOCOLS.contains(uriScheme);
    }

    @Override
    public EJBReceiver getReceiver(EJBReceiverContext ejbReceiverContext, String s) throws IllegalArgumentException {
        if(PROTOCOLS.contains(s)) {
            HttpEJBReceiver receiver = ejbReceiverContext.getClientContext().getAttachment(RECEIVER);
            if(receiver != null) {
                return receiver;
            }
        }
        throw EjbHttpClientMessages.MESSAGES.couldNotCreateHttpEjbReceiverFor(s);
    }

    @Override
    public void close(EJBReceiverContext receiverContext) throws Exception {

    }
}
