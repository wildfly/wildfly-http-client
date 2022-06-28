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

package org.wildfly.httpclient.common;

import java.io.IOException;
import javax.naming.AuthenticationException;

import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.Cause;
import org.jboss.logging.annotations.LogMessage;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;
import org.wildfly.client.config.ConfigXMLParseException;
import io.undertow.client.ClientResponse;

@MessageLogger(projectCode = "WFHTTP")
interface HttpClientMessages extends BasicLogger {

    HttpClientMessages MESSAGES = Logger.getMessageLogger(HttpClientMessages.class, HttpClientMessages.class.getPackage().getName());

    @Message(id = 2, value = "Port value %s out of range")
    ConfigXMLParseException portValueOutOfRange(int port);

    @Message(id = 3, value = "Failed to acquire session")
    @LogMessage(level = Logger.Level.ERROR)
    void failedToAcquireSession(@Cause Throwable t);

    @Message(id = 4, value = "Invalid response type %s")
    IOException invalidResponseType(ContentType type);

    @Message(id = 5, value = "Invalid response code %s (full response %s)")
    IOException invalidResponseCode(int responseCode, ClientResponse response);

    @LogMessage(level = Logger.Level.ERROR)
    @Message(id = 6, value = "Failed to write exception")
    void failedToWriteException(@Cause Exception ex);

    @Message(id = 7, value = "Invalid content encoding %s")
    IOException invalidContentEncoding(String encoding);

    @Message(id = 8, value = "Authentication failed")
    SecurityException authenticationFailed();

    @Message(id = 9, value = "Unsupported qop version in digest auth")
    RuntimeException unsupportedQopInDigest();

    @LogMessage(level = Logger.Level.ERROR)
    @Message(id = 10, value = "Error parsing authentication-info header")
    void failedToParseAuthenticationInfo(@Cause Exception e);

    @Message(id = 11, value = "Stream is closed")
    IOException streamIsClosed();

    @Message(id = 12, value = "Attempted to do blocking IO from the IO thread. This is prohibited as it may result in deadlocks")
    IllegalStateException blockingIoFromIOThread();

    @Message(id = 13, value = "Authentication failed (full response %s)")
    AuthenticationException authenticationFailed(ClientResponse response);

    @LogMessage(level = Logger.Level.DEBUG)
    @Message(id = 14, value = "JavaEE to JakartaEE backward compatibility layer have been installed")
    void javaeeToJakartaeeBackwardCompatibilityLayerInstalled();

}
