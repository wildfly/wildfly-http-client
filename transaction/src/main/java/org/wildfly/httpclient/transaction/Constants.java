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

package org.wildfly.httpclient.transaction;

import org.wildfly.httpclient.common.ContentType;
import io.undertow.util.HttpString;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class Constants {

    // request headers
    static final ContentType EXCEPTION = new ContentType("application/x-wf-jbmar-exception", 1);
    static final ContentType XID = new ContentType("application/x-wf-jbmar-xid", 1);
    static final ContentType XID_LIST = new ContentType("application/x-wf-txn-jbmar-xid-list", 1);
    static final ContentType NEW_TRANSACTION = new ContentType("application/x-wf-jbmar-new-txn", 1);

    static final HttpString TIMEOUT = new HttpString("x-wf-txn-timeout");
    static final HttpString RECOVERY_PARENT_NAME = new HttpString("x-wf-txn-parent-name");
    static final HttpString RECOVERY_FLAGS = new HttpString("x-wf-txn-recovery-flags");

    // response headers
    static final HttpString READ_ONLY = new HttpString("x-wf-txn-read-only");

    // context path
    static final String TXN_CONTEXT = "/txn";

    // protocols
    static final String HTTP_SCHEME = "http";
    static final String HTTPS_SCHEME = "https";

    private Constants() {
        // forbidden instantiation
    }

}
