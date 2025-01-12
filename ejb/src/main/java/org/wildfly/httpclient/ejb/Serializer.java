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

import static org.wildfly.httpclient.ejb.TransactionInfo.LOCAL_TRANSACTION;
import static org.wildfly.httpclient.ejb.TransactionInfo.NULL_TRANSACTION;
import static org.wildfly.httpclient.ejb.TransactionInfo.REMOTE_TRANSACTION;
import static org.wildfly.httpclient.ejb.TransactionInfo.localTransaction;
import static org.wildfly.httpclient.ejb.TransactionInfo.nullTransaction;
import static org.wildfly.httpclient.ejb.TransactionInfo.remoteTransaction;

import org.jboss.ejb.client.EJBModuleIdentifier;
import org.wildfly.transaction.client.SimpleXid;

import javax.transaction.xa.Xid;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Java objects serialization helper class. Provides utility methods for de / serialization of
 * supported types of Remote EJB over HTTP protocol.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class Serializer {

    private Serializer() {
        // forbidden instantiation
    }

    static void serializeObject(final ObjectOutput out, final Object o) throws IOException {
        out.writeObject(o);
    }

    static Object deserializeObject(final ObjectInput in) throws IOException, ClassNotFoundException {
        return in.readObject();
    }

    static void serializeObjectArray(final ObjectOutput out, final Object[] objects) throws IOException {
        if (objects == null) return;
        for (final Object object : objects) {
            out.writeObject(object);
        }
    }

    static void deserializeObjectArray(final ObjectInput in, final Object[] objects) throws IOException, ClassNotFoundException {
        if (objects == null) return;
        for (int i = 0; i < objects.length; i++) {
            objects[i] = deserializeObject(in);
        }
    }

    static void serializePackedInteger(final ObjectOutput out, int value) throws IOException {
        if (value < 0)
            throw new IllegalArgumentException();
        if (value > 127) {
            out.writeByte(value & 0x7F | 0x80);
            serializePackedInteger(out, value >> 7);
        } else {
            out.writeByte(value & 0xFF);
        }
    }

    static int deserializePackedInteger(final ObjectInput input) throws IOException {
        int ret = input.readByte();
        if ((ret & 0x80) == 0x80) {
            return deserializePackedInteger(input) << 7 | (ret & 0x7F);
        }
        return ret;
    }

    static void serializeMap(final ObjectOutput out, final Map<String, Object> contextData) throws IOException {
        int size = contextData != null ? contextData.size() : 0;
        serializePackedInteger(out, size);
        if (size > 0) for (Map.Entry<String, Object> entry : contextData.entrySet()) {
            out.writeObject(entry.getKey());
            out.writeObject(entry.getValue());
        }
    }

    static Map<String, Object> deserializeMap(final ObjectInput in) throws IOException, ClassNotFoundException {
        final int contextDataSize = deserializePackedInteger(in);
        if (contextDataSize == 0) {
            return new HashMap<>();
        }
        final Map<String, Object> ret = new HashMap<>(contextDataSize);
        String key;
        Object value;
        for (int i = 0; i < contextDataSize; i++) {
            key = (String) in.readObject();
            value = in.readObject();
            ret.put(key, value);
        }
        return ret;
    }

    static void serializeSet(final ObjectOutput out, final Set<EJBModuleIdentifier> modules) throws IOException {
        out.writeInt(modules.size());
        for (EJBModuleIdentifier ejbModuleIdentifier : modules) {
            out.writeObject(ejbModuleIdentifier);
        }
    }

    static Set<EJBModuleIdentifier> deserializeSet(final ObjectInput in) throws IOException, ClassNotFoundException {
        int size = in.readInt();
        Set<EJBModuleIdentifier> ret = new HashSet<>(size);
        for (int i = 0; i < size; i++) {
            ret.add((EJBModuleIdentifier) in.readObject());
        }
        return ret;
    }

    static void serializeTransaction(final ObjectOutput out, final TransactionInfo transaction) throws IOException {
        final byte transactionType = transaction.getType();
        out.writeByte(transactionType);
        if (transactionType == NULL_TRANSACTION) {
            return;
        }
        serializeXid(out, transaction.getXid());
        if (transactionType == REMOTE_TRANSACTION) {
            return;
        }
        if (transactionType == LOCAL_TRANSACTION) {
            out.writeInt(transaction.getRemainingTime());
        }
    }

    static TransactionInfo deserializeTransaction(final ObjectInput in) throws IOException {
        final int txnType = in.readByte();
        if (txnType == NULL_TRANSACTION) {
            return nullTransaction();
        } else if (txnType == REMOTE_TRANSACTION || txnType == LOCAL_TRANSACTION) {
            final Xid xid = deserializeXid(in);
            return txnType == REMOTE_TRANSACTION ? remoteTransaction(xid) : localTransaction(xid, in.readInt());
        }
        throw EjbHttpClientMessages.MESSAGES.invalidTransactionType(txnType);
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

}
