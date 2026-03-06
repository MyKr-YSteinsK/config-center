package com.example.configcenter.repository;

import com.example.configcenter.domain.entity.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 当前 feature flag 仓库。
 */
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {

    Optional<FeatureFlag> findByAppAndEnvAndName(String app, String env, String name);

    List<FeatureFlag> findAllByAppAndEnvOrderByNameAsc(String app, String env);
}
