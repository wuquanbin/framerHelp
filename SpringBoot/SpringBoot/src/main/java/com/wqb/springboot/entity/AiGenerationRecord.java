package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * AI 控制台的生成记录
 * <p>
 * 落库而不是只存浏览器 localStorage：换设备不丢，能追溯「谁在什么时候让 AI 生成过什么」，
 * 也能看到哪些被发布成了农事建议。
 * </p>
 */
@Entity
@Table(
        name = "ai_generation_record",
        indexes = {
                @Index(name = "idx_aigen_created", columnList = "created_at")
        }
)
public class AiGenerationRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人后台账号（从 Spring Security 上下文取，不信前端传的值） */
    @Column(length = 100)
    private String operatorName;

    /** 使用的工具名，如「生成农事建议」「自定义指令」 */
    @Column(nullable = false, length = 64)
    private String toolTitle;

    /** 生成时选定的村镇编码，可为空 */
    @Column(length = 32)
    private String villageCode;

    /** 提交给模型的指令（工具预设 prompt 或用户自定义输入） */
    @Column(columnDefinition = "TEXT")
    private String prompt;

    /** AI 生成的内容（Markdown），可能很长 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 是否已发布为农事建议 */
    @Column(nullable = false)
    private Boolean published = false;

    /** 发布后对应的农事建议 id */
    private Long publishedAdviceId;

    public AiGenerationRecord() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public String getToolTitle() { return toolTitle; }
    public void setToolTitle(String toolTitle) { this.toolTitle = toolTitle; }
    public String getVillageCode() { return villageCode; }
    public void setVillageCode(String villageCode) { this.villageCode = villageCode; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getPublished() { return published; }
    public void setPublished(Boolean published) { this.published = published; }
    public Long getPublishedAdviceId() { return publishedAdviceId; }
    public void setPublishedAdviceId(Long publishedAdviceId) { this.publishedAdviceId = publishedAdviceId; }
}
