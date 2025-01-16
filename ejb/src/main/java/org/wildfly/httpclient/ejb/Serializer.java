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

    static void serializeObject(final ObjectOutput output, final Object object) throws IOException {
        output.writeObject(object);
    }

    static Object deserializeObject(final ObjectInput input) throws IOException, ClassNotFoundException {
        return input.readObject();
    }

    static void serializeObjectArray(final ObjectOutput output, final Object[] objects) throws IOException {
        if (objects == null) return;
        for (final Object object : objects) {
            output.writeObject(object);
        }
    }

    static void deserializeObjectArray(final ObjectInput input, final Object[] objects) throws IOException, ClassNotFoundException {
        if (objects == null) return;
        for (int i = 0; i < objects.length; i++) {
            objects[i] = deserializeObject(input);
        }
    }

    static void serializePackedInteger(final ObjectOutput output, int value) throws IOException {
        if (value < 0)
            throw new IllegalArgumentException();
        if (value > 127) {
            output.writeByte(value & 0x7F | 0x80);
            serializePackedInteger(output, value >> 7);
        } else {
            output.writeByte(value & 0xFF);
        }
    }

    static int deserializePackedInteger(final ObjectInput input) throws IOException {
        int ret = input.readByte();
        if ((ret & 0x80) == 0x80) {
            return deserializePackedInteger(input) << 7 | (ret & 0x7F);
        }
        return ret;
    }

    static void serializeMap(final ObjectOutput output, final Map<String, Object> map) throws IOException {
        int size = map != null ? map.size() : 0;
        serializePackedInteger(output, size);
        if (size > 0) for (Map.Entry<String, Object> entry : map.entrySet()) {
            output.writeObject(entry.getKey());
            output.writeObject(entry.getValue());
        }
    }

    static Map<String, Object> deserializeMap(final ObjectInput input) throws IOException, ClassNotFoundException {
        final int contextDataSize = deserializePackedInteger(input);
        if (contextDataSize == 0) {
            return new HashMap<>();
        }
        final Map<String, Object> ret = new HashMap<>(contextDataSize);
        String key;
        Object value;
        for (int i = 0; i < contextDataSize; i++) {
            key = (String) input.readObject();
            value = input.readObject();
            ret.put(key, value);
        }
        return ret;
    }

    static void serializeSet(final ObjectOutput output, final Set<EJBModuleIdentifier> set) throws IOException {
        output.writeInt(set.size());
        for (EJBModuleIdentifier moduleId : set) {
            output.writeObject(moduleId);
        }
    }

    static Set<EJBModuleIdentifier> deserializeSet(final ObjectInput input) throws IOException, ClassNotFoundException {
        int size = input.readInt();
        Set<EJBModuleIdentifier> ret = new HashSet<>(size);
        for (int i = 0; i < size; i++) {
            ret.add((EJBModuleIdentifier) input.readObject());
        }
        return ret;
    }

    static void serializeTransaction(final ObjectOutput output, final TransactionInfo txn) throws IOException {
        final byte transactionType = txn.getType();
        output.writeByte(transactionType);
        if (transactionType == NULL_TRANSACTION) {
            return;
        }
        serializeXid(output, txn.getXid());
        if (transactionType == REMOTE_TRANSACTION) {
            return;
        }
        if (transactionType == LOCAL_TRANSACTION) {
            output.writeInt(txn.getRemainingTime());
        }
    }

    static TransactionInfo deserializeTransaction(final ObjectInput input) throws IOException {
        final int txnType = input.readByte();
        if (txnType == NULL_TRANSACTION) {
            return nullTransaction();
        } else if (txnType == REMOTE_TRANSACTION || txnType == LOCAL_TRANSACTION) {
            final Xid xid = deserializeXid(input);
            return txnType == REMOTE_TRANSACTION ? remoteTransaction(xid) : localTransaction(xid, input.readInt());
        }
        throw EjbHttpClientMessages.MESSAGES.invalidTransactionType(txnType);
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

}
