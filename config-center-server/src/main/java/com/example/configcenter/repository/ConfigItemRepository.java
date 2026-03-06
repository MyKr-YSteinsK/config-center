package com.example.configcenter.repository;

import com.example.configcenter.domain.entity.ConfigItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 当前配置表仓库。
 * Spring Data JPA 会根据方法名自动拼查询，demo 里非常省事。
 */
public interface ConfigItemRepository extends JpaRepository<ConfigItem, Long> {

    Optional<ConfigItem> findByAppAndEnvAndConfigKey(String app, String env, String configKey);

    List<ConfigItem> findAllByAppAndEnvOrderByConfigKeyAsc(String app, String env);

    // watch 场景要拿某个 app/env 的最新版本号，所以单独补一个 maxVersion 查询更直接。
    @org.springframework.data.jpa.repository.Query("""
        select coalesce(max(c.version), 0)
        from ConfigItem c
        where c.app = :app and c.env = :env
        """)
    long maxVersion(@org.springframework.data.repository.query.Param("app") String app,
                    @org.springframework.data.repository.query.Param("env") String env);
}
