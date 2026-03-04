package com.example.configcenter.repository;

import com.example.configcenter.domain.entity.ConfigItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository 是“数据库访问层”
 * Spring Data JPA 会根据方法名自动生成 SQL
 */
public interface ConfigItemRepository extends JpaRepository<ConfigItem, Long> {

    Optional<ConfigItem> findByAppAndEnvAndConfigKey(String app, String env, String configKey);

    List<ConfigItem> findAllByAppAndEnvOrderByConfigKeyAsc(String app, String env);
}