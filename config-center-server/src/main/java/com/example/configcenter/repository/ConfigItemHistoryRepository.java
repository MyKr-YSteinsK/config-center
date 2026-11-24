package com.example.configcenter.repository;

import com.example.configcenter.domain.entity.ConfigItemHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ConfigItemHistoryRepository extends JpaRepository<ConfigItemHistory, Long> {

    String BY_CURRENT_IDENTITY = """
            select h from ConfigItemHistory h where h.ownerId = (
                select c.id from ConfigItem c where c.app = :app and c.env = :env and c.configKey = :configKey
            )
            """;

    @Query(BY_CURRENT_IDENTITY + "order by h.version desc")
    List<ConfigItemHistory> findAllByAppAndEnvAndConfigKeyOrderByVersionDesc(
            String app, String env, String configKey, Pageable pageable);

    @Query(BY_CURRENT_IDENTITY + "and h.version < :beforeVersion order by h.version desc")
    List<ConfigItemHistory> findAllByAppAndEnvAndConfigKeyAndVersionLessThanOrderByVersionDesc(
            String app, String env, String configKey, long beforeVersion, Pageable pageable);

    @Query(BY_CURRENT_IDENTITY + "and h.version = :version")
    Optional<ConfigItemHistory> findByAppAndEnvAndConfigKeyAndVersion(String app, String env, String configKey, long version);
}
