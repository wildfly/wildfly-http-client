/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022 Red Hat, Inc., and individual contributors
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

import org.jboss.ejb.server.Association;
import org.wildfly.httpclient.common.HttpServiceConfig;
import org.wildfly.transaction.client.LocalTransactionContext;

import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * HTTP service that handles EJB calls. Kept for backward compatibility reasons only.
 *
 * @author Stuart Douglas
 * @author Flavia Rainone
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 * @deprecated Use {@link HttpRemoteEjbService} instead.
 */
@Deprecated
public class EjbHttpService extends HttpRemoteEjbService {
    @Deprecated
    public EjbHttpService(Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext) {
        super(HttpServiceConfig.getInstance(), association, executorService, localTransactionContext, null);
    }

    @Deprecated
    public EjbHttpService(Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext,
                          Function<String, Boolean> classResolverFilter) {
        super(HttpServiceConfig.getInstance(), association, executorService, localTransactionContext, classResolverFilter);
    }

    @Deprecated
    public EjbHttpService(HttpServiceConfig httpServiceConfig, Association association, ExecutorService executorService, LocalTransactionContext localTransactionContext,
                          Function<String, Boolean> classResolverFilter) {
        super(httpServiceConfig, association, executorService, localTransactionContext, classResolverFilter);
    }
}
