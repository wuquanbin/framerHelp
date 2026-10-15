package com.wqb.springboot.websocket;

import com.wqb.springboot.entity.AdminUser;
import com.wqb.springboot.entity.User;
import com.wqb.springboot.repository.AdminUserRepository;
import com.wqb.springboot.repository.UserRepository;
import com.wqb.springboot.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手拦截器
 * <p>
 * 在握手阶段解析身份并将 ChatPrincipal 写入 attributes（key="principal"），
 * 后续 ChatWebSocketHandler 统一从 session.getAttributes().get("principal") 获取身份。
 *
 * <h3>农民端连接（小程序）</h3>
 * Query 参数：<code>role=FARMER&openid=xxx&nickname=xx&villageCode=xx&villageName=xx</code>
 *
 * <h3>管理端连接（Vue3）</h3>
 * Query 参数：<code>role=ADMIN&token=JWT</code>
 * 或通过 Authorization header 携带 Bearer token。
 * </p>
 */
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ChatHandshakeInterceptor.class);

    private final JwtUtil jwtUtil;
    private final AdminUserRepository adminUserRepository;
    private final UserRepository userRepository;

    public ChatHandshakeInterceptor(JwtUtil jwtUtil, AdminUserRepository adminUserRepository,
                                    UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.adminUserRepository = adminUserRepository;
        this.userRepository = userRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        try {
            if (!(request instanceof ServletServerHttpRequest servletReq)) {
                log.warn("[WS] 非 Servlet 请求，拒绝握手");
                return false;
            }
            var req = servletReq.getServletRequest();
            String role = req.getParameter("role");
            if ("FARMER".equalsIgnoreCase(role)) {
                String openid = req.getParameter("openid");
                if (openid == null || openid.isBlank()) {
                    log.warn("[WS] 农民握手缺少 openid");
                    return false;
                }
                String nickname = req.getParameter("nickname");
                String villageCode = req.getParameter("villageCode");
                String villageName = req.getParameter("villageName");
                // 农户显示名：优先取用户表的真实昵称（query 传的只是兜底），统一加"农户"后缀
                String displayName = null;
                User user = userRepository.findByOpenid(openid).orElse(null);
                if (user != null && user.getNickname() != null && !user.getNickname().isBlank()) {
                    displayName = user.getNickname();
                } else if (nickname != null && !nickname.isBlank()) {
                    displayName = nickname;
                } else {
                    displayName = "农户";
                }
                if (!displayName.endsWith("农户")) {
                    displayName = displayName + "农户";
                }
                attributes.put("principal", new ChatPrincipal("FARMER", openid));
                attributes.put("nickname", displayName);
                attributes.put("villageCode", villageCode == null ? "baini" : villageCode);
                attributes.put("villageName", villageName == null ? "白坭镇" : villageName);
                log.info("[WS] 农民握手成功: openid={}, name={}, village={}", openid, displayName, villageCode);
                return true;
            }
            if ("ADMIN".equalsIgnoreCase(role)) {
                String token = req.getParameter("token");
                if (token == null || token.isBlank()) {
                    // 尝试从 Authorization header 取
                    String authHeader = req.getHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        token = authHeader.substring(7);
                    }
                }
                if (token == null || token.isBlank() || !jwtUtil.validateToken(token)) {
                    log.warn("[WS] 管理员握手 token 无效");
                    return false;
                }
                String username = jwtUtil.getUsernameFromToken(token);
                AdminUser admin = adminUserRepository.findByUsername(username).orElse(null);
                if (admin == null || admin.getStatus() == null || admin.getStatus() != 1) {
                    log.warn("[WS] 管理员账号无效或已禁用: {}", username);
                    return false;
                }
                attributes.put("principal", new ChatPrincipal("ADMIN", username));
                attributes.put("adminId", admin.getId());
                attributes.put("realName", admin.getRealName() == null ? username : admin.getRealName());
                log.info("[WS] 管理员握手成功: username={}, id={}", username, admin.getId());
                return true;
            }
            log.warn("[WS] 握手缺少 role 参数或非法: {}", role);
            return false;
        } catch (Exception e) {
            log.error("[WS] 握手异常", e);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手完成后无需额外处理
    }
}
