package com.talentserv.blink.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the OAuth callback URL for the current environment instead of a baked-in localhost default.
 * Localhost env values are ignored when the API is serving a public host (Render / production).
 */
public final class OAuthRedirectResolver {

    private OAuthRedirectResolver() {
    }

    public static String callbackPath(String provider) {
        return "/api/integrations/" + provider + "/oauth/callback";
    }

    public static String resolve(
            String provider,
            String requestedRedirectUri,
            String publicApiBase,
            String configuredRedirectUri,
            String corsOrigins
    ) {
        String path = callbackPath(provider);
        Set<String> allowedHosts = allowedHosts(publicApiBase, configuredRedirectUri, corsOrigins);
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, requestedRedirectUri);
        addCandidate(candidates, joinBase(publicApiBase, path));
        if (usableConfigured(configuredRedirectUri, publicApiBase)) {
            addCandidate(candidates, configuredRedirectUri);
        }
        addCandidate(candidates, "http://localhost:5173" + path);

        for (String candidate : candidates) {
            if (isAllowed(candidate, path, allowedHosts, publicApiBase)) {
                return candidate;
            }
        }
        return "http://localhost:5173" + path;
    }

    public static String publicApiBase(String forwardedProto, String forwardedHost, String requestScheme, String requestHost) {
        String proto = firstHop(firstNonBlank(forwardedProto, requestScheme));
        String host = firstHop(firstNonBlank(forwardedHost, requestHost));
        if (proto == null || proto.isBlank()) {
            proto = "http";
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        return proto + "://" + host;
    }

    private static boolean usableConfigured(String configured, String publicApiBase) {
        if (blank(configured)) {
            return false;
        }
        boolean configuredLocal = isLoopbackUri(configured);
        boolean requestLocal = blank(publicApiBase) || isLoopbackUri(publicApiBase);
        return !configuredLocal || requestLocal;
    }

    private static boolean isAllowed(String uri, String expectedPath, Set<String> allowedHosts, String publicApiBase) {
        URI parsed = parse(uri);
        if (parsed == null || parsed.getHost() == null) {
            return false;
        }
        String path = parsed.getPath() == null ? "" : parsed.getPath();
        if (!expectedPath.equals(path)) {
            return false;
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        boolean loopback = isLoopbackHost(parsed.getHost());
        if ("http".equals(scheme)) {
            if (!loopback && !isLoopbackUri(publicApiBase)) {
                return false;
            }
        } else if (!"https".equals(scheme)) {
            return false;
        }
        return allowedHosts.contains(hostKey(parsed))
                || loopback
                || hostKey(parsed).equals(hostKey(parse(publicApiBase)));
    }

    private static Set<String> allowedHosts(String publicApiBase, String configured, String corsOrigins) {
        Set<String> hosts = new LinkedHashSet<>();
        addHost(hosts, publicApiBase);
        addHost(hosts, configured);
        if (corsOrigins != null) {
            for (String origin : corsOrigins.split(",")) {
                addHost(hosts, origin.trim());
            }
        }
        hosts.add(hostKey("localhost", 5173));
        hosts.add(hostKey("127.0.0.1", 5173));
        return hosts;
    }

    private static void addHost(Set<String> hosts, String uri) {
        URI parsed = parse(uri);
        if (parsed == null || parsed.getHost() == null) {
            return;
        }
        hosts.add(hostKey(parsed));
        if (isLoopbackHost(parsed.getHost())) {
            int port = effectivePort(parsed);
            hosts.add(hostKey("localhost", port));
            hosts.add(hostKey("127.0.0.1", port));
        }
    }

    private static void addCandidate(List<String> candidates, String value) {
        if (blank(value)) {
            return;
        }
        String trimmed = value.trim();
        if (!candidates.contains(trimmed)) {
            candidates.add(trimmed);
        }
    }

    private static String joinBase(String base, String path) {
        if (blank(base)) {
            return null;
        }
        String root = base.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.endsWith("/api")) {
            root = root.substring(0, root.length() - 4);
        }
        return root + path;
    }

    private static URI parse(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            if (uri.getHost() == null && uri.getScheme() == null) {
                uri = URI.create("https://" + value.trim());
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String hostKey(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return "";
        }
        return hostKey(uri.getHost(), effectivePort(uri));
    }

    private static String hostKey(String host, int port) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("::1".equals(normalized) || "[::1]".equals(normalized)) {
            normalized = "localhost";
        }
        return normalized + ":" + port;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isLoopbackUri(String value) {
        URI uri = parse(value);
        return uri != null && isLoopbackHost(uri.getHost());
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h) || "[::1]".equals(h);
    }

    private static String firstHop(String value) {
        if (blank(value)) {
            return value;
        }
        int comma = value.indexOf(',');
        return comma < 0 ? value.trim() : value.substring(0, comma).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
