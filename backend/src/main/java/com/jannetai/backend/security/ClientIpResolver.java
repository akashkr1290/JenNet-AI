package com.jannetai.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Audit GAP-049: single place that decides the client IP recorded for OTP
 * requests, refresh tokens and audit data. Client-supplied headers such as
 * X-Forwarded-For are NOT trusted. Only the header named by
 * app.rate-limit.client-ip-header (production: X-Real-IP, which
 * deployment/nginx/jannet.conf overwrites with nginx's own $remote_addr) is
 * honoured, exactly like AuthEndpointRateLimitFilter; otherwise the socket
 * address is used.
 */
@Component
public class ClientIpResolver {

    private final String clientIpHeader;

    public ClientIpResolver(@Value("${app.rate-limit.client-ip-header:}") String clientIpHeader) {
        this.clientIpHeader = clientIpHeader;
    }

    public String resolve(HttpServletRequest request) {
        if (clientIpHeader != null && !clientIpHeader.isBlank()) {
            String value = request.getHeader(clientIpHeader);
            if (value != null && !value.isBlank()) {
                String first = value.split(",")[0].trim();
                return first.length() > 45 ? first.substring(0, 45) : first;
            }
        }
        return request.getRemoteAddr();
    }
}
