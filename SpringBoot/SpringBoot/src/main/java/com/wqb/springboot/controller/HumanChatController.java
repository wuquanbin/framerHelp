package com.wqb.springboot.controller;

import com.wqb.springboot.dto.ApiResponse;
import com.wqb.springboot.entity.HumanChatMessage;
import com.wqb.springboot.entity.HumanChatSession;
import com.wqb.springboot.repository.HumanChatMessageRepository;
import com.wqb.springboot.repository.HumanChatSessionRepository;
import com.wqb.springboot.websocket.SessionRegistry;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工客服 REST 接口
 * <p>
 * 管理端（需 JWT 认证）：会话列表、历史消息、关闭会话
 * 微信端（公开）：在线专家数查询、农民自己未关闭会话查询
 * </p>
 */
@RestController
@RequestMapping("/api")
public class HumanChatController {

    private final HumanChatSessionRepository sessionRepo;
    private final HumanChatMessageRepository msgRepo;

    public HumanChatController(HumanChatSessionRepository sessionRepo,
                               HumanChatMessageRepository msgRepo) {
        this.sessionRepo = sessionRepo;
        this.msgRepo = msgRepo;
    }

    // ==================== 公开接口（微信端） ====================

    /** 查询当前是否有专家在线，返回在线数量及专家列表 */
    @GetMapping("/human-chat/online-experts")
    public ApiResponse<Map<String, Object>> getOnlineExperts() {
        Map<String, Object> r = new HashMap<>();
        r.put("onlineCount", SessionRegistry.getOnlineAdminCount());
        r.put("experts", SessionRegistry.getAllOnlineAdmins());
        return ApiResponse.ok(r);
    }

    /** 农民查询自己尚未关闭的会话（用于进入 AI 页时恢复会话态） */
    @GetMapping("/human-chat/my-active-session")
    public ApiResponse<Map<String, Object>> getMyActiveSession(@RequestParam String openid) {
        Map<String, Object> r = new HashMap<>();
        HumanChatSession s = sessionRepo.findActiveByFarmerOpenid(openid).orElse(null);
        r.put("session", s);
        if (s != null) {
            r.put("messages", msgRepo.findBySessionIdOrderByCreatedAtAsc(s.getId()));
            // 把农民侧未读数清零（打开页面即视为已读）
            if (s.getFarmerUnread() != null && s.getFarmerUnread() > 0) {
                s.setFarmerUnread(0);
                sessionRepo.save(s);
            }
        }
        return ApiResponse.ok(r);
    }

    // ==================== 管理端接口（需 JWT 认证，被 SecurityConfig /api/admin/** 拦截） ====================

    /** 管理员：会话列表（按状态过滤，默认全部），支持 ?status=WAITING,ACTIVE,CLOSED */
    @GetMapping("/admin/human-chat/sessions")
    public ApiResponse<Map<String, Object>> listSessions(@RequestParam(required = false) String status) {
        List<HumanChatSession> list;
        if (status != null && !status.isBlank()) {
            list = sessionRepo.findByStatusInOrderByLastMessageTimeDesc(List.of(status.split(",")));
        } else {
            list = sessionRepo.findAllByOrderByLastMessageTimeDesc();
        }
        Map<String, Object> r = new HashMap<>();
        r.put("list", list);
        r.put("onlineAdmins", SessionRegistry.getAllOnlineAdmins());
        // 统计未读数
        int totalUnread = list.stream().filter(s -> s.getAdminUnread() != null).mapToInt(HumanChatSession::getAdminUnread).sum();
        r.put("totalUnread", totalUnread);
        return ApiResponse.ok(r);
    }

    /** 管理员：查看会话的完整历史消息 */
    @GetMapping("/admin/human-chat/sessions/{sessionId}/messages")
    public ApiResponse<List<HumanChatMessage>> listMessages(@PathVariable Long sessionId) {
        return ApiResponse.ok(msgRepo.findBySessionIdOrderByCreatedAtAsc(sessionId));
    }

    /** 管理员：强制关闭会话（兜底） */
    @PutMapping("/admin/human-chat/sessions/{sessionId}/close")
    public ApiResponse<HumanChatSession> closeSession(@PathVariable Long sessionId) {
        HumanChatSession s = sessionRepo.findById(sessionId).orElse(null);
        if (s == null) return ApiResponse.fail("会话不存在");
        if (!"CLOSED".equals(s.getStatus())) {
            s.setStatus("CLOSED");
            s.setClosedAt(LocalDateTime.now());
            s = sessionRepo.save(s);
        }
        SessionRegistry.unbindSession(s.getId());
        return ApiResponse.ok(s);
    }

    /** 管理员：打开某会话时，清零其未读数 */
    @PutMapping("/admin/human-chat/sessions/{sessionId}/read")
    public ApiResponse<HumanChatSession> markSessionRead(@PathVariable Long sessionId) {
        HumanChatSession s = sessionRepo.findById(sessionId).orElse(null);
        if (s == null) return ApiResponse.fail("会话不存在");
        s.setAdminUnread(0);
        return ApiResponse.ok(sessionRepo.save(s));
    }
}
