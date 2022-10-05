/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
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

import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import javax.transaction.xa.XAException;

import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.Field;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;

/**
 * @author Stuart Douglas
 */
@MessageLogger(projectCode = "TXNWFHTTP")
public interface HttpRemoteTransactionMessages extends BasicLogger {

    HttpRemoteTransactionMessages MESSAGES = Logger.getMessageLogger(HttpRemoteTransactionMessages.class, HttpRemoteTransactionMessages.class.getPackage().getName());

    @Message(id = 1, value = "The protocol operation was interrupted locally")
    SystemException operationInterrupted();

    @Message(id = 2, value = "Rollback-only transaction rolled back")
    RollbackException rollbackOnlyRollback();

    @Message(id = 3, value = "Invalid transaction state")
    IllegalStateException invalidTxnState();

    @Message(id = 4, value = "Transaction operation failed due to thread interruption")
    XAException interruptedXA(@Field int errorCode);
}
