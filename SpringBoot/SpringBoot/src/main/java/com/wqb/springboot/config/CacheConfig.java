package com.wqb.springboot.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Map;

/**
 * 缓存配置（Redis 版）
 * <p>
 * 缓存独立于应用进程：后端重启后仍有效，多实例也能共享同一份。
 * </p>
 *
 * <p><b>TTL 分档：</b>村镇 30 分钟；作物档案/农事建议/灾害记录 10 分钟；
 * 概览/农情报/后台 KPI 2 分钟（含实时数值，短一点避免"刷新数字不变"的观感问题）。
 * 后台增删改时由 {@code @CacheEvict} 主动失效，不依赖 TTL 兜底。</p>
 *
 * <p><b>降级：</b>Redis 没启动或密码不对时读写会抛异常，这里配了「容忍失败」的
 * {@link CacheErrorHandler}：只记日志、不往外抛，Spring 改为直接执行原方法，
 * 所以 Redis 挂了只是慢一点，不会让接口 500。</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    public static final String CACHE_VILLAGES = "villages";
    public static final String CACHE_CROP_PROFILES = "cropProfiles";
    public static final String CACHE_FARM_ADVICES = "farmAdvices";
    public static final String CACHE_DISASTERS = "disasterRecords";
    public static final String CACHE_OVERVIEW = "dashboardOverview";
    public static final String CACHE_AGRI_REPORT = "agriReport";
    public static final String CACHE_ADMIN_STATS = "adminStats";
    /** app_config 读取缓存：改配置时由 AppConfigService.evictAll() 主动清空 */
    public static final String CACHE_APP_CONFIG = "appConfig";
    /** 提示词模板缓存：新建/编辑/启用版本时由 PromptTemplateServiceImpl 手动 evict */
    public static final String CACHE_PROMPT_TEMPLATE = "promptTemplate";

    /** 所有缓存 key 的统一前缀，便于在 Redis 里区分和批量清理 */
    private static final String KEY_PREFIX = "agri:";

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // JSON 序列化（Redis 里能直接看懂存了什么，默认的 JDK 序列化是乱码二进制）。
        // ⚠️ 不能用裸 new ObjectMapper()：缓存对象里有 LocalDateTime，它不认 Java 8 时间类型会抛异常 → 接口 500，
        //    所以要 findAndRegisterModules()，并关掉 WRITE_DATES_AS_TIMESTAMPS 让时间在 Redis 里是可读字符串。
        ObjectMapper cacheMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // ⚠️ 还必须开启"默认类型信息"：把 @Cacheable 的返回值反序列化回原类型。
        //    不开的话，JSON 读出来只会是 LinkedHashMap，赋值时抛
        //    "LinkedHashMap cannot be cast to ...DashboardOverview"。
        //    必须用 EVERYTHING 而不是 NON_FINAL：本项目的 DTO 是 Java record（final 类），
        //    NON_FINAL 会跳过 final 类型 → 根对象不写 @class → 读回来照样失败。
        //    这也是 GenericJackson2JsonRedisSerializer 无参构造的默认策略。
        cacheMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(KEY_PREFIX)
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(cacheMapper)))
                .disableCachingNullValues()
                .entryTtl(Duration.ofMinutes(5));

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                CACHE_VILLAGES, defaults.entryTtl(Duration.ofMinutes(30)),
                CACHE_CROP_PROFILES, defaults.entryTtl(Duration.ofMinutes(10)),
                CACHE_FARM_ADVICES, defaults.entryTtl(Duration.ofMinutes(10)),
                CACHE_DISASTERS, defaults.entryTtl(Duration.ofMinutes(10)),
                CACHE_OVERVIEW, defaults.entryTtl(Duration.ofMinutes(2)),
                CACHE_AGRI_REPORT, defaults.entryTtl(Duration.ofMinutes(2)),
                CACHE_ADMIN_STATS, defaults.entryTtl(Duration.ofMinutes(2)),
                CACHE_APP_CONFIG, defaults.entryTtl(Duration.ofMinutes(2)),
                CACHE_PROMPT_TEMPLATE, defaults.entryTtl(Duration.ofMinutes(2))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    /**
     * 缓存异常不打断业务：Redis 不可用时降级为直接查库
     */
    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("[缓存] 读取失败，降级为直接查库。cache={}, key={}, 原因={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("[缓存] 写入失败，忽略。cache={}, key={}, 原因={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("[缓存] 失效失败，忽略。cache={}, key={}, 原因={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("[缓存] 清空失败，忽略。cache={}, 原因={}",
                        cache.getName(), exception.getMessage());
            }
        };
    }
}
