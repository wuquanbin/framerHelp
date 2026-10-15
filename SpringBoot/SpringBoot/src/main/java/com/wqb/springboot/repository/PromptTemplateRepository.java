package com.wqb.springboot.repository;

import com.wqb.springboot.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 提示词模板数据访问
 */
public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    /**
     * 取某个编码下正在生效的模板：启用中、版本号最大的那条
     */
    Optional<PromptTemplate> findTopByCodeAndEnabledTrueOrderByVersionDesc(String code);

    List<PromptTemplate> findByCodeOrderByVersionDesc(String code);

    /**
     * 启用指定版本：先把同 code 下所有版本停用，再启用目标那条 —— 两步放在同一个方法里是刻意的，
     * 避免同一 code 出现两条 enabled（否则「取版本最大那条」会和页面上看到的不一致）。
     */
    @Modifying
    @Query("UPDATE PromptTemplate p SET p.enabled = false WHERE p.code = :code")
    int disableAllByCode(@Param("code") String code);
}
