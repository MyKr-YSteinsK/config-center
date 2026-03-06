package com.example.configcenter.service;

import com.example.configcenter.domain.entity.ConfigItem;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.dto.response.ConfigItemDto;
import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import com.example.configcenter.repository.ConfigItemHistoryRepository;
import com.example.configcenter.repository.ConfigItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigItemRepository repo;
    private final ConfigItemHistoryRepository historyRepo;
    private final ConfigWatchNotifier notifier;

    public ConfigService(ConfigItemRepository repo,
                         ConfigItemHistoryRepository historyRepo,
                         ConfigWatchNotifier notifier) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.notifier = notifier;
    }

    /**
     * upsert：有就更新，没有就创建。
     * 版本规则很朴素，但非常稳定：创建=1，更新=旧值+1。
     */
    @Transactional
    public ConfigItemDto upsert(UpsertConfigRequest req) {

        ConfigItem item = repo.findByAppAndEnvAndConfigKey(req.getApp(), req.getEnv(), req.getKey())
                .orElseGet(ConfigItem::new);

        boolean isCreate = (item.getId() == null);

        // expectedVersion 不匹配时直接拒绝，防止“我改的是旧页面上看到的数据”这种典型覆盖问题。
        if (!isCreate && req.getExpectedVersion() != null && item.getVersion() != req.getExpectedVersion()) {
            throw new BizException(
                    ErrorCode.CONFLICT,
                    "版本冲突：当前 version=" + item.getVersion() + ", expectedVersion=" + req.getExpectedVersion()
            );
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

        // 历史表按追加写入，不回头改旧记录，这样审计链路会更干净。
        historyRepo.save(toHistory(saved, "UPSERT", req.getOperator(), req.getReason()));

        // 等事务真正提交成功再通知 watch 客户端，避免出现“被叫醒了但数据库还没落稳”的尴尬时刻。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                long lv = latestVersion(req.getApp(), req.getEnv());
                notifier.notifyChanged(req.getApp(), req.getEnv(), lv);
            }
        });

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
        // 用 key+version 组成一个稳定签名，只要有一项变了，ETag 就会跟着变。
        String sig = repo.findAllByAppAndEnvOrderByConfigKeyAsc(app, env).stream()
                .map(i -> i.getConfigKey() + ":" + i.getVersion())
                .reduce("", (a, b) -> a + ";" + b);
        return EtagUtil.weakEtag(sig);
    }

    @Transactional(readOnly = true)
    public ConfigItemDto getOne(String app, String env, String key) {
        ConfigItem item = repo.findByAppAndEnvAndConfigKey(app, env, key)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "配置不存在: " + key));
        return toDto(item);
    }

    @Transactional
    public ConfigItemDto rollback(com.example.configcenter.dto.request.RollbackConfigRequest req) {

        ConfigItem current = repo.findByAppAndEnvAndConfigKey(req.getApp(), req.getEnv(), req.getKey())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "配置不存在: " + req.getKey()));

        long targetVer = req.getTargetVersion();

        com.example.configcenter.domain.entity.ConfigItemHistory target = historyRepo
                .findByAppAndEnvAndConfigKeyAndVersion(req.getApp(), req.getEnv(), req.getKey(), targetVer)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "找不到目标历史版本: " + targetVer));

        // 回滚不是去改旧历史，而是把目标快照重新写成一个新的当前版本。
        current.setConfigValue(target.getConfigValue());
        current.setDescription(target.getDescription());
        current.setUpdatedAt(Instant.now());
        current.setVersion(current.getVersion() + 1);

        ConfigItem saved = repo.save(current);
        historyRepo.save(toHistory(saved, "ROLLBACK", req.getOperator(),
                "rollback-to=" + targetVer + (req.getReason() == null ? "" : (", " + req.getReason()))));
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public java.util.List<com.example.configcenter.dto.response.ConfigHistoryDto> history(String app, String env, String key) {
        return historyRepo.findAllByAppAndEnvAndConfigKeyOrderByVersionDesc(app, env, key)
                .stream()
                .map(h -> new com.example.configcenter.dto.response.ConfigHistoryDto(
                        h.getApp(), h.getEnv(), h.getConfigKey(), h.getConfigValue(), h.getDescription(),
                        h.getVersion(), h.getAction(), h.getOperator(), h.getReason(), h.getCreatedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public long latestVersion(String app, String env) {
        return repo.maxVersion(app, env);
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
        h.setCreatedAt(Instant.now());
        return h;
    }
}
