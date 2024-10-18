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

import org.wildfly.transaction.client.SimpleXid;

import javax.transaction.xa.Xid;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * Java objects serialization helper class. Provides utility methods for de / serialization of
 * supported types of Remote TXN over HTTP protocol.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class Serializer {

    private Serializer() {
        // forbidden instantiation
    }

    static void serializeXid(final ObjectOutput out, final Xid xid) throws IOException {
        out.writeInt(xid.getFormatId());
        out.writeInt(xid.getGlobalTransactionId().length);
        out.write(xid.getGlobalTransactionId());
        out.writeInt(xid.getBranchQualifier().length);
        out.write(xid.getBranchQualifier());
    }

    static Xid deserializeXid(final ObjectInput in) throws IOException {
        int formatId = in.readInt();
        int length = in.readInt();
        byte[] globalId = new byte[length];
        in.readFully(globalId);
        length = in.readInt();
        byte[] branchId = new byte[length];
        in.readFully(branchId);
        return new SimpleXid(formatId, globalId, branchId);
    }

    static void serializeXidArray(final ObjectOutput out, final Xid[] xids) throws IOException {
        out.writeInt(xids.length);
        for (Xid xid : xids) {
            serializeXid(out, xid);
        }
    }

    static Xid[] deserializeXidArray(final ObjectInput in) throws IOException {
        int length = in.readInt();
        Xid[] ret = new Xid[length];
        for (int i = 0; i < length; ++i) {
            ret[i] = deserializeXid(in);
        }
        return ret;
    }

    static void serializeThrowable(final ObjectOutput out, final Throwable t) throws IOException {
        out.writeObject(t);
        out.write(0);
    }

}
