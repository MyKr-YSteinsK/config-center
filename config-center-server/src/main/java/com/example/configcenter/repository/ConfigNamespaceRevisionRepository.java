package com.example.configcenter.repository;

import com.example.configcenter.domain.entity.ConfigNamespaceRevision;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ConfigNamespaceRevisionRepository extends JpaRepository<ConfigNamespaceRevision, Long> {

    Optional<ConfigNamespaceRevision> findByAppAndEnv(String app, String env);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ConfigNamespaceRevision r where r.app = :app and r.env = :env")
    Optional<ConfigNamespaceRevision> findForUpdate(@Param("app") String app, @Param("env") String env);
}
