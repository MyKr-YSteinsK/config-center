package com.example.configcenter.service;

import com.example.configcenter.domain.entity.ConfigItem;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.dto.response.ConfigItemDto;
import com.example.configcenter.repository.ConfigItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigItemRepository repo;

    public ConfigService(ConfigItemRepository repo) {
        this.repo = repo;
    }

    /**
     * upsert：存在则更新，不存在则创建
     * version 规则：
     * - 创建：version=1
     * - 更新：version=旧值+1
     */
    @Transactional
    public ConfigItemDto upsert(UpsertConfigRequest req) {

        ConfigItem item = repo.findByAppAndEnvAndConfigKey(req.getApp(), req.getEnv(), req.getKey())
                .orElseGet(ConfigItem::new);

        boolean isCreate = (item.getId() == null);

        item.setApp(req.getApp());
        item.setEnv(req.getEnv());
        item.setConfigKey(req.getKey());
        item.setConfigValue(req.getValue());
        item.setDescription(req.getDescription());
        item.setUpdatedAt(Instant.now());

        if (isCreate) {
            item.setVersion(1);
            log.info("Config upsert(create): app={}, env={}, key={}", req.getApp(), req.getEnv(), req.getKey());
        } else {
            item.setVersion(item.getVersion() + 1);
            log.info("Config upsert(update): app={}, env={}, key={}, newVersion={}",
                    req.getApp(), req.getEnv(), req.getKey(), item.getVersion());
        }

        ConfigItem saved = repo.save(item);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<ConfigItemDto> list(String app, String env) {
        return repo.findAllByAppAndEnvOrderByConfigKeyAsc(app, env)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConfigItemDto getOne(String app, String env, String key) {
        ConfigItem item = repo.findByAppAndEnvAndConfigKey(app, env, key)
                .orElseThrow(() -> new com.example.configcenter.exception.BizException(
                        com.example.configcenter.exception.ErrorCode.NOT_FOUND,
                        "配置不存在：" + key
                ));
        return toDto(item);
    }

    private ConfigItemDto toDto(ConfigItem e) {
        return new ConfigItemDto(
                e.getApp(), e.getEnv(),
                e.getConfigKey(), e.getConfigValue(),
                e.getDescription(), e.getVersion(), e.getUpdatedAt()
        );
    }
}