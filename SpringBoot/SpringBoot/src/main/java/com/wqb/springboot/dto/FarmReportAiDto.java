package com.wqb.springboot.dto;

/**
 * 农情上报智能受理相关传输对象
 */
public class FarmReportAiDto {

    /**
     * 受理请求
     *
     * @param rawText     农户原话（一句话或一段话，可能很口语、很随意）
     * @param villageCode 所在村镇，用于限定作物范围和检索本地资料
     */
    public record ParseRequest(String rawText, String villageCode) {}

    /**
     * 受理结果（工单草稿）
     *
     * @param title           规范化后的标题
     * @param cropName        识别出的作物名
     * @param reportType      上报类型：pest 病虫害 / disaster 灾害 / growth 生长 / help 求助
     * @param urgency         紧急度：low / medium / high
     * @param description     整理后的问题描述
     * @param suggestedAction 初步处置建议（依据知识库生成，供专家复核时参考）
     * @param aiParsed        是否真的由模型产出（false 表示走的是降级兜底，没有调模型）
     */
    public record ParseResult(
            String title,
            String cropName,
            String reportType,
            String urgency,
            String description,
            String suggestedAction,
            boolean aiParsed
    ) {}
}
