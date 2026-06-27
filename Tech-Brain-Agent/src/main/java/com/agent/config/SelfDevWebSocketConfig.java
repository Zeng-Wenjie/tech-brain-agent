package com.agent.config;

import com.agent.selfdev.terminal.SelfDevTerminalHandshakeInterceptor;
import com.agent.selfdev.terminal.SelfDevTerminalWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket endpoints for sandbox-only self-development terminal sessions.
 */
@Configuration
@EnableWebSocket
public class SelfDevWebSocketConfig implements WebSocketConfigurer {

    private final SelfDevTerminalWebSocketHandler terminalWebSocketHandler;
    private final SelfDevTerminalHandshakeInterceptor terminalHandshakeInterceptor;

    public SelfDevWebSocketConfig(SelfDevTerminalWebSocketHandler terminalWebSocketHandler,
                                  SelfDevTerminalHandshakeInterceptor terminalHandshakeInterceptor) {
        this.terminalWebSocketHandler = terminalWebSocketHandler;
        this.terminalHandshakeInterceptor = terminalHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalWebSocketHandler, "/api/self-dev/terminal", "/self-dev/terminal")
                .addInterceptors(terminalHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
