package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 气象预警
 * <p>索引：① 首页按村镇取生效预警 → (village_id, status)；② 同步任务按 expires_at 清理过期预警 → expires_at 单独建索引。</p>
 */
@Entity
@Table(
    name = "warning_notice",
    indexes = {
        @Index(name = "idx_warn_village_status", columnList = "village_id, status"),
        @Index(name = "idx_warn_expires", columnList = "expires_at")
    }
)
public class WarningNotice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "village_id")
    private Village village;

    @Column(nullable = false, length = 32)
    private String warningType;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 255)
    private String impactArea;

    @Column(columnDefinition = "TEXT")
    private String defenseGuidance;

    public WarningNotice() {
    }

    public WarningNotice(
            Village village,
            String warningType,
            String severity,
            String title,
            String content,
            LocalDateTime issuedAt,
            LocalDateTime expiresAt,
            String status
    ) {
        this.village = village;
        this.warningType = warningType;
        this.severity = severity;
        this.title = title;
        this.content = content;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.status = status;
    }

    public WarningNotice(
            Village village,
            String warningType,
            String severity,
            String title,
            String content,
            LocalDateTime issuedAt,
            LocalDateTime expiresAt,
            String status,
            String impactArea,
            String defenseGuidance
    ) {
        this.village = village;
        this.warningType = warningType;
        this.severity = severity;
        this.title = title;
        this.content = content;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.status = status;
        this.impactArea = impactArea;
        this.defenseGuidance = defenseGuidance;
    }

    public Long getId() { return id; }
    public Village getVillage() { return village; }
    public void setVillage(Village village) { this.village = village; }
    public String getWarningType() { return warningType; }
    public void setWarningType(String warningType) { this.warningType = warningType; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getImpactArea() { return impactArea; }
    public void setImpactArea(String impactArea) { this.impactArea = impactArea; }
    public String getDefenseGuidance() { return defenseGuidance; }
    public void setDefenseGuidance(String defenseGuidance) { this.defenseGuidance = defenseGuidance; }

    public String getWarningTypeCn() {
        if (warningType == null) return "天气预警";
        return switch (warningType) {
            case "rainstorm" -> "暴雨";
            case "wind" -> "大风";
            case "thunder" -> "雷电";
            case "heat" -> "高温";
            case "cold" -> "寒潮";
            case "fog" -> "大雾";
            case "typhoon" -> "台风";
            case "drought" -> "干旱";
            case "snow" -> "降雪";
            case "hail" -> "冰雹";
            case "frost" -> "霜冻";
            default -> "天气预警";
        };
    }
}
