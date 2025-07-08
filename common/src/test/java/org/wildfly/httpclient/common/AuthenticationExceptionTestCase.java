package org.wildfly.httpclient.common;

import static io.undertow.util.Headers.CONTENT_TYPE;
import static org.wildfly.httpclient.common.HeadersHelper.addResponseHeader;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.naming.AuthenticationException;

import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.security.auth.client.AuthenticationConfiguration;

/**
 * Client should throw AuthenticationException rather than general IOException when authentication fails.
 *
 * Legacy behavior can be enforced by setting system property "org.wildfly.httpclient.io-exception-on-failed-auth"
 * to "true".
 */
@SuppressWarnings({"Convert2Lambda", "Anonymous2MethodRef"})
@RunWith(HTTPTestServer.class)
public class AuthenticationExceptionTestCase {

    @Before
    public void setUp() {
        HTTPTestServer.registerPathHandler("/", new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) {
                // pretend authentication failure
                exchange.setStatusCode(401);
                addResponseHeader(exchange, CONTENT_TYPE, "text/html");
            }
        });
    }

    @Test
    public void testAuthenticationExceptionOnAuthenticationFailure() throws URISyntaxException {
        System.clearProperty("org.wildfly.httpclient.io-exception-on-failed-auth");
        assertRequestException(AuthenticationException.class);
    }

    @Test
    public void testLegacyGeneralExceptionOnAuthenticationFailure() throws URISyntaxException {
        System.setProperty("org.wildfly.httpclient.io-exception-on-failed-auth", "true");
        assertRequestException(IOException.class);
    }

    private void assertRequestException(Class<? extends Exception> exceptionType) throws URISyntaxException {
        ClientRequest request = new ClientRequest().setMethod(Methods.GET).setPath("/");
        CompletableFuture<ClientResponse> responseFuture = doClientRequest(request);

        try {
            responseFuture.join();
            Assert.fail("CompletionException is expected");
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            Assert.assertNotNull(cause);
            String expectedTypeMessage = String.format("Expected %s, got %s",
                    exceptionType.getSimpleName(),
                    cause.getClass().getSimpleName());
            Assert.assertEquals(expectedTypeMessage, cause.getClass(), exceptionType);
        }
    }

    private CompletableFuture<ClientResponse> doClientRequest(ClientRequest request) throws URISyntaxException {
        ClientAuthUtils.setupBasicAuth(request, new URI(HTTPTestServer.getDefaultServerURL() + request.getPath()));

        CompletableFuture<ClientResponse> responseFuture = new CompletableFuture<>();
        HttpTargetContext context = WildflyHttpContext.getCurrent().getTargetContext(new URI(HTTPTestServer.getDefaultServerURL()));
        context.sendRequest(request, null, AuthenticationConfiguration.empty(), null,
                new HttpTargetContext.HttpBodyDecoder() {
                    @Override
                    public void decode(InputStream result, ClientResponse response) {
                        responseFuture.complete(response);
                    }
                }, new HttpTargetContext.HttpFailureHandler() {
                    @Override
                    public void handleFailure(Throwable throwable) {
                        responseFuture.completeExceptionally(throwable);
                    }
                },
                null, null, true);
        return responseFuture;
    }

}
