package com.example.configcenter.repository;

import com.example.configcenter.domain.entity.FeatureFlagHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FeatureFlagHistoryRepository extends JpaRepository<FeatureFlagHistory, Long> {

    String BY_CURRENT_IDENTITY = """
            select h from FeatureFlagHistory h where h.ownerId = (
                select c.id from FeatureFlag c where c.app = :app and c.env = :env and c.name = :name
            )
            """;

    @Query(BY_CURRENT_IDENTITY + "order by h.version desc")
    List<FeatureFlagHistory> findAllByAppAndEnvAndNameOrderByVersionDesc(
            String app, String env, String name, Pageable pageable);

    @Query(BY_CURRENT_IDENTITY + "and h.version < :beforeVersion order by h.version desc")
    List<FeatureFlagHistory> findAllByAppAndEnvAndNameAndVersionLessThanOrderByVersionDesc(
            String app, String env, String name, long beforeVersion, Pageable pageable);

    @Query(BY_CURRENT_IDENTITY + "and h.version = :version")
    Optional<FeatureFlagHistory> findByAppAndEnvAndNameAndVersion(String app, String env, String name, long version);
}
