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

package org.wildfly.httpclient.transaction;

import static io.undertow.util.Headers.ACCEPT;
import static io.undertow.util.Headers.CONTENT_TYPE;
import static org.wildfly.httpclient.transaction.RequestType.UT_BEGIN;
import static org.wildfly.httpclient.transaction.RequestType.XA_RECOVER;
import static org.wildfly.httpclient.transaction.TransactionConstants.EXCEPTION;
import static org.wildfly.httpclient.transaction.TransactionConstants.NEW_TRANSACTION;
import static org.wildfly.httpclient.transaction.TransactionConstants.RECOVERY_FLAGS;
import static org.wildfly.httpclient.transaction.TransactionConstants.RECOVERY_PARENT_NAME;
import static org.wildfly.httpclient.transaction.TransactionConstants.TIMEOUT;
import static org.wildfly.httpclient.transaction.TransactionConstants.XID;
import static org.wildfly.httpclient.transaction.TransactionConstants.XID_LIST;

import io.undertow.client.ClientRequest;
import io.undertow.util.HeaderMap;
import org.wildfly.httpclient.common.Protocol;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class RequestBuilder {

    private RequestType requestType;
    private int version = Protocol.LATEST;
    private int timeout;
    private int flags;
    private String parentName;

    // setters

    RequestBuilder setRequestType(final RequestType requestType) {
        this.requestType = requestType;
        return this;
    }

    RequestBuilder setVersion(final int version) {
        this.version = version;
        return this;
    }

    RequestBuilder setTimeout(final int timeout) {
        this.timeout = timeout;
        return this;
    }

    RequestBuilder setFlags(final int flags) {
        this.flags = flags;
        return this;
    }

    RequestBuilder setParent(final String parentName) {
        this.parentName = parentName;
        return this;
    }

    // helper methods

    ClientRequest createRequest(final String prefix) {
        final ClientRequest clientRequest = new ClientRequest();
        setRequestMethod(clientRequest);
        setRequestPath(clientRequest, prefix);
        setRequestHeaders(clientRequest);
        return clientRequest;
    }

    private void setRequestMethod(final ClientRequest request) {
        request.setMethod(requestType.getMethod());
    }

    private void setRequestPath(final ClientRequest request, final String prefix) {
        // NOOP
    }

    private void setRequestHeaders(final ClientRequest request) {
        final HeaderMap headers = request.getRequestHeaders();
        if (requestType == UT_BEGIN) {
            headers.put(ACCEPT, EXCEPTION + "," + NEW_TRANSACTION);
            headers.put(TIMEOUT, timeout);
        } else if (requestType == XA_RECOVER) {
            headers.put(ACCEPT, XID_LIST + "," + NEW_TRANSACTION);
            headers.put(RECOVERY_PARENT_NAME, parentName);
            headers.put(RECOVERY_FLAGS, Integer.toString(flags));
        } else {
            headers.add(ACCEPT, EXCEPTION.toString());
            headers.put(CONTENT_TYPE, XID.toString());
        }
    }

}
