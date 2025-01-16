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

    static void serializeXid(final ObjectOutput output, final Xid xid) throws IOException {
        output.writeInt(xid.getFormatId());
        output.writeInt(xid.getGlobalTransactionId().length);
        output.write(xid.getGlobalTransactionId());
        output.writeInt(xid.getBranchQualifier().length);
        output.write(xid.getBranchQualifier());
    }

    static Xid deserializeXid(final ObjectInput input) throws IOException {
        int formatId = input.readInt();
        int length = input.readInt();
        byte[] globalId = new byte[length];
        input.readFully(globalId);
        length = input.readInt();
        byte[] branchId = new byte[length];
        input.readFully(branchId);
        return new SimpleXid(formatId, globalId, branchId);
    }

    static void serializeXidArray(final ObjectOutput output, final Xid[] xids) throws IOException {
        output.writeInt(xids.length);
        for (Xid xid : xids) {
            serializeXid(output, xid);
        }
    }

    static Xid[] deserializeXidArray(final ObjectInput input) throws IOException {
        int length = input.readInt();
        Xid[] ret = new Xid[length];
        for (int i = 0; i < length; ++i) {
            ret[i] = deserializeXid(input);
        }
        return ret;
    }

}
