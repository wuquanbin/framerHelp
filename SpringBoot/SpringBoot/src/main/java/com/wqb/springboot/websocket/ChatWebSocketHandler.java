package com.wqb.springboot.websocket;

import com.wqb.springboot.dto.WsMessage;
import com.wqb.springboot.entity.HumanChatSession;
import com.wqb.springboot.exception.BusinessException;
import com.wqb.springboot.service.HumanChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工客服实时聊天 WebSocket 处理器
 * <p>
 * 只负责 WS 协议层：连接生命周期、帧解析、会话注册表维护、消息路由与推送；
 * 业务逻辑（会话创建/接单/落库/未读数/关闭）全部委托给 {@link HumanChatService}。
 * </p>
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    // Spring Boot 4 默认 Jackson 3：ObjectMapper 已不可变，官方推荐用 JsonMapper.builder() 构建
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HumanChatService humanChatService;

    public ChatWebSocketHandler(HumanChatService humanChatService) {
        this.humanChatService = humanChatService;
    }

    // ================ 生命周期 ================
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        ChatPrincipal p = principalOf(session);
        if (p == null) {
            closeSilently(session, CloseStatus.POLICY_VIOLATION.withReason("未鉴权"));
            return;
        }
        SessionRegistry.register(p, session);

        if (p.isAdmin()) {
            String expertName = expertDisplayName(p.getIdentity());
            SessionRegistry.markAdminOnline(p.getName(), expertName);
            log.info("[WS] 管理员上线: {} ({}), 在线管理员数={}", expertName, p.getIdentity(), SessionRegistry.getOnlineAdminCount());
        }

        // 连接建立确认
        Map<String, Object> connBody = new HashMap<>();
        connBody.put("role", p.getRole());
        connBody.put("identity", p.getIdentity());
        if (p.isAdmin()) {
            connBody.put("realName", expertDisplayName(p.getIdentity()));
            connBody.put("adminId", attr(session, "adminId", null));
        } else {
            connBody.put("nickname", attr(session, "nickname", "农户"));
            connBody.put("villageCode", attr(session, "villageCode", "baini"));
            connBody.put("villageName", attr(session, "villageName", "白坭镇"));
        }
        send(session, WsMessage.of("CONNECTED", connBody));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ChatPrincipal p = principalOf(session);
        if (p != null) {
            if (p.isAdmin()) {
                SessionRegistry.markAdminOffline(p.getName());
                log.info("[WS] 管理员下线: {}", p.getIdentity());
            } else {
                log.info("[WS] 农民断线: {}", p.getIdentity());
            }
            SessionRegistry.unregister(p);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ChatPrincipal p = principalOf(session);
        if (p == null) return;
        try {
            JsonNode root = mapper.readTree(message.getPayload());
            String type = root.path("type").asText("");
            JsonNode body = root.get("body");

            switch (type) {
                case "FARMER_REQUEST"  -> onFarmerRequest(session, p, body);
                case "ADMIN_ACCEPT"    -> onAdminAccept(session, p, body);
                case "CHAT"            -> onChat(session, p, body);
                case "TYPING"          -> onTyping(p, body);
                case "READ"            -> onRead(p, body);
                case "CLOSE"           -> onClose(session, p, body);
                case "ADMIN_STATUS"    -> onAdminStatus(session, p, body);
                default                -> send(session, WsMessage.error("未知消息类型: " + type));
            }
        } catch (BusinessException be) {
            // 业务校验失败（会话不存在/已关闭/无权等）→ 直接回错误帧
            send(session, WsMessage.error(be.getMessage()));
        } catch (Exception e) {
            log.error("[WS] 处理消息异常: {}", e.getMessage(), e);
            send(session, WsMessage.error("消息格式错误: " + e.getMessage()));
        }
    }

    // ================ 农民端：请求转人工 ================
    private void onFarmerRequest(WebSocketSession session, ChatPrincipal p, JsonNode body) {
        if (!p.isFarmer()) { send(session, WsMessage.error("仅农民可发起请求")); return; }

        String aiContext = (body != null && body.has("aiContext")) ? body.path("aiContext").asText(null) : null;
        HumanChatService.FarmerRequestResult r = humanChatService.handleFarmerRequest(
                p.getIdentity(),
                attr(session, "nickname", "农户"),
                attr(session, "villageCode", "baini"),
                attr(session, "villageName", "白坭镇"),
                aiContext);
        HumanChatSession s = r.session();

        // 维护内存映射：农民必绑；复用旧会话时若已有接单管理员也补绑
        SessionRegistry.bindSessionFarmer(s.getId(), p.getName());
        if (r.resumed() && s.getAdminUsername() != null) {
            SessionRegistry.bindSessionAdmin(s.getId(), ChatPrincipal.PREFIX_ADMIN + s.getAdminUsername());
        }

        if (r.resumed() && r.active()) {
            // 复用已进行中的会话：直接告知农民当前专家信息
            Map<String, Object> b = new HashMap<>();
            b.put("session", s);
            b.put("adminInfo", Map.of(
                "username", s.getAdminUsername() == null ? "" : s.getAdminUsername(),
                "realName", s.getAdminRealName() == null ? "农技专家" : s.getAdminRealName()
            ));
            send(session, WsMessage.of("SESSION_ACCEPTED", b));
        } else if (r.resumed()) {
            // 复用等待中的会话（首次请求时无专家在线，专家上线后农民重试）
            Map<String, Object> b = new HashMap<>();
            b.put("session", s);
            b.put("onlineExperts", SessionRegistry.getOnlineAdminCount());
            send(session, WsMessage.of("SESSION_CREATED", b));
            // 重新广播给在线管理员：否则专家永远不知道这条等待中的会话
            broadcastAdmins(WsMessage.of("NEW_SESSION_NOTIFY", Map.of("session", s)));
            log.info("[WS] 农民 {} 复用等待会话: 会话ID={}, 在线专家={}", p.getIdentity(), s.getId(), SessionRegistry.getOnlineAdminCount());
        } else {
            // 全新会话：有无在线专家决定回复类型，并广播管理员
            boolean hasExpert = SessionRegistry.hasAnyAdminOnline();
            Map<String, Object> farmerBody = new HashMap<>();
            farmerBody.put("session", s);
            farmerBody.put("onlineExperts", SessionRegistry.getOnlineAdminCount());
            send(session, WsMessage.of(hasExpert ? "SESSION_CREATED" : "NO_EXPERT_ONLINE", farmerBody));

            broadcastAdmins(WsMessage.of("NEW_SESSION_NOTIFY", Map.of("session", s)));
            log.info("[WS] 农民 {} 请求转人工: 会话ID={}, 专家在线={}", p.getIdentity(), s.getId(), hasExpert);
        }
    }

    // ================ 管理员端：接单 ================
    private void onAdminAccept(WebSocketSession session, ChatPrincipal p, JsonNode body) {
        if (!p.isAdmin()) { send(session, WsMessage.error("仅管理员可接单")); return; }
        Long sessionId = body == null ? null : body.path("sessionId").asLong(0);
        if (sessionId == null || sessionId <= 0) { send(session, WsMessage.error("缺少 sessionId")); return; }

        // 优先取注册表中的专家代号（上线时已统一转换），未在线时即时生成
        String realName = SessionRegistry.getAdminRealName(p.getName());
        if (realName == null) realName = expertDisplayName(p.getIdentity());
        HumanChatService.AdminAcceptResult r = humanChatService.acceptSession(p.getIdentity(), realName, sessionId);
        HumanChatSession s = r.session();

        SessionRegistry.bindSessionAdmin(s.getId(), p.getName());

        // 推送给管理员（含历史消息）
        Map<String, Object> adminBody = new HashMap<>();
        adminBody.put("session", s);
        adminBody.put("messages", r.history());
        send(session, WsMessage.of("SESSION_ACCEPTED", adminBody));

        // 推送给农民
        String farmerP = SessionRegistry.getFarmerPrincipalOfSession(s.getId());
        if (farmerP != null) {
            WebSocketSession fSess = SessionRegistry.getSession(farmerP);
            if (fSess != null) {
                Map<String, Object> fBody = new HashMap<>();
                fBody.put("session", s);
                fBody.put("adminInfo", Map.of(
                    "username", p.getIdentity(),
                    "realName", realName
                ));
                send(fSess, WsMessage.of("SESSION_ACCEPTED", fBody));
            }
        }

        // 广播所有管理员：该会话已被接单（更新列表状态）
        broadcastAdmins(WsMessage.of("SESSION_ACCEPTED_BROADCAST", Map.of("sessionId", s.getId(),
            "adminUsername", p.getIdentity(), "adminRealName", realName)));

        log.info("[WS] 管理员 {} 接单: 会话ID={}", realName, s.getId());
    }

    // ================ 双方：发聊天消息 ================
    private void onChat(WebSocketSession session, ChatPrincipal p, JsonNode body) {
        if (body == null) { send(session, WsMessage.error("缺少 body")); return; }
        Long sessionId = body.path("sessionId").asLong(0);
        String content = body.path("content").asText("").trim();
        if (sessionId <= 0 || content.isEmpty()) { send(session, WsMessage.error("参数缺失")); return; }

        String senderName;
        if (p.isFarmer()) {
            senderName = attr(session, "nickname", "农户");
        } else {
            senderName = SessionRegistry.getAdminRealName(p.getName());
            if (senderName == null) senderName = expertDisplayName(p.getIdentity());
        }
        HumanChatService.ChatSendResult r = humanChatService.sendChatMessage(
                p.isFarmer(), p.getIdentity(), senderName, sessionId, content);
        HumanChatSession s = r.session();

        // 推给双方
        WsMessage newChatMsg = WsMessage.of("NEW_CHAT", Map.of("sessionId", s.getId(), "message", r.message()));
        send(session, newChatMsg); // 回显给发送者
        if (p.isFarmer()) {
            String adminP = SessionRegistry.getAdminPrincipalOfSession(s.getId());
            if (adminP != null) {
                if (!"ACTIVE".equals(s.getStatus())) {
                    // 等待状态被抢发，广播给所有管理员
                    broadcastAdmins(newChatMsg);
                } else {
                    sendToPrincipal(adminP, newChatMsg);
                }
            } else {
                // 未被接单，广播全部管理员
                broadcastAdmins(newChatMsg);
            }
        } else {
            String farmerP = SessionRegistry.getFarmerPrincipalOfSession(s.getId());
            if (farmerP != null) sendToPrincipal(farmerP, newChatMsg);
        }

        // 通知管理员列表端（未读数变化）
        broadcastAdmins(WsMessage.of("SESSION_UNREAD_UPDATE", Map.of(
            "sessionId", s.getId(),
            "adminUnread", s.getAdminUnread(),
            "farmerUnread", s.getFarmerUnread(),
            "lastMessage", s.getLastMessage(),
            "lastMessageTime", s.getLastMessageTime()
        )));
    }

    // ================ 正在输入 / 已读 / 关闭 ================
    private void onTyping(ChatPrincipal p, JsonNode body) {
        Long sessionId = body == null ? null : body.path("sessionId").asLong(0);
        if (sessionId == null || sessionId <= 0) return;
        String targetPrincipal;
        if (p.isFarmer()) {
            targetPrincipal = SessionRegistry.getAdminPrincipalOfSession(sessionId);
            if (targetPrincipal == null) return;
        } else {
            targetPrincipal = SessionRegistry.getFarmerPrincipalOfSession(sessionId);
            if (targetPrincipal == null) return;
        }
        sendToPrincipal(targetPrincipal, WsMessage.of("TYPING", Map.of(
            "sessionId", sessionId, "from", p.isFarmer() ? "FARMER" : "ADMIN"
        )));
    }

    private void onRead(ChatPrincipal p, JsonNode body) {
        Long sessionId = body == null ? null : body.path("sessionId").asLong(0);
        if (sessionId == null || sessionId <= 0) return;
        boolean ok = humanChatService.markRead(p.isFarmer(), sessionId);
        if (!ok) return;

        // 回执给对方
        String targetPrincipal;
        if (p.isFarmer()) {
            targetPrincipal = SessionRegistry.getAdminPrincipalOfSession(sessionId);
        } else {
            targetPrincipal = SessionRegistry.getFarmerPrincipalOfSession(sessionId);
        }
        if (targetPrincipal != null) {
            sendToPrincipal(targetPrincipal, WsMessage.of("READ_ACK", Map.of(
                "sessionId", sessionId, "by", p.isFarmer() ? "FARMER" : "ADMIN"
            )));
        }
    }

    private void onClose(WebSocketSession session, ChatPrincipal p, JsonNode body) {
        Long sessionId = body == null ? null : body.path("sessionId").asLong(0);
        if (sessionId == null || sessionId <= 0) { send(session, WsMessage.error("缺少 sessionId")); return; }

        HumanChatSession s = humanChatService.closeSession(sessionId, p.isFarmer(), p.getIdentity());

        WsMessage closedMsg = WsMessage.of("SESSION_CLOSED", Map.of("sessionId", s.getId()));
        send(session, closedMsg);

        String farmerP = SessionRegistry.getFarmerPrincipalOfSession(s.getId());
        String adminP = SessionRegistry.getAdminPrincipalOfSession(s.getId());
        if (farmerP != null && !farmerP.equals(p.getName())) sendToPrincipal(farmerP, closedMsg);
        if (adminP != null && !adminP.equals(p.getName())) sendToPrincipal(adminP, closedMsg);
        broadcastAdmins(closedMsg);
        SessionRegistry.unbindSession(s.getId());

        log.info("[WS] 会话 {} 已关闭, by {} {}", s.getId(), p.getRole(), p.getIdentity());
    }

    private void onAdminStatus(WebSocketSession session, ChatPrincipal p, JsonNode body) {
        if (!p.isAdmin()) return;
        boolean online = body != null && body.path("online").asBoolean(true);
        String realName = SessionRegistry.getAdminRealName(p.getName());
        if (realName == null) realName = p.getIdentity();
        int waitingCount = 0;
        if (online) {
            SessionRegistry.markAdminOnline(p.getName(), realName);
            // 上线补偿：把当前待接单会话推给刚上线的专家，避免漏看（工作台REST也会加载，这里保证实时）
            List<HumanChatSession> waiting = humanChatService.listWaitingSessions();
            waitingCount = waiting.size();
            for (HumanChatSession ws : waiting) {
                send(session, WsMessage.of("NEW_SESSION_NOTIFY", Map.of("session", ws)));
            }
        } else {
            SessionRegistry.markAdminOffline(p.getName());
        }
        log.info("[WS] 管理员 {} 状态变更: online={}, 待接单={}", realName, online, waitingCount);
    }

    // ================ 工具方法 ================
    /**
     * 专家对外显示名：基于管理员账号生成稳定的 3 位数字代号（如"专家351"），
     * 同一账号代号恒定，不暴露真实姓名。
     */
    private String expertDisplayName(String adminUsername) {
        int code = Math.abs(adminUsername.hashCode()) % 900 + 100;
        return "专家" + code;
    }

    // 身份在握手拦截器写入的 attributes（key="principal"）里：session.getPrincipal() 是容器级 Principal，
    // attributes.put 不会映射到它（恒为 null），必须从 attributes 读。
    private ChatPrincipal principalOf(WebSocketSession s) {
        Object v = s.getAttributes().get("principal");
        return v instanceof ChatPrincipal cp ? cp : null;
    }

    @SuppressWarnings("unchecked")
    private <T> T attr(WebSocketSession s, String key, T defVal) {
        Object v = s.getAttributes().get(key);
        if (v == null) return defVal;
        try { return (T) v; } catch (Exception e) { return defVal; }
    }

    private void send(WebSocketSession s, WsMessage msg) {
        if (s == null || !s.isOpen()) return;
        try {
            s.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        } catch (IOException e) {
            log.warn("[WS] 发送消息失败: {}", e.getMessage());
        }
    }

    private void sendToPrincipal(String principalName, WsMessage msg) {
        if (principalName == null) return;
        WebSocketSession s = SessionRegistry.getSession(principalName);
        send(s, msg);
    }

    /** 给所有在线管理员广播 */
    private void broadcastAdmins(WsMessage msg) {
        List<String> names = List.copyOf(SessionRegistry.getOnlineAdminPrincipals());
        for (String n : names) sendToPrincipal(n, msg);
    }

    private void closeSilently(WebSocketSession s, CloseStatus cs) {
        try { s.close(cs); } catch (Exception ignored) {}
    }
}
