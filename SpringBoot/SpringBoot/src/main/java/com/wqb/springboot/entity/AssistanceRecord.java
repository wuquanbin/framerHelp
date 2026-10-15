package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

@Entity
@Table(name = "assistance_record")
public class AssistanceRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String assistanceType;

    @Column(length = 255)
    private String description;

    @Column(length = 20)
    private String status;

    /**
     * 由哪条农情上报闭环生成（自动建档时写入；手工创建的记录为 null）。
     * 普通列不加外键约束：上报按业务约定可独立保留，避免台账被上报删除连带。
     */
    @Column(name = "report_id")
    private Long reportId;

    /** 关联农情上报标题，仅列表展示时由服务层填充，不落库 */
    @Transient
    private String reportTitle;

    public AssistanceRecord() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getAssistanceType() { return assistanceType; }
    public void setAssistanceType(String assistanceType) { this.assistanceType = assistanceType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getReportTitle() { return reportTitle; }
    public void setReportTitle(String reportTitle) { this.reportTitle = reportTitle; }
}
