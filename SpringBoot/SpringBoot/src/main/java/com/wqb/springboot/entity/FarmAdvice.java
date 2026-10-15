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

@Entity
@Table(name = "farm_advice")
public class FarmAdvice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    @Column(nullable = false, length = 64)
    private String title;

    @Column(nullable = false, length = 255)
    private String summary;

    // AI 生成的农事建议正文较长，使用 TEXT 存储（MySQL 已建表需配套 ALTER 扩列）
    @Column(nullable = false, columnDefinition = "TEXT")
    private String recommendation;

    @Column(nullable = false, length = 32)
    private String priorityTag;

    @Column(nullable = false, length = 32)
    private String iconKey;

    @Column(nullable = false, length = 32)
    private String adviceCode;

    @Column(nullable = false)
    private Integer priority;

    public FarmAdvice() {
    }

    public FarmAdvice(
            Village village,
            String title,
            String summary,
            String recommendation,
            String priorityTag,
            String iconKey,
            String adviceCode,
            Integer priority
    ) {
        this.village = village;
        this.title = title;
        this.summary = summary;
        this.recommendation = recommendation;
        this.priorityTag = priorityTag;
        this.iconKey = iconKey;
        this.adviceCode = adviceCode;
        this.priority = priority;
    }

    public Long getId() { return id; }
    public Village getVillage() { return village; }
    public void setVillage(Village village) { this.village = village; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    public String getPriorityTag() { return priorityTag; }
    public void setPriorityTag(String priorityTag) { this.priorityTag = priorityTag; }
    public String getIconKey() { return iconKey; }
    public void setIconKey(String iconKey) { this.iconKey = iconKey; }
    public String getAdviceCode() { return adviceCode; }
    public void setAdviceCode(String adviceCode) { this.adviceCode = adviceCode; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
}
