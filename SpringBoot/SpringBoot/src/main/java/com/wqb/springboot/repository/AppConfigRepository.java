package com.wqb.springboot.repository;

import com.wqb.springboot.entity.AppConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppConfigRepository extends JpaRepository<AppConfig, Long> {
    Optional<AppConfig> findByConfigKey(String configKey);
    List<AppConfig> findByCategoryOrderByConfigKey(String category);
    List<AppConfig> findAllByOrderByCategoryAscConfigKeyAsc();
}
