package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 提示词模板
 * <p>提示词能在后台随时改、留版本、可复现 ——「第几版提示词跑出第几组数据」是做提示词对照实验的前提。</p>
 *
 * <p>多版本模型：同一个 {@code code} 可以有多条记录，{@code version} 递增，
 * 只有 {@code enabled=true} 且版本号最大的那条生效；回滚把新版本停用即可，历史版本不会丢。</p>
 */
@Entity
@Table(
        name = "prompt_template",
        uniqueConstraints = @UniqueConstraint(name = "uk_prompt_code_version", columnNames = {"code", "version"}),
        indexes = @Index(name = "idx_prompt_code", columnList = "code")
)
public class PromptTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模板编码，业务代码按它取，如 {@code agri.chat.system} */
    @Column(nullable = false, length = 64)
    private String code;

    /** 展示名，如「农业气象助手-系统提示词」 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 版本号，同一 code 下递增 */
    @Column(nullable = false)
    private Integer version = 1;

    /** 模板正文 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 变更说明，便于回看「第 3 版改了什么」 */
    @Column(length = 200)
    private String description;

    /** 是否启用；同一 code 下取「启用中且版本最大」的那条 */
    @Column(nullable = false)
    private Boolean enabled = true;

    public PromptTemplate() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
