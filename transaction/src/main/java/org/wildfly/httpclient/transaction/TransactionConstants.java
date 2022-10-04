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
 * @author Stuart Douglas
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class TransactionConstants {

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

    // paths
    static final String UT_BEGIN_PATH = "/ut/begin";
    static final String UT_COMMIT_PATH = "/ut/commit";
    static final String UT_ROLLBACK_PATH = "/ut/rollback";
    static final String XA_COMMIT_PATH = "/xa/commit";
    static final String XA_ROLLBACK_PATH = "/xa/rollback";
    static final String XA_PREP_PATH = "/xa/prep";
    static final String XA_FORGET_PATH = "/xa/forget";
    static final String XA_BC_PATH = "/xa/bc";
    static final String XA_RECOVER_PATH = "/xa/recover";

    // protocols
    static final String HTTP_SCHEME = "http";
    static final String HTTPS_SCHEME = "https";

    private TransactionConstants() {
        // forbidden instantiation
    }

}
