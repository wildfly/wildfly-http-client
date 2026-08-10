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

import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.Cookies;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Helper methods for processing JSESSIONID Cookies and Headers used in stickiness processing.
 *
 * @author <a href="mailto@rachmato@redhat.com>Richard Achmatowicz</a>
 */
public class HttpStickinessHelper {

    private static final HttpString STRICT_STICKINESS_HOST = new HttpString("StrictStickinessHost");
    private static final HttpString STRICT_STICKINESS_RESULT = new HttpString("StrictStickinessResult");
    private static final HttpString JSESSIONID_COOKIE_NAME = new HttpString("JSESSIONID");

    private static final RoutingSupport routingSupport = new SimpleRoutingSupport();

    private HttpStickinessHelper() {

    }

    // Affinity helper methods

    public static URI createURIAffinityValue(String host) throws URISyntaxException {
        URI uriAffinityValue = new URI(null, host, null, null) ;
        return uriAffinityValue;
    }

    // Cookie helper methods

    public static void addEncodedSessionID(ClientRequest request, String sessionID, String route) {
        CharSequence encodedSessionID = routingSupport.format(sessionID, route);
        String cookieValue = JSESSIONID_COOKIE_NAME + "=" + encodedSessionID.toString();
        request.getRequestHeaders().put(Headers.COOKIE, cookieValue);
    }

    public static boolean hasEncodedSessionID(ClientResponse response) {
        boolean hasCookie = false;
        HeaderValues cookies = response.getResponseHeaders().get(Headers.SET_COOKIE);
        if (cookies != null) {
            for (String cookie : cookies) {
                Cookie c = Cookies.parseSetCookieHeader(cookie);
                if (c.getName().equals("JSESSIONID")) {
                    hasCookie = true;
                }
            }
        }
        return hasCookie;
    }

    public static String getEncodedSessionID(ClientResponse response) {
        String encodedSessionID = null;
        HeaderValues cookies = response.getResponseHeaders().get(Headers.SET_COOKIE);
        if (cookies != null) {
            for (String cookie : cookies) {
                Cookie c = Cookies.parseSetCookieHeader(cookie);
                if (c.getName().equals("JSESSIONID")) {
                    encodedSessionID = c.getValue();
                }
            }
        }
        return encodedSessionID;
    }

    public static String extractSessionIDFromEncodedSessionID(String encodedSessionID) {
        Map.Entry<CharSequence, CharSequence> parsedSessionID = routingSupport.parse(encodedSessionID);
        String sessionID = parsedSessionID.getKey().toString();

        HttpClientMessages.MESSAGES.infof("HttpStickinessHelper: encodedSessionID = %s, sessionID = %s", encodedSessionID, sessionID);
        return sessionID;
    }

    public static String extractRouteFromEncodedSessionID(String encodedSessionID) {
        Map.Entry<CharSequence, CharSequence> parsedSessionID = routingSupport.parse(encodedSessionID);
        CharSequence routeValue = parsedSessionID.getValue();
        String route = routeValue != null ? routeValue.toString() : null;

        HttpClientMessages.MESSAGES.infof("HttpStickinessHelper: encodedSessionID = %s, route = %s", encodedSessionID, route);
        return route;
    }

    public static boolean hasEncodedSessionID(HttpServerExchange exchange) {
        Cookie cookie = exchange.getRequestCookies().get(JSESSIONID_COOKIE_NAME.toString());
        return cookie != null;
    }

    public static String getEncodedSessionID(HttpServerExchange exchange) {
        Cookie cookie = exchange.getRequestCookies().get(JSESSIONID_COOKIE_NAME.toString());
        if (cookie != null) {
            return cookie.getValue();
        }
        return null;
    }

    public static void addEncodedSessionID(HttpServerExchange exchange, String sessionID, String route) {
        CharSequence encodedSessionID = routingSupport.format(sessionID, route);
        exchange.setResponseCookie(new CookieImpl(JSESSIONID_COOKIE_NAME.toString(), encodedSessionID.toString()));
    }

    public static void addUnencodedSessionID(HttpServerExchange exchange, String unencodedSessionID) {
        exchange.setResponseCookie(new CookieImpl(JSESSIONID_COOKIE_NAME.toString(), unencodedSessionID));
    }

    // Header operations

    public static void addStrictStickinessHost(ClientRequest request, String host) {
        request.getRequestHeaders().put(STRICT_STICKINESS_HOST, host);
    }

    public static boolean hasStrictStickinessHost(ClientResponse response) throws Exception {
        HeaderValues strictStickinessHosts = response.getResponseHeaders().get(STRICT_STICKINESS_HOST);
        return strictStickinessHosts != null && strictStickinessHosts.size() > 0;
    }

    public static String getStrictStickinessHost(ClientResponse response) throws Exception {
        String strictStickinessHost = null;
        HeaderValues strictStickinessHosts = response.getResponseHeaders().get(STRICT_STICKINESS_HOST);
        if (strictStickinessHosts != null && strictStickinessHosts.size() > 0) {
            strictStickinessHost = strictStickinessHosts.getFirst();
            if(strictStickinessHost == null) {
                throw new Exception("Stickiness host is null - this should not happen");
            }
        }
        return strictStickinessHost;
    }

    public static void addStrictStickinessResult(ClientRequest request, String result) {
        request.getRequestHeaders().put(STRICT_STICKINESS_RESULT, result);
    }

    public static boolean hasStrictStickinessResult(ClientResponse response) throws Exception {
        HeaderValues strictStickinessResults = response.getResponseHeaders().get(STRICT_STICKINESS_RESULT);
        return strictStickinessResults != null && strictStickinessResults.size() > 0;
    }

    public static boolean getStrictStickinessResult(ClientResponse response) throws Exception {
        boolean isSticky = false;
        String strictStickinessResult = null;
        HeaderValues strictStickinessResults = response.getResponseHeaders().get(STRICT_STICKINESS_RESULT);
        if (strictStickinessResults != null && strictStickinessResults.size() > 0) {
            strictStickinessResult = strictStickinessResults.getFirst();
            if(!strictStickinessResult.equals("success")) {
                throw new Exception("Stickiness result indicates failure - we failed over when we should not have failed over");
            }
            isSticky = true;
        }
        return isSticky;
    }

    public static void addStrictStickinessHost(HttpServerExchange exchange, String host) {
        exchange.getResponseHeaders().put(STRICT_STICKINESS_HOST, host);
    }

    public static boolean hasStrictStickinessHost(HttpServerExchange exchange) throws Exception {
        HeaderValues strictStickinessHosts = exchange.getRequestHeaders().get(STRICT_STICKINESS_HOST);
        return strictStickinessHosts != null && strictStickinessHosts.size() > 0;
    }

    public static String getStrictStickinessHost(HttpServerExchange exchange) throws Exception {
        String strictStickinessHost = null;
        HeaderValues strictStickinessHosts = exchange.getRequestHeaders().get(STRICT_STICKINESS_HOST);
        if (strictStickinessHosts != null && strictStickinessHosts.size() > 0) {
            strictStickinessHost = strictStickinessHosts.getFirst();
            if (strictStickinessHost == null) {
                throw new Exception("Stickiness host is null - this should not happen");
            }
        }
        return strictStickinessHost;
    }

    public static void addStrictStickinessResult(HttpServerExchange exchange, String result) {
        exchange.getResponseHeaders().put(STRICT_STICKINESS_RESULT, result);
    }

    // map of node to sessionID

    public static String updateNode2SessionIDMap(ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionIdMap, URI uri, ClientResponse response) {
        String encodedSessionID = getEncodedSessionID(response);
        String sessionID = extractSessionIDFromEncodedSessionID(encodedSessionID);
        String route = extractRouteFromEncodedSessionID(encodedSessionID);

        String oldSessionID = addSessionIDForNode(node2SessionIdMap, uri, route, sessionID);

        if (oldSessionID != null) {
            HttpClientMessages.MESSAGES.infof("HttpStickinessHandler:updateNode2SessionIDMap uri = %s, node = %s, oldSessionID = %s, sessionId = %s", uri, route, oldSessionID, sessionID);
        } else {
            HttpClientMessages.MESSAGES.infof("HttpStickinessHandler:updateNode2SessionIDMap uri = %s, node = %s, sessionId = %s", uri, route, sessionID);
        }

        return route;
    }

    public static String addSessionIDForNode(ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionIdMap, URI uri, String node, String sessionID) {
        ConcurrentMap<String, String> map = node2SessionIdMap.get(uri);
        if (map == null) {
            map = new ConcurrentHashMap<String, String>();
            node2SessionIdMap.put(uri, map);
        }
        String oldSessionID = map.put(node, sessionID);
        if (oldSessionID != null) {
            HttpClientMessages.MESSAGES.infof("HttpStickinessHelper:addSessionIDForNode() sessionID %s for node %s has been replaced by %s for URI %s", oldSessionID, node, sessionID, uri);
        }
        return oldSessionID;
    }

    public static String getSessionIDForNode(ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionIdMap, URI uri, String node) {
        ConcurrentMap<String, String> map = node2SessionIdMap.get(uri);
        if (map == null) {
            return null;
        }
        return map.get(node);
    }

    public static boolean hasSessionIDForNode(ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionIdMap, URI uri, String node) {
        return getSessionIDForNode(node2SessionIdMap, uri, node) != null;
    }

    public static void dumpResponseHeaders(ClientResponse response) {
        HeaderMap headers = response.getResponseHeaders();
        HttpClientMessages.MESSAGES.infof("HttpStickinessHelper: dump response headers = %s", headers.toString());
    }

    public static void dumpRequestCookies(HttpServerExchange exchange) {
        Map<String, Cookie> cookieMap = exchange.getRequestCookies();
        HttpClientMessages.MESSAGES.infof("HttpStickinessHelper: dump request Cookies:");
        for(Map.Entry entry : cookieMap.entrySet()) {
            String cookieKey = (String) entry.getKey();
            Cookie cookieValue = (Cookie) entry.getValue();
            HttpClientMessages.MESSAGES.infof("HttpStickinessHelper: name = %s, Cookie = %s, value = %s",cookieKey, cookieValue, cookieValue.getValue());
        }
    }

    public static void dumpRequestHeaders(HttpServerExchange exchange) {
        HeaderMap headerMap = exchange.getRequestHeaders();
        HttpClientMessages.MESSAGES.infof("HttpStickinessHelper: dump request headers = %s", headerMap.toString());
    }

}
