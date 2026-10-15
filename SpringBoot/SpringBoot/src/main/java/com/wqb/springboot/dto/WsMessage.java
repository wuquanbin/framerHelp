package com.wqb.springboot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * WebSocket 消息统一协议
 * <p>
 * 客户端 → 服务端 type 枚举：
 * <ul>
 *   <li><b>FARMER_REQUEST</b> 农民请求转人工（body: aiContext）</li>
 *   <li><b>ADMIN_ACCEPT</b>  管理员接单（body: sessionId）</li>
 *   <li><b>CHAT</b>          发一条聊天消息（body: {sessionId, content}）</li>
 *   <li><b>TYPING</b>        正在输入提示（body: {sessionId}）</li>
 *   <li><b>READ</b>          已读回执（body: {sessionId}）</li>
 *   <li><b>CLOSE</b>         关闭会话（body: {sessionId}）</li>
 *   <li><b>ADMIN_STATUS</b>  管理员设置在线/离线（body: {online: true/false}）</li>
 * </ul>
 *
 * 服务端 → 客户端 type 枚举：
 * <ul>
 *   <li><b>CONNECTED</b>     连接建立确认</li>
 *   <li><b>SESSION_CREATED</b> 会话已创建，正在等待（body: session）</li>
 *   <li><b>SESSION_ACCEPTED</b>管理员已接单，会话开始（body: session + adminInfo）</li>
 *   <li><b>NO_EXPERT_ONLINE</b> 当前无在线专家（body: message）</li>
 *   <li><b>NEW_CHAT</b>      收到新聊天消息（body: message）</li>
 *   <li><b>TYPING</b>        对方正在输入</li>
 *   <li><b>READ_ACK</b>      对方已读确认</li>
 *   <li><b>SESSION_CLOSED</b> 会话已关闭</li>
 *   <li><b>NEW_SESSION_NOTIFY</b> 推给所有在线管理员：有新会话等待</li>
 *   <li><b>ERROR</b>         错误信息</li>
 * </ul>
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {

    private String type;
    private Object body;
    private Long timestamp;

    public WsMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    public WsMessage(String type, Object body) {
        this.type = type;
        this.body = body;
        this.timestamp = System.currentTimeMillis();
    }

    public static WsMessage of(String type) {
        return new WsMessage(type, null);
    }

    public static WsMessage of(String type, Object body) {
        return new WsMessage(type, body);
    }

    public static WsMessage error(String msg) {
        return new WsMessage("ERROR", java.util.Map.of("message", msg));
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Object getBody() { return body; }
    public void setBody(Object body) { this.body = body; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
}
