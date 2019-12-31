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

package org.wildfly.httpclient.ejb;

import java.io.IOException;
import javax.ejb.EJBException;

import org.jboss.ejb.client.EJBLocator;
import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.Cause;
import org.jboss.logging.annotations.LogMessage;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;

@MessageLogger(projectCode = "WFHTTPEJB")
interface EjbHttpClientMessages extends BasicLogger {

    EjbHttpClientMessages MESSAGES = Logger.getMessageLogger(EjbHttpClientMessages.class, EjbHttpClientMessages.class.getPackage().getName());

    @Message(id = 1, value = "Unexpected data in response")
    IOException unexpectedDataInResponse();

    @Message(id = 2, value = "No session id in response")
    IOException noSessionIdInResponse();

    @Message(id = 3, value = "Could not resolve target for locator %s")
    IllegalStateException couldNotResolveTargetForLocator(EJBLocator locator);

    @Message(id = 4, value = "Could create HTTP EJBReceiver for protocol %s")
    IllegalArgumentException couldNotCreateHttpEjbReceiverFor(String s);

    // id = 5

    @Message(id = 6, value = "EJB not stateful")
    IllegalArgumentException notStateful();

    @Message(id = 7, value = "Session was not active")
    IllegalArgumentException sessionNotActive();

    @Message(id = 8, value = "Session was not active")
    IllegalArgumentException noSuchMethod();

    @Message(id = 9, value = "Wrong view type")
    EJBException wrongViewType();

    @Message(id = 10, value = "Cannot enlist in transaction")
    IllegalStateException cannotEnlistTx();

    @Message(id = 11, value = "Invalid transaction type %s")
    IOException invalidTransactionType(int type);

    @LogMessage(level = Logger.Level.ERROR)
    @Message(id = 12, value = "Unable to perform EJB discovery")
    void unableToPerformEjbDiscovery(@Cause Throwable e);
}
