package org.wildfly.httpclient.common;

import static io.undertow.util.Headers.AUTHORIZATION;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;
import io.undertow.client.ClientRequest;
import io.undertow.util.FlexBase64;

/**
 * Class for simple utils methods helping with client authentication tasks
 */
public final class ClientAuthUtils {
    private ClientAuthUtils() {} // only meant for static methods => never init


    public static void setupBasicAuth(ClientRequest request, URI uri) {
        AuthenticationContext context = AuthenticationContext.captureCurrent();
        AuthenticationConfiguration config = new AuthenticationContextConfigurationClient().getAuthenticationConfiguration(uri, context);
        Principal principal = new AuthenticationContextConfigurationClient().getPrincipal(config);
        PasswordCallback callback = new PasswordCallback("password", false);
        try {
            new AuthenticationContextConfigurationClient().getCallbackHandler(config).handle(new Callback[]{callback});
        } catch (IOException | UnsupportedCallbackException e) {
            return;
        }
        char[] password = callback.getPassword();
        String challenge = principal.getName() + ":" + new String(password);
        request.getRequestHeaders().put(AUTHORIZATION, "basic " + FlexBase64.encodeString(challenge.getBytes(StandardCharsets.UTF_8), false));
    }
}
