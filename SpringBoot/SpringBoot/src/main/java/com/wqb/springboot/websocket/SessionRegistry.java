package com.wqb.springboot.websocket;

import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话注册表（内存）
 * <p>
 * 维护：
 * <ul>
 *   <li>所有活跃 WS 会话（按 principal name 映射）</li>
 *   <li>管理员在线集合（principal name → 基本信息）</li>
 *   <li>sessionId 与 两端 principal 的映射，用于消息转发</li>
 * </ul>
 * </p>
 */
public class SessionRegistry {

    /** principal name -> WebSocketSession */
    private static final Map<String, WebSocketSession> SESSIONS = new ConcurrentHashMap<>();

    /** 管理员 principal name -> realName */
    private static final Map<String, String> ADMINS_ONLINE = new ConcurrentHashMap<>();

    /** 会话DB ID -> 当前农民 principal name */
    private static final Map<Long, String> SESSION_FARMER = new ConcurrentHashMap<>();
    /** 会话DB ID -> 当前管理员 principal name（接单后赋值） */
    private static final Map<Long, String> SESSION_ADMIN = new ConcurrentHashMap<>();

    private SessionRegistry() {}

    // ====== 会话注册/注销 ======
    public static void register(ChatPrincipal principal, WebSocketSession session) {
        SESSIONS.put(principal.getName(), session);
    }

    public static void unregister(ChatPrincipal principal) {
        SESSIONS.remove(principal.getName());
        if (principal.isAdmin()) {
            ADMINS_ONLINE.remove(principal.getName());
        }
    }

    public static WebSocketSession getSession(String principalName) {
        return SESSIONS.get(principalName);
    }

    // ====== 管理员在线状态 ======
    public static void markAdminOnline(String adminPrincipal, String realName) {
        ADMINS_ONLINE.put(adminPrincipal, realName);
    }

    public static void markAdminOffline(String adminPrincipal) {
        ADMINS_ONLINE.remove(adminPrincipal);
    }

    public static boolean hasAnyAdminOnline() {
        return !ADMINS_ONLINE.isEmpty();
    }

    public static Set<String> getOnlineAdminPrincipals() {
        return ADMINS_ONLINE.keySet();
    }

    public static int getOnlineAdminCount() {
        return ADMINS_ONLINE.size();
    }

    public static String getAdminRealName(String adminPrincipal) {
        return ADMINS_ONLINE.get(adminPrincipal);
    }

    public static Map<String, String> getAllOnlineAdmins() {
        return new ConcurrentHashMap<>(ADMINS_ONLINE);
    }

    // ====== 会话ID -> 双方 principal 映射 ======
    public static void bindSessionFarmer(Long sessionId, String farmerPrincipal) {
        SESSION_FARMER.put(sessionId, farmerPrincipal);
    }

    public static void bindSessionAdmin(Long sessionId, String adminPrincipal) {
        SESSION_ADMIN.put(sessionId, adminPrincipal);
    }

    public static String getFarmerPrincipalOfSession(Long sessionId) {
        return SESSION_FARMER.get(sessionId);
    }

    public static String getAdminPrincipalOfSession(Long sessionId) {
        return SESSION_ADMIN.get(sessionId);
    }

    public static void unbindSession(Long sessionId) {
        SESSION_FARMER.remove(sessionId);
        SESSION_ADMIN.remove(sessionId);
    }
}
