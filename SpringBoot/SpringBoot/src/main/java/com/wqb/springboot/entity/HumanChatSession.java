package com.wqb.springboot.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 人工客服会话实体
 * <p>
 * 农民发起转人工后，创建一条会话记录，状态由等待→进行中→已关闭流转。
 * </p>
 */
@Entity
@Table(name = "human_chat_session")
public class HumanChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 农民 openid */
    @Column(nullable = false, length = 64)
    private String farmerOpenid;

    /** 农民昵称（冗余快照，会话列表展示用） */
    @Column(length = 100)
    private String farmerNickname;

    /** 所属村庄 code */
    @Column(length = 50)
    private String villageCode;

    /** 所属村庄 name */
    @Column(length = 100)
    private String villageName;

    /** AI 上下文快照（转人工时带入） */
    @Column(columnDefinition = "TEXT")
    private String aiContextSnapshot;

    /** 管理员 username（接单后赋值） */
    @Column(length = 50)
    private String adminUsername;

    /** 管理员真实姓名（冗余） */
    @Column(length = 50)
    private String adminRealName;

    /** 会话状态：WAITING 等待接单 / ACTIVE 进行中 / CLOSED 已关闭 */
    @Column(nullable = false, length = 20)
    private String status = "WAITING";

    /** 最后一条消息内容（列表预览用） */
    @Column(length = 500)
    private String lastMessage;

    /** 最后一条消息时间 */
    private LocalDateTime lastMessageTime;

    /** 未读消息数（管理员视角） */
    @Column(nullable = false)
    private Integer adminUnread = 0;

    /** 未读消息数（农民视角） */
    @Column(nullable = false)
    private Integer farmerUnread = 0;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;   // 管理员接单时间
    private LocalDateTime closedAt;    // 关闭时间

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (lastMessageTime == null) lastMessageTime = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFarmerOpenid() { return farmerOpenid; }
    public void setFarmerOpenid(String farmerOpenid) { this.farmerOpenid = farmerOpenid; }
    public String getFarmerNickname() { return farmerNickname; }
    public void setFarmerNickname(String farmerNickname) { this.farmerNickname = farmerNickname; }
    public String getVillageCode() { return villageCode; }
    public void setVillageCode(String villageCode) { this.villageCode = villageCode; }
    public String getVillageName() { return villageName; }
    public void setVillageName(String villageName) { this.villageName = villageName; }
    public String getAiContextSnapshot() { return aiContextSnapshot; }
    public void setAiContextSnapshot(String aiContextSnapshot) { this.aiContextSnapshot = aiContextSnapshot; }
    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
    public String getAdminRealName() { return adminRealName; }
    public void setAdminRealName(String adminRealName) { this.adminRealName = adminRealName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
    public LocalDateTime getLastMessageTime() { return lastMessageTime; }
    public void setLastMessageTime(LocalDateTime lastMessageTime) { this.lastMessageTime = lastMessageTime; }
    public Integer getAdminUnread() { return adminUnread; }
    public void setAdminUnread(Integer adminUnread) { this.adminUnread = adminUnread; }
    public Integer getFarmerUnread() { return farmerUnread; }
    public void setFarmerUnread(Integer farmerUnread) { this.farmerUnread = farmerUnread; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
}
