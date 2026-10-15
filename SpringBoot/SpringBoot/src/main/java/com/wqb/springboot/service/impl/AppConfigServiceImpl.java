package com.wqb.springboot.service.impl;

import com.wqb.springboot.config.CacheConfig;
import com.wqb.springboot.entity.AppConfig;
import com.wqb.springboot.repository.AppConfigRepository;
import com.wqb.springboot.service.AppConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link AppConfigService} 的实现
 * <p>
 * 每个 getter 各自带缓存、各自解析，彼此不互相调用 —— 这是有意的：
 * Spring 的缓存是代理实现的，同一个 Bean 内部方法互调不会经过代理，
 * 缓存也就不会生效。所以这里让每个 public 方法都直接查库 + 各自缓存，
 * 共用的只有那个不带缓存的私有 {@link #raw}。
 * </p>
 */
@Service
public class AppConfigServiceImpl implements AppConfigService {

    private static final Logger log = LoggerFactory.getLogger(AppConfigServiceImpl.class);

    private final AppConfigRepository appConfigRepository;

    public AppConfigServiceImpl(AppConfigRepository appConfigRepository) {
        this.appConfigRepository = appConfigRepository;
    }

    /** 直接查库取原始值，不带缓存（供本类各 getter 复用） */
    private Optional<String> raw(String key) {
        return appConfigRepository.findByConfigKey(key)
                .map(AppConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank());
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_APP_CONFIG, key = "'str:' + #key")
    public String get(String key, String defaultValue) {
        return raw(key).orElse(defaultValue);
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_APP_CONFIG, key = "'bool:' + #key")
    public boolean getBool(String key, boolean defaultValue) {
        return raw(key)
                .map(v -> "true".equalsIgnoreCase(v)
                        || "1".equals(v)
                        || "yes".equalsIgnoreCase(v)
                        || "on".equalsIgnoreCase(v))
                .orElse(defaultValue);
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_APP_CONFIG, key = "'int:' + #key")
    public int getInt(String key, int defaultValue) {
        Optional<String> v = raw(key);
        if (v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.get().trim());
        } catch (NumberFormatException e) {
            // 配置被填成了非数字：不要因此让整个功能挂掉，回退默认并记录一笔
            log.warn("[配置] {} 的值「{}」不是整数，回退默认值 {}", key, v.get(), defaultValue);
            return defaultValue;
        }
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_APP_CONFIG, key = "'dbl:' + #key")
    public double getDouble(String key, double defaultValue) {
        Optional<String> v = raw(key);
        if (v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v.get().trim());
        } catch (NumberFormatException e) {
            log.warn("[配置] {} 的值「{}」不是数字，回退默认值 {}", key, v.get(), defaultValue);
            return defaultValue;
        }
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CACHE_APP_CONFIG, allEntries = true)
    public void evictAll() {
        log.info("[配置] 缓存已清空，新配置下次读取时生效");
    }
}
