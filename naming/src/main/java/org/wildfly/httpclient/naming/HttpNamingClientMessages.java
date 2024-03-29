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

package org.wildfly.httpclient.naming;

import java.io.InvalidClassException;
import javax.naming.NamingException;

import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;

@MessageLogger(projectCode = "JNDIWFHTTP")
interface HttpNamingClientMessages extends BasicLogger {

    HttpNamingClientMessages MESSAGES = Logger.getMessageLogger(HttpNamingClientMessages.class, HttpNamingClientMessages.class.getPackage().getName());

    @Message(id = 1, value = "Unexpected data in response")
    NamingException unexpectedDataInResponse();

    @Message(id = 2, value = "At least one URI must be provided")
    NamingException atLeastOneUri();

    @Message(id = 3, value = "Exception resolving class %s for unmarshalling; it has either been blocklisted or not allowlisted")
    InvalidClassException cannotResolveFilteredClass(String clazz);
}
