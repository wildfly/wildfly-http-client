package org.wildfly.httpclient.common;

import static io.undertow.util.Headers.HOST;
import static org.wildfly.httpclient.common.HeadersHelper.getRequestHeader;
import static org.wildfly.httpclient.common.HeadersHelper.putRequestHeader;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.httpclient.common.HttpTargetContext.ResponseContext;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import io.undertow.client.ClientRequest;
import io.undertow.util.Methods;

@RunWith(HTTPTestServer.class)
public class ClientHostHeaderTestCase {

    private static final Logger log = Logger.getLogger(ClientHostHeaderTestCase.class.getName());

    @Test
    public void hostHeaderIncludesPortTest() throws URISyntaxException, InterruptedException {
        final List<String> hosts = new ArrayList<>();
        String path = "/host";
        HTTPTestServer.registerPathHandler(path, exchange -> hosts.add(getRequestHeader(exchange, HOST)));
        ClientRequest request = new ClientRequest().setMethod(Methods.GET).setPath(path);
        doClientRequest(request);

        Assertions.assertThat(hosts)
                .as("Check Host header includes also port")
                .containsExactly(HTTPTestServer.getHostAddress() + ":" + HTTPTestServer.getHostPort());
    }

    @Test
    public void hostHeaderIsNotOverridenIfProvided() throws URISyntaxException, InterruptedException {
        final List<String> hosts = new ArrayList<>();
        String path = "/host";
        HTTPTestServer.registerPathHandler(path, exchange -> hosts.add(getRequestHeader(exchange, HOST)));
        String myHostHeader = "127.0.0.2";
        ClientRequest request = new ClientRequest().setMethod(Methods.GET).setPath(path);
        putRequestHeader(request, HOST, myHostHeader);
        doClientRequest(request);

        Assertions.assertThat(hosts)
                .as("Check Host header includes also port")
                .containsExactly(myHostHeader);
    }


    private void doClientRequest(ClientRequest request) throws URISyntaxException, InterruptedException {
        ClientAuthUtils.setupBasicAuth(request, new URI(HTTPTestServer.getDefaultServerURL() + request.getPath()));

        CountDownLatch latch = new CountDownLatch(1);
        HttpTargetContext context = WildflyHttpContext.getCurrent().getTargetContext(new URI(HTTPTestServer.getDefaultServerURL()));
        context.sendRequest(request, null, AuthenticationConfiguration.empty(), null,
                new HttpTargetContext.HttpBodyDecoder() {
                    @Override
                    public void decode(ResponseContext ctx) {
                        latch.countDown();
                    }
                }, new HttpTargetContext.HttpFailureHandler() {
                    @Override
                    public void handleFailure(Throwable throwable) {
                        log.log(Level.SEVERE, "Request handling failed with exception", throwable);
                        latch.countDown();
                    }
                },
                null, null, true);
        latch.await(10, TimeUnit.SECONDS);
    }

}
