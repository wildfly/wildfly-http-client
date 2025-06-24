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

import static java.lang.Boolean.TRUE;
import static io.undertow.util.Headers.ACCEPT;
import static io.undertow.util.Headers.CONTENT_TYPE;
import static org.wildfly.httpclient.common.HeadersHelper.putRequestHeader;
import static org.wildfly.httpclient.transaction.Constants.EXCEPTION;
import static org.wildfly.httpclient.transaction.Constants.NEW_TRANSACTION;
import static org.wildfly.httpclient.transaction.Constants.OPC_QUERY_PARAMETER;
import static org.wildfly.httpclient.transaction.Constants.RECOVERY_FLAGS;
import static org.wildfly.httpclient.transaction.Constants.RECOVERY_PARENT_NAME;
import static org.wildfly.httpclient.transaction.Constants.TIMEOUT;
import static org.wildfly.httpclient.transaction.Constants.TXN_CONTEXT;
import static org.wildfly.httpclient.transaction.Constants.XID;
import static org.wildfly.httpclient.transaction.Constants.XID_LIST;
import static org.wildfly.httpclient.transaction.RequestType.UT_BEGIN;
import static org.wildfly.httpclient.transaction.RequestType.XA_COMMIT;
import static org.wildfly.httpclient.transaction.RequestType.XA_RECOVER;

import io.undertow.client.ClientRequest;

import org.wildfly.httpclient.common.HttpTargetContext;

/**
 * HTTP TXN module client request builder. Encapsulates all information needed to create HTTP TXN client requests.
 * Use setter methods (those returning {@link RequestBuilder}) to configure the builder.
 * Once configured {@link #createRequest(String)} method must be called to build HTTP client request.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class RequestBuilder extends org.wildfly.httpclient.common.RequestBuilder<RequestType> {

    private int timeout;
    private int flags;
    private String parentName;
    private Boolean onePhase;

    // constructor

    RequestBuilder(final HttpTargetContext targetContext, final RequestType requestType) {
        super(targetContext, requestType);
    }

    // setters

    RequestBuilder setTimeout(final int timeout) {
        this.timeout = timeout;
        return this;
    }

    RequestBuilder setFlags(final int flags) {
        this.flags = flags;
        return this;
    }

    RequestBuilder setOnePhase(final Boolean onePhase) {
        this.onePhase = onePhase;
        return this;
    }

    RequestBuilder setParent(final String parentName) {
        this.parentName = parentName;
        return this;
    }

    // implementation

    @Override
    protected void setRequestPath(final ClientRequest request) {
        final StringBuilder sb = new StringBuilder();
        appendOperationPath(sb, TXN_CONTEXT);
        if (getRequestType() == XA_COMMIT) {
            if (onePhase != null && onePhase) {
                setQueryParameter(sb, OPC_QUERY_PARAMETER, TRUE.toString());
            }
        } else if (getRequestType() == XA_RECOVER) {
            appendPath(sb, parentName, false);
        }
        request.setPath(sb.toString());
    }


    @Override
    protected void setRequestHeaders(final ClientRequest request) {
        if (getRequestType() == UT_BEGIN) {
            putRequestHeader(request, ACCEPT, EXCEPTION + "," + NEW_TRANSACTION);
            putRequestHeader(request, TIMEOUT, String.valueOf(timeout));
        } else if (getRequestType() == XA_RECOVER) {
            putRequestHeader(request, ACCEPT, XID_LIST + "," + NEW_TRANSACTION);
            putRequestHeader(request, RECOVERY_PARENT_NAME, parentName);
            putRequestHeader(request, RECOVERY_FLAGS, String.valueOf(flags));
        } else {
            putRequestHeader(request, ACCEPT, EXCEPTION);
            putRequestHeader(request, CONTENT_TYPE, XID);
        }
    }

}
