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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.server.Association;
import org.jboss.ejb.server.CancelHandle;
import org.jboss.ejb.server.ClusterTopologyListener;
import org.jboss.ejb.server.InvocationRequest;
import org.jboss.ejb.server.ListenerHandle;
import org.jboss.ejb.server.ModuleAvailabilityListener;
import org.jboss.ejb.server.SessionOpenRequest;
import org.junit.runners.model.InitializationError;
import org.wildfly.common.annotation.NotNull;
import org.wildfly.httpclient.common.HTTPTestServer;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.server.handlers.PathHandler;

/**
 * @author Stuart Douglas
 */
public class EJBTestServer extends HTTPTestServer {

    /*
     * Reject unmarshalling an instance of IAE, as a kind of 'blocklist'.
     * In normal tests this type would never be sent, which is analogous to
     * how blocklisted classes are normally not sent. And then we can
     * deliberately send an IAE in tests to confirm it is rejected.
     */
    private static final Function<String, Boolean> DEFAULT_CLASS_FILTER = cName -> !cName.equals(IllegalArgumentException.class.getName());


    private static volatile TestEJBHandler handler;

    public EJBTestServer(Class<?> klass) throws InitializationError {
        super(klass);
    }

    public static TestEJBHandler getHandler() {
        return handler;
    }

    public static void setHandler(TestEJBHandler handler) {
        EJBTestServer.handler = handler;
    }

    @Override
    protected void registerPaths(PathHandler servicesHandler) {
        servicesHandler.addPrefixPath("/ejb", new EjbHttpService(new Association() {
            @Override
            public <T> CancelHandle receiveInvocationRequest(@NotNull InvocationRequest invocationRequest) {
                TestCancelHandle handle = new TestCancelHandle();
                try {
                    InvocationRequest.Resolved request = invocationRequest.getRequestContent(getClass().getClassLoader());
                    ServerHandlers.HttpInvocationHandler.ResolvedInvocation resolvedInvocation = (ServerHandlers.HttpInvocationHandler.ResolvedInvocation) request;
                    TestEjbOutput out = new TestEjbOutput();
                    getWorker().execute(() -> {
                        try {
                            Object result = handler.handle(request, resolvedInvocation.getSessionAffinity(), out, invocationRequest.getMethodLocator(), handle, resolvedInvocation.getAttachments());
                            if (out.getSessionAffinity() != null) {
                                resolvedInvocation.getExchange().setResponseCookie(new CookieImpl("JSESSIONID", out.getSessionAffinity()));
                            }
                            request.writeInvocationResult(result);
                        } catch (Exception e) {
                            invocationRequest.writeException(e);
                        }
                    });
                } catch (Exception e) {
                    invocationRequest.writeException(e);
                }
                return handle;
            }

            @Override
            public CancelHandle receiveSessionOpenRequest(@NotNull SessionOpenRequest sessionOpenRequest) {
                sessionOpenRequest.convertToStateful(SessionID.createSessionID("SFSB_ID".getBytes(StandardCharsets.UTF_8)));
                return null;
            }

            @Override
            public ListenerHandle registerClusterTopologyListener(@NotNull ClusterTopologyListener clusterTopologyListener) {
                return null;
            }

            @Override
            public ListenerHandle registerModuleAvailabilityListener(@NotNull ModuleAvailabilityListener moduleAvailabilityListener) {
                return null;
            }
        }, null, null, DEFAULT_CLASS_FILTER).createHttpHandler());

    }

    public static class TestCancelHandle implements CancelHandle {

        private final LinkedBlockingDeque<Boolean> resultQueue = new LinkedBlockingDeque<>();

        @Override
        public void cancel(boolean aggressiveCancelRequested) {
            resultQueue.add(aggressiveCancelRequested);
        }

        public Boolean awaitResult() {
            try {
                return resultQueue.poll(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
