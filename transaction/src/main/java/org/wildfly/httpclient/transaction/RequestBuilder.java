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
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.wildfly.httpclient.common.HeadersHelper.putRequestHeader;
import static org.wildfly.httpclient.common.Protocol.VERSION_PATH;
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
import org.wildfly.httpclient.common.Protocol;

/**
 * HTTP TXN module client request builder. Encapsulates all information needed to create HTTP TXN client requests.
 * Use setter methods (those returning {@link RequestBuilder}) to configure the builder.
 * Once configured {@link #createRequest(String)} method must be called to build HTTP client request.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class RequestBuilder {

    private RequestType requestType;
    private int version = Protocol.LATEST;
    private int timeout;
    private int flags;
    private String parentName;
    private Boolean onePhase;

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

    RequestBuilder setOnePhase(final Boolean onePhase) {
        this.onePhase = onePhase;
        return this;
    }

    RequestBuilder setParent(final String parentName) {
        this.parentName = parentName;
        return this;
    }

    // helper methods

    ClientRequest createRequest(final String prefix) {
        final ClientRequest request = new ClientRequest();
        setRequestMethod(request);
        setRequestPath(request, prefix);
        setRequestHeaders(request);
        return request;
    }

    private void setRequestMethod(final ClientRequest request) {
        request.setMethod(requestType.getMethod());
    }

    private void setRequestPath(final ClientRequest request, final String prefix) {
        final StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix);
        }
        appendPath(sb, TXN_CONTEXT, false);
        appendPath(sb, VERSION_PATH + version, false);
        appendPath(sb, requestType.getPath(), false);
        if (requestType == XA_COMMIT) {
            sb.append(onePhase != null && onePhase ? "?" + OPC_QUERY_PARAMETER + "=" + TRUE : "");
        } else if (requestType == XA_RECOVER) {
            appendPath(sb, parentName, false);
        }
        request.setPath(sb.toString());
    }


    private void setRequestHeaders(final ClientRequest request) {
        if (requestType == UT_BEGIN) {
            putRequestHeader(request, ACCEPT, EXCEPTION + "," + NEW_TRANSACTION);
            putRequestHeader(request, TIMEOUT, String.valueOf(timeout));
        } else if (requestType == XA_RECOVER) {
            putRequestHeader(request, ACCEPT, XID_LIST + "," + NEW_TRANSACTION);
            putRequestHeader(request, RECOVERY_PARENT_NAME, parentName);
            putRequestHeader(request, RECOVERY_FLAGS, String.valueOf(flags));
        } else {
            putRequestHeader(request, ACCEPT, EXCEPTION);
            putRequestHeader(request, CONTENT_TYPE, XID);
        }
    }

    private static void appendPath(final StringBuilder sb, final String path, final boolean encode) {
        if (!path.startsWith("/")) {
            sb.append("/");
        }
        sb.append(encode ? encode(path, UTF_8) : path);
    }

}
