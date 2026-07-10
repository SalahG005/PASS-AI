package com.pass.ai_assistant_backend.config;

import com.pass.ai_assistant_backend.security.JwtUtil;
import com.pass.ai_assistant_backend.terminal.TerminalWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final JwtUtil jwtUtil;

    public WebSocketConfig(TerminalWebSocketHandler terminalWebSocketHandler, JwtUtil jwtUtil) {
        this.terminalWebSocketHandler = terminalWebSocketHandler;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal")
                .addInterceptors(authInterceptor())
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    private HandshakeInterceptor authInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                String token = null;
                if (request instanceof ServletServerHttpRequest servletRequest) {
                    var httpReq = servletRequest.getServletRequest();
                    token = httpReq.getParameter("token");
                    String ws = httpReq.getParameter("ws");
                    if (ws != null && !ws.isBlank()) {
                        attributes.put("workspaceId", ws);
                    }
                    String shell = httpReq.getParameter("shell");
                    if (shell != null && !shell.isBlank()) {
                        attributes.put("shell", shell);
                    }
                }
                if (token == null || token.isBlank() || !jwtUtil.isTokenValid(token)) {
                    return false;
                }
                attributes.put("email", jwtUtil.extractEmail(token));
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) {
            }
        };
    }
}
