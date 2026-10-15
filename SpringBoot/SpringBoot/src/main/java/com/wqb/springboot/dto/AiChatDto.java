package com.wqb.springboot.dto;

import java.util.List;

public final class AiChatDto {

    private AiChatDto() {
    }

    public record ChatRequest(
            String message,
            String villageCode,
            List<ChatMessage> history
    ) {
    }

    public record ChatMessage(
            String role,
            String content
    ) {
    }

    public record ChatResponse(
            String reply,
            String villageCode,
            String villageName
    ) {
    }

    /**
     * 评测模式：逐级打开「提示词 / 天气上下文 / 知识库」三样东西，
     * 用来量化每一层对回答质量到底贡献了多少。
     * <p>
     * 面向论文的实验章节，不参与线上对话：正常对话固定走 {@link #FULL}。
     * </p>
     */
    public enum EvalMode {
        /** 裸模型：只有用户问题，什么附加信息都不给 */
        BARE,
        /** 只加系统提示词 */
        PROMPT,
        /** 系统提示词 + 天气数据上下文（数据库里的实况/预报/预警等） */
        CONTEXT,
        /** 完整版：再叠加 RAGFlow 知识库召回的农技片段 */
        FULL
    }

    /** 跑一次评测的请求 */
    public record EvalRunRequest(
            String message,
            String villageCode,
            /** 模式名，取值见 {@link EvalMode}；为空按 FULL 处理 */
            String mode
    ) {
    }

    /** 评测专用响应：比 ChatResponse 多带几个跑分需要的客观指标 */
    public record EvalResponse(
            EvalMode mode,
            String reply,
            /** 生成耗时（毫秒） */
            long elapsedMs,
            /** 是否命中了知识库片段 */
            boolean knowledgeUsed,
            /** 回复里是否出现了来源标注 */
            boolean cited
    ) {
    }
}
