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


    /*
     * Create a URI value to represent a fixed host for strict stickiness
     */
    public static URI createURIAffinityValue(String host) throws URISyntaxException {
        URI uriAffinityValue = new URI(null, host, null, null) ;
        return uriAffinityValue;
    }

    // Cookie helper methods

    /*
     * Add a Cookie with key JSESSIONID and value an encoded sessionID (sessionID + "." + route).
     */
    public static void addEncodedSessionID(ClientRequest request, String sessionID, String route) {
        CharSequence encodedSessionID = routingSupport.format(sessionID, route);
        String cookieValue = JSESSIONID_COOKIE_NAME + "=" + encodedSessionID.toString();
        request.getRequestHeaders().put(Headers.COOKIE, cookieValue);
    }

    /*
     * Check if the response has a Cookie with key JSESSIONID
     */
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

    /*
     * Extract the encoded sessionID (sessionID + "." + route) from the ClientResponse Cookie called JSESSIONID
     */
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

    /*
     * Extract the HTTP sessionID from the encoded sessionID
     */
    public static String extractSessionIDFromEncodedSessionID(String encodedSessionID) {
        // extract route from Cookie (Cookie may not be present if SLSB)
        Map.Entry<CharSequence, CharSequence> parsedSessionID = routingSupport.parse(encodedSessionID);
        String sessionID = parsedSessionID.getKey().toString();

        HttpClientMessages.MESSAGES.infof("HttpStickinessHelper: encodedSessionID = %s, route = %s", encodedSessionID, sessionID);
        return sessionID;
    }

    /*
     * Extract the route from the encoded sessionID
     */
    public static String extractRouteFromEncodedSessionID(String encodedSessionID) {
        // extract route from Cookie (Cookie may not be present if SLSB)
        Map.Entry<CharSequence, CharSequence> parsedSessionID = routingSupport.parse(encodedSessionID);
        String route = parsedSessionID.getValue().toString();

        HttpClientMessages.MESSAGES.infof("HttpStickinessHelper: encodedSessionID = %s, route = %s", encodedSessionID, route);
        return route;
    }

    /*
     * Check if the HttpServerExchange has an encoded request Cookie with key JSESSIONID
     */
    public static boolean hasEncodedSessionID(HttpServerExchange exchange) {
        boolean hasCookie = false;
        Cookie cookie = exchange.getRequestCookies().get(JSESSIONID_COOKIE_NAME.toString());
        if (cookie != null) {
            hasCookie = true;
        }
        return hasCookie;
    }

    /*
     * Check if the HttpServerExchange has an encoded request Cookie with key JSESSIONID
     */
    public static String getEncodedSessionID(HttpServerExchange exchange) {
        Cookie cookie = exchange.getRequestCookies().get(JSESSIONID_COOKIE_NAME.toString());
        if (cookie != null) {
            return cookie.getValue();
        }
        return null;
    }

    /*
     * Add a Cookie with key JSESSIONID and value an encoded sessionID (sessionID + "." + route).to the exchange.
     */
    public static void addUnencodedSessionID(HttpServerExchange exchange, String unencodedSessionID) {
        // assert unencodedSessionID != null : "unencodedSessionID has value null!";
        exchange.setResponseCookie(new CookieImpl(JSESSIONID_COOKIE_NAME.toString(), unencodedSessionID));
    }

    // Header operations

    /*
     * Add a STRICT_STICKINESS_HOST header to the ClientRequest with the given hostname.
     */
    public static void addStrictStickinessHost(ClientRequest request, String host) {
        request.getRequestHeaders().put(STRICT_STICKINESS_HOST, host);
    }

    /*
     * Check if the ClientResponse has a STRICT_STICKINESS_HOST header.
     */
    public static boolean hasStrictStickinessHost(ClientResponse response) throws Exception {
        HeaderValues strictStickinessHosts = response.getResponseHeaders().get(STRICT_STICKINESS_HOST);
        if (strictStickinessHosts != null && strictStickinessHosts.size() > 0) {
            return true;
        }
        return false;
    }

    /*
     * Get the STRICT_STICKINESS_HOST header and validate and return the host value.
     */
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

    /*
     * Add a STRICT_STICKINESS_RESULT header to the ClientRequest with the given result ("success" or "failure").
     */
    public static void addStrictStickinessResult(ClientRequest request, String result) {
        request.getRequestHeaders().put(STRICT_STICKINESS_RESULT, result);
    }

    /*
     * Check if the ClientResponse has a STRICT_STICKINESS_RESULT header.
     */
    public static boolean hasStrictStickinessResult(ClientResponse response) throws Exception {
        HeaderValues strictStickinessResults = response.getResponseHeaders().get(STRICT_STICKINESS_RESULT);
        if (strictStickinessResults != null && strictStickinessResults.size() > 0) {
            return true;
        }
        return false;
    }

    /*
     * Get the value of the STRICT_STICKINESS_RESULT header and validate the response.
     */
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


    /*
     * Add a STRICT_STICKINESS_HOST header to the HttpServerExchange with the given hostname.
     */
    public static void addStrictStickinessHost(HttpServerExchange exchange, String host) {
        exchange.getResponseHeaders().put(STRICT_STICKINESS_HOST, host);
    }

    /*
     * Check if the HttpClientExchange has a STRICT_STICKINESS_HOST header.
     */
    public static boolean hasStrictStickinessHost(HttpServerExchange exchange) throws Exception {
        HeaderValues strictStickinessHosts = exchange.getResponseHeaders().get(STRICT_STICKINESS_HOST);
        if (strictStickinessHosts != null && strictStickinessHosts.size() > 0) {
            return true;
        }
        return false;
    }

    /*
     * Get a STRICT_STICKINESS_HOST header to the HttpServerExchange with the given hostname.
     */
    public static String getStrictStickinessHost(HttpServerExchange exchange) throws Exception {
        String strictStickinessHost = null;
        HeaderValues strictStickinessHosts = exchange.getResponseHeaders().get(STRICT_STICKINESS_HOST);
        if (strictStickinessHosts != null && strictStickinessHosts.size() > 0) {
            strictStickinessHost = strictStickinessHosts.getFirst();
            if (strictStickinessHost == null) {
                throw new Exception("Stickiness host is null - this should not happen");
            }
        }
        return strictStickinessHost;
    }

    /*
     * Add a STRICT_STICKINESS_RESULT header to the HttpServerExchange with the given hostname.
     */
    public static void addStrictStickinessResult(HttpServerExchange exchange, String result) {
        exchange.getResponseHeaders().put(STRICT_STICKINESS_RESULT, result);
    }


    // map of node to sessionID

    /*
     * Extract the sessionID and the route from the encoded sessionID, update the node2SessionID map withthe new entry
     * and return the route. This method assumes that the ClientResponse has a JSESSIONID Cookie.
     */
    public static String updateNode2SessionIDMap(ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionIdMap, URI uri, ClientResponse response) {
        // get the encoded sessionID from the JSESSIONID Cookie and extract the parts
        String encodedSessionID = getEncodedSessionID(response);
        String sessionID = extractSessionIDFromEncodedSessionID(encodedSessionID);
        String route = extractRouteFromEncodedSessionID(encodedSessionID);

        // update the node -> sessionID map
        String oldSessionID = addSessionIDForNode(node2SessionIdMap, uri, route, sessionID);

        if (oldSessionID != null) {
            HttpClientMessages.MESSAGES.infof("HttpStickinessHandler:updateNode2SessionIDMap uri = %s, node = %s, oldSessionID %s, sessionId %s", uri, route, oldSessionID, sessionID);
        } else {
            HttpClientMessages.MESSAGES.infof("HttpStickinessHandler:updateNode2SessionIDMap uri = %s, node = %s, sessionId %s", uri, route, sessionID);
        }

        return route;
    }



    /*
     * Record an association between (node, sessionID) for the given URI
     */
    public static String addSessionIDForNode(ConcurrentMap<URI, ConcurrentMap<String, String>> node2SessionIdMap, URI uri, String node, String sessionID) {
        ConcurrentMap<String, String> map = node2SessionIdMap.get(uri);
        if (map == null) {
            map = new ConcurrentHashMap<String, String>();
            node2SessionIdMap.put(uri, map);
        }
        String oldSessionID = map.put(node, sessionID);
        if (oldSessionID != null) {
            // this should only happen if a backend node has been restarted
            HttpClientMessages.MESSAGES.infof("HttpStickinessHelper:addSessionIDForNode() sessionID %s for node %s has been replaced by %s for URI %s", oldSessionID, node, sessionID, uri);
        }
        return oldSessionID;
    }

    /*
     * Discover an association between (node, sessionID) for the given URI
     */
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
