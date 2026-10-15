package com.wqb.springboot.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 人工客服单条消息实体
 */
@Entity
@Table(name = "human_chat_message")
public class HumanChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话 */
    @Column(nullable = false)
    private Long sessionId;

    /** 发送者类型：FARMER 或 ADMIN */
    @Column(nullable = false, length = 20)
    private String senderType;

    /** 发送者身份：openid 或 username */
    @Column(length = 64)
    private String senderIdentity;

    /** 发送者显示名（冗余） */
    @Column(length = 100)
    private String senderName;

    /** 消息内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 是否已读 */
    @Column(nullable = false)
    private Boolean readFlag = false;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getSenderType() { return senderType; }
    public void setSenderType(String senderType) { this.senderType = senderType; }
    public String getSenderIdentity() { return senderIdentity; }
    public void setSenderIdentity(String senderIdentity) { this.senderIdentity = senderIdentity; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getReadFlag() { return readFlag; }
    public void setReadFlag(Boolean readFlag) { this.readFlag = readFlag; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
