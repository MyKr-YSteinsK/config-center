package com.example.configcenter.service;

import com.example.configcenter.domain.entity.ConfigItem;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.dto.response.ConfigItemDto;
import com.example.configcenter.repository.ConfigItemRepository;
import com.example.configcenter.repository.ConfigItemHistoryRepository;
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
    private final ConfigItemHistoryRepository historyRepo;

    public ConfigService(ConfigItemRepository repo, ConfigItemHistoryRepository historyRepo) {
        this.repo = repo;
        this.historyRepo = historyRepo;
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

        // 软防线：expectedVersion 不匹配就拒绝，避免“丢更新”
        if (!isCreate && req.getExpectedVersion() != null) {
            if (item.getVersion() != req.getExpectedVersion()) {
                throw new com.example.configcenter.exception.BizException(
                        com.example.configcenter.exception.ErrorCode.CONFLICT,
                        "版本冲突：当前 version=" + item.getVersion() + ", expectedVersion=" + req.getExpectedVersion()
                );
            }
        }

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
        // 审计：把“这个版本的快照”写到 history 表（append-only）
        historyRepo.save(toHistory(saved, "UPSERT", req.getOperator(), req.getReason()));

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
    public String etagForList(String app, String env) {
        // 用 key+version 生成稳定签名：只要任何 key 的 version 变化，etag 就会变
        String sig = repo.findAllByAppAndEnvOrderByConfigKeyAsc(app, env).stream()
                .map(i -> i.getConfigKey() + ":" + i.getVersion())
                .reduce("", (a, b) -> a + ";" + b);
        return EtagUtil.weakEtag(sig);
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

    private com.example.configcenter.domain.entity.ConfigItemHistory toHistory(
            ConfigItem item, String action, String operator, String reason) {

        com.example.configcenter.domain.entity.ConfigItemHistory h =
                new com.example.configcenter.domain.entity.ConfigItemHistory();

        h.setApp(item.getApp());
        h.setEnv(item.getEnv());
        h.setConfigKey(item.getConfigKey());
        h.setConfigValue(item.getConfigValue());
        h.setDescription(item.getDescription());
        h.setVersion(item.getVersion());
        h.setAction(action);
        h.setOperator(operator);
        h.setReason(reason);
        h.setCreatedAt(java.time.Instant.now());
        return h;
    }
    @org.springframework.transaction.annotation.Transactional
    public com.example.configcenter.dto.response.ConfigItemDto rollback(
            com.example.configcenter.dto.request.RollbackConfigRequest req) {

        ConfigItem current = repo.findByAppAndEnvAndConfigKey(req.getApp(), req.getEnv(), req.getKey())
                .orElseThrow(() -> new com.example.configcenter.exception.BizException(
                        com.example.configcenter.exception.ErrorCode.NOT_FOUND,
                        "配置不存在：" + req.getKey()
                ));

        long targetVer = req.getTargetVersion();

        com.example.configcenter.domain.entity.ConfigItemHistory target = historyRepo
                .findByAppAndEnvAndConfigKeyAndVersion(req.getApp(), req.getEnv(), req.getKey(), targetVer)
                .orElseThrow(() -> new com.example.configcenter.exception.BizException(
                        com.example.configcenter.exception.ErrorCode.NOT_FOUND,
                        "找不到目标历史版本：" + targetVer
                ));

        // 回滚的本质：写入一个“新版本”，但 value/desc 恢复为目标版本的快照
        current.setConfigValue(target.getConfigValue());
        current.setDescription(target.getDescription());
        current.setUpdatedAt(java.time.Instant.now());
        current.setVersion(current.getVersion() + 1);

        ConfigItem saved = repo.save(current);

        historyRepo.save(toHistory(saved, "ROLLBACK", req.getOperator(), "rollback-to=" + targetVer +
                (req.getReason() == null ? "" : (", " + req.getReason()))));

        return toDto(saved);
    }
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<com.example.configcenter.dto.response.ConfigHistoryDto> history(String app, String env, String key) {
        return historyRepo.findAllByAppAndEnvAndConfigKeyOrderByVersionDesc(app, env, key)
                .stream()
                .map(h -> new com.example.configcenter.dto.response.ConfigHistoryDto(
                        h.getApp(), h.getEnv(), h.getConfigKey(), h.getConfigValue(), h.getDescription(),
                        h.getVersion(), h.getAction(), h.getOperator(), h.getReason(), h.getCreatedAt()
                ))
                .toList();
    }

}