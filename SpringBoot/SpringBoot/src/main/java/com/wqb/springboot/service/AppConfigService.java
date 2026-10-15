package com.wqb.springboot.service;

/**
 * 系统配置读取服务（读 {@code app_config} 表）
 * <p>
 * 存在的意义：后台「系统设置」页改的配置必须真的能影响运行时行为。
 * 在此之前 {@code app_config} 只有增删改查，没有任何业务代码读它 ——
 * 在页面上把同步间隔从 30 改成 5、换 AI 模型，运行时毫无变化，是个假控制台。
 * 现在天气同步、AI 调用、推送开关都从这里取值。
 * </p>
 *
 * <p>取值策略：<b>表里有就用表里的，没有就用调用方给的默认值</b>。
 * 表为空、Redis 挂了、配置被误删，都退回代码里的默认行为，不会因为配置缺失导致功能瘫痪。</p>
 */
public interface AppConfigService {

    /** 是否启用自动天气同步（布尔） */
    String K_SYNC_ENABLED = "weather.sync.enabled";
    /** 天气同步间隔（分钟） */
    String K_SYNC_INTERVAL = "weather.sync.interval";
    /** 是否启用预警推送（布尔） */
    String K_WARNING_PUSH = "warning.push.enabled";
    /** 每日农事建议推送时间（HH:mm） */
    String K_ADVICE_PUSH_TIME = "farm.advice.push.time";
    /** AI 模型名称 */
    String K_AI_MODEL = "ai.model";
    /** AI 服务地址（OpenAI 兼容端点） */
    String K_AI_BASE_URL = "ai.base.url";
    /** AI 服务密钥，云端服务必填 */
    String K_AI_API_KEY = "ai.api.key";
    /** AI 温度参数 */
    String K_AI_TEMPERATURE = "ai.temperature";
    /** AI 最大生成 token */
    String K_AI_MAX_TOKENS = "ai.max.tokens";
    /** 系统版本（展示用） */
    String K_SYSTEM_VERSION = "system.version";

    /** 是否启用 RAGFlow 农业知识库检索（布尔，关闭时 AI 行为与接入前完全一致） */
    String K_RAG_ENABLED = "rag.enabled";
    /** RAGFlow HTTP API 根地址 */
    String K_RAG_BASE_URL = "rag.base.url";
    /** RAGFlow API Key */
    String K_RAG_API_KEY = "rag.api.key";
    /** RAGFlow 知识库（dataset）ID */
    String K_RAG_DATASET_ID = "rag.dataset.id";
    /** 知识库召回条数 */
    String K_RAG_TOP_K = "rag.top.k";
    /** 知识库相似度阈值 */
    String K_RAG_SCORE_THRESHOLD = "rag.score.threshold";

    /**
     * 取字符串配置
     *
     * @param key          配置键
     * @param defaultValue 表里没有该键时的回退值
     */
    String get(String key, String defaultValue);

    /** 取布尔配置，识别 true/1/yes/on（忽略大小写） */
    boolean getBool(String key, boolean defaultValue);

    /** 取整数配置，解析失败回退默认值 */
    int getInt(String key, int defaultValue);

    /** 取小数配置，解析失败回退默认值 */
    double getDouble(String key, double defaultValue);

    /**
     * 清空配置缓存。后台改完配置后调用，让新值立刻生效，不必等 TTL。
     */
    void evictAll();
}
