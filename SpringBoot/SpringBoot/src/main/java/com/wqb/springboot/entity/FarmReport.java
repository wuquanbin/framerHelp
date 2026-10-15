package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 农情上报
 * <p>
 * 索引说明：列表按村镇筛选（管理员看本村镇）、「我的上报」按 openid 查，
 * 后台还常按状态过滤，故建 (village_code, status) 与 (openid) 两个索引。
 * </p>
 */
@Entity
@Table(
    name = "farm_report",
    indexes = {
        @Index(name = "idx_report_village_status", columnList = "village_code, status"),
        @Index(name = "idx_report_openid", columnList = "openid")
    }
)
public class FarmReport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String openid;

    @Column(length = 64)
    private String nickname;

    @Column(length = 255)
    private String avatarUrl;

    @Column(nullable = false, length = 32)
    private String villageCode;

    @Column(nullable = false, length = 32)
    private String villageName;

    /** 上报类型: pest-病虫害, disaster-灾损, growth-长势, help-求助, other-其他 */
    @Column(nullable = false, length = 20)
    private String reportType;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(length = 1000)
    private String imageUrls;

    /** 关联作物名称 */
    @Column(length = 32)
    private String cropName;

    /** 关联生长阶段 */
    @Column(length = 32)
    private String growthStage;

    /** 处理状态: pending-待处理, processing-处理中, resolved-已解决, closed-已关闭 */
    @Column(nullable = false, length = 20)
    private String status = "pending";

    /** 紧急程度: low-一般, medium-较急, high-紧急 */
    @Column(length = 10)
    private String urgency = "low";

    /**
     * 提交时是否经过 AI 智能受理
     * <p>
     * 用来在后台区分「农户自己填的」和「模型整理过的工单」——
     * 受理是辅助不是替代，标出来专家才知道哪些字段需要重点复核。
     * </p>
     */
    @Column(nullable = false)
    private Boolean aiParsed = false;

    /** AI 受理给出的初步处置建议（TEXT，供专家复核时参考，不直接下发给农户） */
    @Column(columnDefinition = "TEXT")
    private String aiSuggestedAction;

    public FarmReport() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getVillageCode() { return villageCode; }
    public void setVillageCode(String villageCode) { this.villageCode = villageCode; }
    public String getVillageName() { return villageName; }
    public void setVillageName(String villageName) { this.villageName = villageName; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImageUrls() { return imageUrls; }
    public void setImageUrls(String imageUrls) { this.imageUrls = imageUrls; }
    public String getCropName() { return cropName; }
    public void setCropName(String cropName) { this.cropName = cropName; }
    public String getGrowthStage() { return growthStage; }
    public void setGrowthStage(String growthStage) { this.growthStage = growthStage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getUrgency() { return urgency; }
    public void setUrgency(String urgency) { this.urgency = urgency; }
    public Boolean getAiParsed() { return aiParsed; }
    public void setAiParsed(Boolean aiParsed) { this.aiParsed = aiParsed; }
    public String getAiSuggestedAction() { return aiSuggestedAction; }
    public void setAiSuggestedAction(String aiSuggestedAction) { this.aiSuggestedAction = aiSuggestedAction; }
}
