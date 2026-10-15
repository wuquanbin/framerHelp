package com.wqb.springboot.service.impl;

import com.wqb.springboot.entity.HumanChatMessage;
import com.wqb.springboot.entity.HumanChatSession;
import com.wqb.springboot.exception.BusinessException;
import com.wqb.springboot.repository.HumanChatMessageRepository;
import com.wqb.springboot.repository.HumanChatSessionRepository;
import com.wqb.springboot.service.HumanChatService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人工客服业务实现
 * <p>
 * 写操作统一加 @Transactional，保证"消息落库 + 会话未读数更新"等
 * 多次写操作的原子性（此前直接写在 Handler 中无事务包裹）。
 * </p>
 */
@Service
public class HumanChatServiceImpl implements HumanChatService {

    private final HumanChatSessionRepository sessionRepo;
    private final HumanChatMessageRepository msgRepo;

    public HumanChatServiceImpl(HumanChatSessionRepository sessionRepo,
                                HumanChatMessageRepository msgRepo) {
        this.sessionRepo = sessionRepo;
        this.msgRepo = msgRepo;
    }

    @Override
    public FarmerRequestResult handleFarmerRequest(String openid, String nickname,
                                                   String villageCode, String villageName,
                                                   String aiContext) {
        // 1. 该农民已有打开中的会话（WAITING/ACTIVE）则直接复用，不重复建会话
        HumanChatSession existing = sessionRepo.findActiveByFarmerOpenid(openid).orElse(null);
        if (existing != null) {
            return new FarmerRequestResult(existing, true, "ACTIVE".equals(existing.getStatus()));
        }

        // 2. 新会话入库（是否有在线专家由 WS 层判断，此处只负责业务数据）
        HumanChatSession s = new HumanChatSession();
        s.setFarmerOpenid(openid);
        s.setFarmerNickname(nickname);
        s.setVillageCode(villageCode);
        s.setVillageName(villageName);
        if (aiContext != null && !aiContext.isBlank()) {
            s.setAiContextSnapshot(aiContext);
        }
        s.setLastMessage("（用户已发起转人工，等待专家接待）");
        s = sessionRepo.save(s);
        return new FarmerRequestResult(s, false, false);
    }

    @Override
    @Transactional
    public AdminAcceptResult acceptSession(String adminUsername, String adminRealName, Long sessionId) {
        HumanChatSession s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        if (!"WAITING".equals(s.getStatus()) && !"ACTIVE".equals(s.getStatus())) {
            throw new BusinessException("会话状态不允许接单: " + s.getStatus());
        }

        s.setStatus("ACTIVE");
        s.setAdminUsername(adminUsername);
        s.setAdminRealName(adminRealName);
        s.setStartedAt(LocalDateTime.now());
        sessionRepo.save(s);

        List<HumanChatMessage> history = msgRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return new AdminAcceptResult(s, history);
    }

    @Override
    @Transactional
    public ChatSendResult sendChatMessage(boolean fromFarmer, String senderIdentity,
                                          String senderName, Long sessionId, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException("消息内容不能为空");
        }
        HumanChatSession s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        if ("CLOSED".equals(s.getStatus())) {
            throw new BusinessException("会话已关闭");
        }

        // 权限校验：只有会话双方自己能发消息
        if (fromFarmer && !senderIdentity.equals(s.getFarmerOpenid())) {
            throw new BusinessException("无权向该会话发消息");
        }
        if (!fromFarmer && (s.getAdminUsername() == null || !s.getAdminUsername().equals(senderIdentity))) {
            throw new BusinessException("您尚未接取该会话");
        }

        // 消息落库
        HumanChatMessage m = new HumanChatMessage();
        m.setSessionId(s.getId());
        m.setSenderType(fromFarmer ? "FARMER" : "ADMIN");
        m.setSenderIdentity(senderIdentity);
        m.setSenderName(senderName);
        m.setContent(content.trim());
        m = msgRepo.save(m);

        // 更新会话最后消息 / 未读数 / 时间（与上面落库同事务，保证原子性）
        s.setLastMessage(m.getContent());
        s.setLastMessageTime(LocalDateTime.now());
        if (fromFarmer) {
            s.setAdminUnread(s.getAdminUnread() + 1);
        } else {
            s.setFarmerUnread(s.getFarmerUnread() + 1);
        }
        sessionRepo.save(s);

        return new ChatSendResult(s, m);
    }

    @Override
    @Transactional
    public boolean markRead(boolean byFarmer, Long sessionId) {
        HumanChatSession s = sessionRepo.findById(sessionId).orElse(null);
        if (s == null) return false;
        if (byFarmer) {
            s.setFarmerUnread(0);
        } else {
            s.setAdminUnread(0);
        }
        sessionRepo.save(s);
        return true;
    }

    @Override
    @Transactional
    public HumanChatSession closeSession(Long sessionId, boolean byFarmer, String identity) {
        HumanChatSession s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        // 管理员可直接关闭；农民只能关闭自己所属的会话
        if (byFarmer && !identity.equals(s.getFarmerOpenid())) {
            throw new BusinessException("无权关闭该会话");
        }
        s.setStatus("CLOSED");
        s.setClosedAt(LocalDateTime.now());
        sessionRepo.save(s);
        return s;
    }

    @Override
    public List<HumanChatSession> listWaitingSessions() {
        return sessionRepo.findByStatusInOrderByLastMessageTimeDesc(List.of("WAITING"));
    }
}
