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
package org.wildfly.httpclient.ejb;

import javax.transaction.xa.Xid;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class TransactionInfo {
    static byte NULL_TRANSACTION = 0;
    static byte REMOTE_TRANSACTION = 1;
    static byte LOCAL_TRANSACTION = 2;
    private static final int UNDEFINED = 0;
    private static final TransactionInfo NULL = new TransactionInfo(NULL_TRANSACTION, null, UNDEFINED);

    private final byte type;
    private final Xid xid;
    private final int remainingTime;

    private TransactionInfo(final byte type, final Xid xid, final int remainingTime) {
        this.type = type;
        this.xid = xid;
        this.remainingTime = remainingTime;
    }

    byte getType() {
        return type;
    }

    Xid getXid() {
        return xid;
    }

    int getRemainingTime() {
        return remainingTime;
    }

    static TransactionInfo nullTransaction() {
        return NULL;
    }

    static TransactionInfo remoteTransaction(final Xid xid) {
        return new TransactionInfo(REMOTE_TRANSACTION, xid, UNDEFINED);
    }

    static TransactionInfo localTransaction(final Xid xid, final int remainingTime) {
        return new TransactionInfo(LOCAL_TRANSACTION, xid, remainingTime);
    }
}
