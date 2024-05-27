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

import static io.undertow.util.Methods.GET;
import static io.undertow.util.Methods.POST;
import static org.wildfly.httpclient.transaction.TransactionConstants.UT_BEGIN_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.UT_COMMIT_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.UT_ROLLBACK_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_BC_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_COMMIT_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_FORGET_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_PREP_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_RECOVER_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_ROLLBACK_PATH;

import io.undertow.util.HttpString;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
enum RequestType {

    UT_BEGIN(POST, UT_BEGIN_PATH),
    UT_COMMIT(POST, UT_COMMIT_PATH),
    UT_ROLLBACK(POST, UT_ROLLBACK_PATH),
    XA_BEFORE_COMPLETION(POST, XA_BC_PATH),
    XA_COMMIT(POST, XA_COMMIT_PATH),
    XA_FORGET(POST, XA_FORGET_PATH),
    XA_PREPARE(POST, XA_PREP_PATH),
    XA_RECOVER(GET, XA_RECOVER_PATH),
    XA_ROLLBACK(POST, XA_ROLLBACK_PATH);

    private final HttpString method;
    private final String path;

    RequestType(final HttpString method, final String path) {
        this.method = method;
        this.path = path;
    }

    HttpString getMethod() {
        return method;
    }

    String getPath() {
        return path;
    }

}
