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
 * HTTP TXN module invocation types. Each invocation type has {@linkplain #getName() name}, {@linkplain #getMethod() method}
 * and {@linkplain #getPath() path}. An invocation can be one of the following types:
 * <ul>
 *     <li>{@link #UT_BEGIN}<br>
 *     Establishes a remote user-controlled transaction.
 *     </li>
 *     <li>{@link #UT_COMMIT}<br>
 *     Commits a remote user-controlled transaction.
 *     </li>
 *     <li>{@link #UT_ROLLBACK}<br>
 *     Rolls back a remote user-controlled transaction.
 *     </li>
 *     <li>{@link #XA_RECOVER}<br>
 *     Acquires a list of all unresolved subordinate remote user-controlled transactions.
 *     </li>
 *     <li>{@link #XA_BEFORE_COMPLETION}<br>
 *     Performs before-commit operations, including running all transaction synchronizations on subordinate transaction.
 *     </li>
 *     <li>{@link #XA_COMMIT}<br>
 *     Commits a subordinate transaction.
 *     </li>
 *     <li>{@link #XA_FORGET}<br>
 *     Forgets the (previously prepared) subordinate transaction.
 *     </li>
 *     <li>{@link #XA_PREPARE}<br>
 *     Prepares the subordinate transaction.
 *     </li>
 *     <li>{@link #XA_ROLLBACK}<br>
 *     Rolls back the subordinate transaction.
 *     </li>
 * </ul>
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
enum RequestType {

    /**
     * {@code UT_BEGIN} invocation type: used to establish a remote user-controlled transaction via HTTP protocol.
     */
    UT_BEGIN(POST, UT_BEGIN_PATH),
    /**
     * {@code UT_COMMIT} invocation type: used to commit remote user-controlled transaction via HTTP protocol.
     */
    UT_COMMIT(POST, UT_COMMIT_PATH),
    /**
     * {@code UT_ROLLBACK} invocation type: used to rollback remote user-controlled transaction via HTTP protocol.
     */
    UT_ROLLBACK(POST, UT_ROLLBACK_PATH),
    /**
     * {@code XA_RECOVER} invocation type: used to acquire a list of all unresolved subordinate remote user-controlled
     * transactions from the location associated with this provider via HTTP protocol.
     */
    // TODO: THIS IS BUG. The name must be UT_RECOVER & request path must contain 'ut' instead of 'xa' prefix
    XA_RECOVER(GET, XA_RECOVER_PATH),
    /**
     * {@code XA_BEFORE_COMPLETION} invocation type: used to perform before-commit operations,
     * including running all transaction synchronizations on given subordinate transaction via HTTP protocol.
     */
    XA_BEFORE_COMPLETION(POST, XA_BC_PATH),
    /**
     * {@code XA_COMMIT} invocation type: used to commit the subordinate transaction via HTTP protocol.
     */
    XA_COMMIT(POST, XA_COMMIT_PATH),
    /**
     * {@code XA_FORGET} invocation type: used to forget the (previously prepared) subordinate transaction via HTTP protocol.
     */
    XA_FORGET(POST, XA_FORGET_PATH),
    /**
     * {@code XA_PREPARE} invocation type: used to prepare the subordinate transaction via HTTP protocol.
     */
    XA_PREPARE(POST, XA_PREP_PATH),
    /**
     * {@code XA_ROLLBACK} invocation type: used to roll back the subordinate transaction via HTTP protocol.
     */
    XA_ROLLBACK(POST, XA_ROLLBACK_PATH);

    private final HttpString method;
    private final String path;

    RequestType(final HttpString method, final String path) {
        this.method = method;
        this.path = path;
    }

    /**
     * Returns the name of this invocation.
     * @return this invocation {@linkplain #name()}.
     */
    final String getName() {
        return name();
    }

    /**
     * Returns the HTTP request method used by this invocation.
     * @return this invocation HTTP request method.
     */
    final HttpString getMethod() {
        return method;
    }

    /**
     * Returns the HTTP request subpath used by this invocation.
     * @return this invocation HTTP request subpath.
     */
    final String getPath() {
        return path;
    }

}
