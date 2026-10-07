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

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

/**
 * Regression tests for per-transaction XA stickiness keying on {@link HttpTargetContext}.
 * <p>
 * A single {@code HttpTargetContext} is cached one-per-URI and is therefore shared by every
 * transaction that targets the same load balancer. Historically the sticky route/session was held
 * in shared fields, so two concurrent transactions clobbered each other and XA completion could be
 * driven against a pod that never prepared the branch. These tests lock the fix: stickiness is keyed
 * per transaction and one transaction can never overwrite or clear another's pin.
 *
 * @author Tomasz Adamski
 */
public class HttpTargetContextStickinessTestCase {

    private static HttpTargetContext newTargetContext() throws Exception {
        // The stickiness methods only touch the per-transaction map, never the connection pool,
        // so a context built with a null pool is sufficient for these tests.
        return new HttpTargetContext(null, false, new URI("http://localhost:7788/wildfly-services"));
    }

    @Test
    public void testConcurrentTransactionsDoNotClobber() throws Exception {
        final HttpTargetContext context = newTargetContext();

        final String keyA = "1:" + "globalA";
        final String keyB = "1:" + "globalB";

        context.setTransactionStickiness(keyA, "pod-a", "session-a");
        context.setTransactionStickiness(keyB, "pod-b", "session-b");

        Assert.assertEquals("pod-a", context.getTransactionStickyRoute(keyA));
        Assert.assertEquals("session-a", context.getTransactionStickySessionId(keyA));
        Assert.assertEquals("pod-b", context.getTransactionStickyRoute(keyB));
        Assert.assertEquals("session-b", context.getTransactionStickySessionId(keyB));
    }

    @Test
    public void testClearingOneTransactionLeavesOthersIntact() throws Exception {
        final HttpTargetContext context = newTargetContext();

        final String keyA = "1:globalA";
        final String keyB = "1:globalB";

        context.setTransactionStickiness(keyA, "pod-a", "session-a");
        context.setTransactionStickiness(keyB, "pod-b", "session-b");

        context.clearTransactionStickiness(keyA);

        Assert.assertNull("cleared transaction must have no pinned route", context.getTransactionStickyRoute(keyA));
        Assert.assertNull("cleared transaction must have no pinned session", context.getTransactionStickySessionId(keyA));
        Assert.assertEquals("sibling transaction must survive the clear", "pod-b", context.getTransactionStickyRoute(keyB));
        Assert.assertEquals("sibling transaction must survive the clear", "session-b", context.getTransactionStickySessionId(keyB));
    }

    @Test
    public void testUnknownAndNullKeysReturnNull() throws Exception {
        final HttpTargetContext context = newTargetContext();

        Assert.assertNull(context.getTransactionStickyRoute("never-pinned"));
        Assert.assertNull(context.getTransactionStickySessionId("never-pinned"));
        Assert.assertNull(context.getTransactionStickyRoute(null));
        Assert.assertNull(context.getTransactionStickySessionId(null));

        // Null-key writes and clears must be no-ops rather than blowing up.
        context.setTransactionStickiness(null, "pod", "session");
        context.clearTransactionStickiness(null);
    }

    /**
     * Reproduces the original failure mode: many transactions pinning and reading against a single
     * shared context in parallel. Each transaction must always read back exactly its own pin.
     */
    @Test
    public void testStickinessIsolatedUnderConcurrency() throws Exception {
        final HttpTargetContext context = newTargetContext();
        final int threads = 16;
        final int iterationsPerThread = 2000;

        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicReference<AssertionError> failure = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final String key = "1:global-" + t;
            final String route = "pod-" + t;
            final String session = "session-" + t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        context.setTransactionStickiness(key, route, session);
                        final String readRoute = context.getTransactionStickyRoute(key);
                        final String readSession = context.getTransactionStickySessionId(key);
                        if (!route.equals(readRoute) || !session.equals(readSession)) {
                            throw new AssertionError("expected " + route + "/" + session
                                    + " but read " + readRoute + "/" + readSession);
                        }
                    }
                } catch (AssertionError e) {
                    failure.compareAndSet(null, e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        Assert.assertTrue("threads did not finish in time", done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    // --- keying semantics: HttpStickinessHelper.stickinessKey ---
    // The key is derived from format id + global transaction id only. Callers deliberately pass the
    // global transaction id (never the branch qualifier), so every branch of one global transaction
    // resolves to the same key; that contract is exercised at the call sites, not here.

    @Test
    public void testStickinessKeyIsStableForSameIdentity() {
        final byte[] global = new byte[] {1, 2, 3, 4};

        Assert.assertEquals(HttpStickinessHelper.stickinessKey(1, global), HttpStickinessHelper.stickinessKey(1, global));
    }

    @Test
    public void testStickinessKeyDiffersForDifferentGlobalTransactions() {
        Assert.assertNotEquals(
                HttpStickinessHelper.stickinessKey(1, new byte[] {1, 2, 3}),
                HttpStickinessHelper.stickinessKey(1, new byte[] {4, 5, 6}));
    }

    @Test
    public void testStickinessKeyDiffersForDifferentFormatIds() {
        final byte[] global = new byte[] {1, 2, 3};

        Assert.assertNotEquals(
                HttpStickinessHelper.stickinessKey(1, global),
                HttpStickinessHelper.stickinessKey(2, global));
    }

    @Test
    public void testStickinessKeyOfNullGlobalIdIsNull() {
        Assert.assertNull(HttpStickinessHelper.stickinessKey(1, null));
    }
}
