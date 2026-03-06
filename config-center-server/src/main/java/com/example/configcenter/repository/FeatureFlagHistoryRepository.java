package com.example.configcenter.repository;

import com.example.configcenter.domain.entity.FeatureFlagHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * feature 历史仓库。
 */
public interface FeatureFlagHistoryRepository extends JpaRepository<FeatureFlagHistory, Long> {

    List<FeatureFlagHistory> findAllByAppAndEnvAndNameOrderByVersionDesc(String app, String env, String name);

    Optional<FeatureFlagHistory> findByAppAndEnvAndNameAndVersion(String app, String env, String name, long version);
}
