package com.agent.selfdev.terminal;

import com.agent.selfdev.security.SelfDevAccessGuard;
import com.agent.selfdev.security.SelfDevWorkspaceGuard;
import com.agent.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Authenticates OWNER access before opening the interactive Claude Code terminal.
 */
@Slf4j
@Component
public class SelfDevTerminalHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "selfDevUserId";
    public static final String ATTR_USERNAME = "selfDevUsername";

    private final SelfDevAccessGuard accessGuard;
    private final SelfDevWorkspaceGuard workspaceGuard;

    public SelfDevTerminalHandshakeInterceptor(SelfDevAccessGuard accessGuard,
                                               SelfDevWorkspaceGuard workspaceGuard) {
        this.accessGuard = accessGuard;
        this.workspaceGuard = workspaceGuard;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        try {
            String token = resolveToken(request);
            if (isBlank(token)) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            Claims claims = JwtUtils.parseToken(token);
            Long userId = claims.get("userId", Long.class);
            String username = claims.get("username", String.class);
            if (userId == null) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            if (!accessGuard.isOwner(userId, username)) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            workspaceGuard.resolveSandboxWorkspace();
            attributes.put(ATTR_USER_ID, userId);
            attributes.put(ATTR_USERNAME, username);
            return true;
        } catch (Exception e) {
            log.warn("[SelfDevTerminal] handshake rejected: {}", e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // No-op.
    }

    private String resolveToken(ServerHttpRequest request) {
        String headerToken = request.getHeaders().getFirst("token");
        if (!isBlank(headerToken)) {
            return headerToken.trim();
        }
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams();
        String queryToken = query.getFirst("token");
        return queryToken == null ? null : queryToken.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
