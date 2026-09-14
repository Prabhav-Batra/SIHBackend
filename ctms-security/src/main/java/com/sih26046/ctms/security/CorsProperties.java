package com.sih26046.ctms.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cross-origin configuration.
 *
 * <p>The platform's own frontend is served from a different origin than the API whenever the two
 * are deployed separately — a local {@code http://localhost:3000} dev server against the hosted
 * API being the ordinary case. Without an explicit allowance the browser's preflight
 * {@code OPTIONS} never reaches a controller: it is refused by the filter chain as
 * unauthenticated, and every call — login included — surfaces as a 401.
 *
 * <p>The list is empty by default, which leaves CORS off entirely. That is the correct default
 * for a same-origin deployment (the frontend behind the same Caddy vhost as the API) and keeps
 * the failure mode of forgetting to configure it "nothing is allowed" rather than "anything is".
 *
 * @param allowedOrigins exact origins permitted to call the API with credentials. Wildcards are
 *     not usable here: {@code Access-Control-Allow-Credentials: true} and
 *     {@code Access-Control-Allow-Origin: *} are mutually exclusive per the CORS spec, and this
 *     API authenticates by cookie, so every entry must be a concrete scheme://host:port.
 */
@ConfigurationProperties(prefix = "ctms.cors")
public record CorsProperties(@DefaultValue({}) List<String> allowedOrigins) {

    public CorsProperties {
        // An unset CTMS_CORS_ALLOWED_ORIGINS resolves to the empty string, and a trailing comma
        // is easy to leave behind. Either can bind to a list holding a blank entry, which would
        // switch CORS on with an origin no request can ever match — the misleading half-state
        // where preflights are handled but every one of them is refused.
        allowedOrigins =
                allowedOrigins == null
                        ? List.of()
                        : allowedOrigins.stream()
                                .filter(o -> o != null && !o.isBlank())
                                .map(String::trim)
                                .toList();
        if (allowedOrigins.contains("*")) {
            throw new IllegalStateException(
                    "ctms.cors.allowed-origins cannot contain '*': the API authenticates by "
                            + "cookie, and a wildcard origin is invalid on a credentialed "
                            + "response. List each origin explicitly.");
        }
    }

    public boolean enabled() {
        return !allowedOrigins.isEmpty();
    }
}
