package com.wqb.springboot.websocket;

import com.wqb.springboot.repository.AdminUserRepository;
import com.wqb.springboot.repository.UserRepository;
import com.wqb.springboot.security.JwtUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 * <p>
 * 注册 /ws/chat 端点，使用自定义握手拦截器绑定 Principal，允许所有来源跨域。
 * 采用原生 WebSocketHandler（非 STOMP/SockJS），兼容微信小程序原生 WS 协议。
 * </p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatHandler;
    private final JwtUtil jwtUtil;
    private final AdminUserRepository adminUserRepository;
    private final UserRepository userRepository;

    public WebSocketConfig(ChatWebSocketHandler chatHandler,
                           JwtUtil jwtUtil,
                           AdminUserRepository adminUserRepository,
                           UserRepository userRepository) {
        this.chatHandler = chatHandler;
        this.jwtUtil = jwtUtil;
        this.adminUserRepository = adminUserRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatHandler, "/ws/chat")
                .addInterceptors(new ChatHandshakeInterceptor(jwtUtil, adminUserRepository, userRepository))
                .setAllowedOriginPatterns("*");
    }
}
