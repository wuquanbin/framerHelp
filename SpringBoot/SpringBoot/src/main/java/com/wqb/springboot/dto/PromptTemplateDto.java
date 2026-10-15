package com.wqb.springboot.dto;

/**
 * 提示词模板相关传输对象
 */
public class PromptTemplateDto {

    /**
     * 新建 / 编辑模板的请求体
     *
     * @param code        模板编码
     * @param name        展示名
     * @param content     模板正文
     * @param description 变更说明
     */
    public record UpsertRequest(String code, String name, String content, String description) {}
}
