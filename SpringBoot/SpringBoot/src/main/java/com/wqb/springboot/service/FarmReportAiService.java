package com.wqb.springboot.service;

import com.wqb.springboot.dto.FarmReportAiDto;

/**
 * 农情上报智能受理
 * <p>
 * 农户提交上报时往往只写一两句口语（"我地里叶子黄了，最近又下雨"），
 * 村镇管理员要逐条读、判断类型、定紧急度，才能分派给专家——这条链路的瓶颈在人工分拣。
 * 这里让模型先把原话整理成结构化工单草稿（作物、类型、紧急度、规范化描述、初步建议），
 * 专家在后台看一眼确认或修改即可，把「读懂一句话」的成本从人转到模型。
 * </p>
 */
public interface FarmReportAiService {

    /** 受理提示词编码 */
    String CODE_FARM_REPORT_PARSE = "farm.report.parse";

    /**
     * 把农户原话解析成工单草稿
     *
     * @param request 原话 + 村镇
     * @return 结构化草稿；模型不可用时会降级返回「原话直填」的结果（aiParsed=false），绝不抛异常
     */
    FarmReportAiDto.ParseResult parse(FarmReportAiDto.ParseRequest request);
}
