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

import static io.undertow.util.Methods.DELETE;
import static io.undertow.util.Methods.POST;
import static org.wildfly.httpclient.ejb.EjbConstants.EJB_CANCEL_PATH;
import static org.wildfly.httpclient.ejb.EjbConstants.EJB_EXCEPTION;
import static org.wildfly.httpclient.ejb.EjbConstants.EJB_INVOKE_PATH;
import static org.wildfly.httpclient.ejb.EjbConstants.EJB_OPEN_PATH;
import static org.wildfly.httpclient.ejb.EjbConstants.INVOCATION_ACCEPT;
import static org.wildfly.httpclient.ejb.EjbConstants.INVOCATION_ID;
import static org.wildfly.httpclient.ejb.EjbConstants.INVOCATION;
import static org.wildfly.httpclient.ejb.EjbConstants.SESSION_OPEN;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.net.URLEncoder.encode;

import io.undertow.client.ClientRequest;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.wildfly.httpclient.common.Protocol;

import java.lang.reflect.Method;

/**
 * Builder for invocations against a specific EJB, such as invocation and session open
 *
 * @author Stuart Douglas
 */
final class HttpEJBInvocationBuilder {

    private String appName;
    private String moduleName;
    private String distinctName;
    private String beanName;
    private String beanId;
    private String view;
    private Method method;
    private InvocationType invocationType;
    private String invocationId;
    private int version = Protocol.LATEST;
    private boolean cancelIfRunning;

    // setters

    HttpEJBInvocationBuilder setAppName(final String appName) {
        this.appName = appName;
        return this;
    }

    HttpEJBInvocationBuilder setModuleName(final String moduleName) {
        this.moduleName = moduleName;
        return this;
    }

    HttpEJBInvocationBuilder setDistinctName(final String distinctName) {
        this.distinctName = distinctName;
        return this;
    }

    HttpEJBInvocationBuilder setBeanName(final String beanName) {
        this.beanName = beanName;
        return this;
    }

    HttpEJBInvocationBuilder setBeanId(final String beanId) {
        this.beanId = beanId;
        return this;
    }

    HttpEJBInvocationBuilder setMethod(final Method method) {
        this.method = method;
        return this;
    }

    HttpEJBInvocationBuilder setView(final String view) {
        this.view = view;
        return this;
    }

    HttpEJBInvocationBuilder setInvocationType(final InvocationType invocationType) {
        this.invocationType = invocationType;
        return this;
    }

    HttpEJBInvocationBuilder setInvocationId(final String invocationId) {
        this.invocationId = invocationId;
        return this;
    }

    HttpEJBInvocationBuilder setVersion(final int version) {
        this.version = version;
        return this;
    }

    HttpEJBInvocationBuilder setCancelIfRunning(final boolean cancelIfRunning) {
        this.cancelIfRunning = cancelIfRunning;
        return this;
    }

    enum InvocationType {
        METHOD_INVOCATION,
        STATEFUL_CREATE,
        CANCEL,
    }

    // helper methods

    ClientRequest createRequest(final String prefix) {
        final ClientRequest clientRequest = new ClientRequest();
        clientRequest.setMethod(getBeanRequestMethod());
        clientRequest.setPath(getBeanRequestPath(prefix));
        if (invocationType == InvocationType.METHOD_INVOCATION) {
            clientRequest.getRequestHeaders().add(Headers.ACCEPT, INVOCATION_ACCEPT + "," + EJB_EXCEPTION);
            if (invocationId != null) {
                clientRequest.getRequestHeaders().put(INVOCATION_ID, invocationId);
            }
            clientRequest.getRequestHeaders().put(Headers.CONTENT_TYPE, INVOCATION.toString());
        } else if (invocationType == InvocationType.STATEFUL_CREATE) {
            clientRequest.getRequestHeaders().put(Headers.CONTENT_TYPE, SESSION_OPEN.toString());
            clientRequest.getRequestHeaders().add(Headers.ACCEPT, EJB_EXCEPTION.toString());
        }
        return clientRequest;
    }

    private HttpString getBeanRequestMethod() {
        if (invocationType == InvocationType.METHOD_INVOCATION) return POST;
        if (invocationType == InvocationType.STATEFUL_CREATE) return POST;
        if (invocationType == InvocationType.CANCEL) return DELETE;
        throw new IllegalStateException();
    }

    private String getBeanRequestPath(final String prefix) {
        if (invocationType == InvocationType.METHOD_INVOCATION) return invokeBeanRequestPath(prefix);
        if (invocationType == InvocationType.STATEFUL_CREATE) return openBeanRequestPath(prefix);
        if (invocationType == InvocationType.CANCEL) return cancelBeanRequestPath(prefix);
        throw new IllegalStateException();
    }

    private String openBeanRequestPath(final String mountPoint) {
        final StringBuilder sb = new StringBuilder();
        appendBeanPath(sb, mountPoint, EJB_OPEN_PATH);
        return sb.toString();
    }

    private String cancelBeanRequestPath(final String mountPoint) {
        final StringBuilder sb = new StringBuilder();
        appendBeanPath(sb, mountPoint, EJB_CANCEL_PATH);
        appendPath(sb, invocationId, false);
        appendPath(sb, "" + cancelIfRunning, false); // TODO: convert to String
        return sb.toString();
    }

    private String invokeBeanRequestPath(final String mountPoint) {
        final StringBuilder sb = new StringBuilder();
        appendBeanPath(sb, mountPoint, EJB_INVOKE_PATH);
        appendPath(sb, beanId, false);
        appendPath(sb, view, false);
        appendPath(sb, method.getName(), false); // TODO: convert to String
        for (final Class<?> param : method.getParameterTypes()) {
            appendPath(sb, param.getName(), true); // TODO: convert to Strings
        }
        return sb.toString();
    }

    private void appendBeanPath(final StringBuilder sb, final String mountPoint, final String operationType) {
        if (mountPoint != null) {
            sb.append(mountPoint);
        }
        appendPath(sb, "ejb", false);
        appendPath(sb, "v" + version, false); // TODO: convert to String
        appendPath(sb, operationType, false);
        appendPath(sb, appName, true);
        appendPath(sb, moduleName, true);
        appendPath(sb, distinctName, true);
        appendPath(sb, beanName, true);
    }

    private static void appendPath(final StringBuilder sb, final String path, final boolean encode) {
        sb.append("/").append(path == null || path.isEmpty() ? "-" : encode ? encode(path, UTF_8) : path);
    }

}
