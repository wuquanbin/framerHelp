package com.wqb.springboot.service.impl;

import com.wqb.springboot.config.CacheConfig;
import com.wqb.springboot.dto.PromptTemplateDto;
import com.wqb.springboot.entity.PromptTemplate;
import com.wqb.springboot.repository.PromptTemplateRepository;
import com.wqb.springboot.service.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 提示词模板服务实现
 * <p>
 * {@code create/update/activate} 都要清缓存，但不走本类内部的 {@code @CacheEvict} 方法：
 * 注解靠代理生效，同 Bean 内互调会绕过代理、注解白写，所以这里直接拿 {@link CacheManager} 手动 evict。
 * </p>
 */
@Service
public class PromptTemplateServiceImpl implements PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateServiceImpl.class);

    private final PromptTemplateRepository repository;
    private final CacheManager cacheManager;

    public PromptTemplateServiceImpl(PromptTemplateRepository repository, CacheManager cacheManager) {
        this.repository = repository;
        this.cacheManager = cacheManager;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_PROMPT_TEMPLATE, key = "#code")
    public String resolve(String code, String fallback) {
        try {
            return repository.findTopByCodeAndEnabledTrueOrderByVersionDesc(code)
                    .map(PromptTemplate::getContent)
                    .filter(c -> c != null && !c.isBlank())
                    .orElse(fallback);
        } catch (Exception e) {
            // 表还没建、Redis 异常、SQL 出错，都不该让 AI 对话挂掉
            log.warn("读取提示词模板失败，改用内置兜底提示词。code={}, 原因={}", code, e.getMessage());
            return fallback;
        }
    }

    @Override
    public List<PromptTemplate> listByCode(String code) {
        return repository.findByCodeOrderByVersionDesc(code);
    }

    @Override
    public List<PromptTemplate> listAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(PromptTemplate::getCode)
                        .thenComparing(Comparator.comparing(PromptTemplate::getVersion).reversed()))
                .toList();
    }

    @Override
    @Transactional
    public PromptTemplate create(PromptTemplateDto.UpsertRequest request) {
        int nextVersion = repository.findByCodeOrderByVersionDesc(request.code()).stream()
                .mapToInt(p -> p.getVersion() == null ? 0 : p.getVersion())
                .max().orElse(0) + 1;

        PromptTemplate entity = new PromptTemplate();
        entity.setCode(request.code());
        entity.setName(request.name());
        entity.setVersion(nextVersion);
        entity.setContent(request.content());
        entity.setDescription(request.description());
        // 新建即启用：省掉「建完还要点一下启用」的步骤，同编码旧版本会自动停用
        repository.disableAllByCode(request.code());
        entity.setEnabled(true);

        PromptTemplate saved = repository.save(entity);
        evict(request.code());
        log.info("新增提示词模板 code={}, version={}", request.code(), nextVersion);
        return saved;
    }

    @Override
    @Transactional
    public PromptTemplate update(Long id, PromptTemplateDto.UpsertRequest request) {
        PromptTemplate entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("提示词模板不存在: " + id));
        if (request.name() != null && !request.name().isBlank()) {
            entity.setName(request.name());
        }
        if (request.content() != null && !request.content().isBlank()) {
            entity.setContent(request.content());
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        PromptTemplate saved = repository.save(entity);
        evict(entity.getCode());
        log.info("更新提示词模板 id={}, code={}, version={}", id, entity.getCode(), entity.getVersion());
        return saved;
    }

    @Override
    @Transactional
    public PromptTemplate activate(Long id) {
        PromptTemplate target = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("提示词模板不存在: " + id));
        repository.disableAllByCode(target.getCode());
        target.setEnabled(true);
        PromptTemplate saved = repository.save(target);
        evict(target.getCode());
        log.info("启用提示词模板 code={}, version={}", target.getCode(), target.getVersion());
        return saved;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        PromptTemplate entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("提示词模板不存在: " + id));
        repository.deleteById(id);
        evict(entity.getCode());
        log.info("删除提示词模板 id={}, code={}, version={}", id, entity.getCode(), entity.getVersion());
    }

    /** 手动清缓存，避免同 Bean 内调用绕过代理导致 @CacheEvict 失效 */
    private void evict(String code) {
        try {
            Cache cache = cacheManager.getCache(CacheConfig.CACHE_PROMPT_TEMPLATE);
            if (cache != null) {
                cache.evict(code);
            }
        } catch (Exception e) {
            log.warn("清空提示词缓存失败（Redis 不可用时会走到这里，忽略）: {}", e.getMessage());
        }
    }
}
