package com.wqb.springboot.service;

import com.wqb.springboot.entity.HumanChatMessage;
import com.wqb.springboot.entity.HumanChatSession;

import java.util.List;

/**
 * 人工客服业务服务
 * <p>
 * 负责转人工聊天的业务逻辑：会话创建/恢复、接单、消息落库与未读数、已读、关闭。
 * WS 传输与路由（WebSocketSession 查找、消息推送）由 ChatWebSocketHandler 负责，
 * 本接口只做数据库业务，方法参数全部用基础类型/实体，避免 service 层反向依赖 websocket 包。
 * </p>
 */
public interface HumanChatService {

    /** 农民请求转人工的结果：resumed=复用已有会话；active=该会话已处于进行中 */
    record FarmerRequestResult(HumanChatSession session, boolean resumed, boolean active) {}

    /** 管理员接单结果：更新后的会话 + 该会话全部历史消息 */
    record AdminAcceptResult(HumanChatSession session, List<HumanChatMessage> history) {}

    /** 发送聊天消息结果：更新后的会话（未读数等）+ 落库的消息 */
    record ChatSendResult(HumanChatSession session, HumanChatMessage message) {}

    /**
     * 农民请求转人工：已有打开中的会话则复用，否则新建一条 WAITING 会话。
     *
     * @param aiContext 转人工时带入的 AI 对话上下文快照，可为 null
     */
    FarmerRequestResult handleFarmerRequest(String openid, String nickname,
                                            String villageCode, String villageName,
                                            String aiContext);

    /**
     * 管理员接单：WAITING/ACTIVE → ACTIVE，记录接单人与时间
     *
     * @throws com.wqb.springboot.exception.BusinessException 会话不存在或状态不允许
     */
    AdminAcceptResult acceptSession(String adminUsername, String adminRealName, Long sessionId);

    /**
     * 发送一条聊天消息：落库 + 更新会话未读数/最后消息
     *
     * @throws com.wqb.springboot.exception.BusinessException 会话不存在/已关闭/无权发送
     */
    ChatSendResult sendChatMessage(boolean fromFarmer, String senderIdentity,
                                   String senderName, Long sessionId, String content);

    /**
     * 标记会话对某一端已读（清零对应未读数）
     *
     * @return false 表示会话不存在
     */
    boolean markRead(boolean byFarmer, Long sessionId);

    /**
     * 关闭会话
     *
     * @throws com.wqb.springboot.exception.BusinessException 会话不存在或无权关闭
     */
    HumanChatSession closeSession(Long sessionId, boolean byFarmer, String identity);

    /**
     * 当前待接单（WAITING）会话列表，用于专家上线/转人工重试时的实时推送
     */
    List<HumanChatSession> listWaitingSessions();
}
