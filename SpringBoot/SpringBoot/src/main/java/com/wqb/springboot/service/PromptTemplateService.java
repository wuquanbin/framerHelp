package com.wqb.springboot.service;

import com.wqb.springboot.dto.PromptTemplateDto;
import com.wqb.springboot.entity.PromptTemplate;

import java.util.List;

/**
 * 提示词模板服务
 * <p>
 * 取值策略与 {@link AppConfigService} 一致：<b>表里有生效版本就用表里的，没有就用调用方给的兜底文本</b>。
 * 表被清空、Redis 挂了、模板被误停用，都退回代码里的默认提示词，AI 对话不会因此瘫痪。
 * </p>
 */
public interface PromptTemplateService {

    /** 农业气象对话的系统提示词 */
    String CODE_CHAT_SYSTEM = "agri.chat.system";
    /** 命中知识库时追加的使用规则（要求标注来源、不许编造） */
    String CODE_KNOWLEDGE_RULES = "agri.chat.knowledge.rules";

    /**
     * 取生效中的模板正文
     *
     * @param code     模板编码
     * @param fallback 表里没有生效版本时的兜底文本（通常传代码里的常量）
     * @return 模板正文，永不返回 null
     */
    String resolve(String code, String fallback);

    /** 按编码列出所有版本（版本号倒序） */
    List<PromptTemplate> listByCode(String code);

    /** 列出全部模板（按编码、版本倒序） */
    List<PromptTemplate> listAll();

    /** 新增一个版本（版本号自动取当前最大 +1） */
    PromptTemplate create(PromptTemplateDto.UpsertRequest request);

    /** 编辑指定版本的正文与说明 */
    PromptTemplate update(Long id, PromptTemplateDto.UpsertRequest request);

    /** 启用指定版本（同编码下其余版本自动停用） */
    PromptTemplate activate(Long id);

    /** 删除指定版本 */
    void delete(Long id);
}
