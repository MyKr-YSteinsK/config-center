package com.example.configcenter.repository;

import com.example.configcenter.domain.entity.ConfigItemHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 配置历史查询仓库。
 */
public interface ConfigItemHistoryRepository extends JpaRepository<ConfigItemHistory, Long> {

    List<ConfigItemHistory> findAllByAppAndEnvAndConfigKeyOrderByVersionDesc(String app, String env, String configKey);

    Optional<ConfigItemHistory> findByAppAndEnvAndConfigKeyAndVersion(String app, String env, String configKey, long version);
}
