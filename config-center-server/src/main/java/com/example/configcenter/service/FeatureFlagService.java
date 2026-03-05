package com.example.configcenter.service;

import com.example.configcenter.domain.entity.FeatureFlag;
import com.example.configcenter.dto.request.UpsertFeatureRequest;
import com.example.configcenter.dto.response.FeatureEvalResult;
import com.example.configcenter.dto.response.FeatureFlagDto;
import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import com.example.configcenter.repository.FeatureFlagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final FeatureFlagRepository repo;
    private final com.example.configcenter.repository.FeatureFlagHistoryRepository historyRepo;
    private final FeatureEvaluator evaluator = new FeatureEvaluator();

    public FeatureFlagService(FeatureFlagRepository repo,
                              com.example.configcenter.repository.FeatureFlagHistoryRepository historyRepo) {
        this.repo = repo;
        this.historyRepo = historyRepo;
    }

    @Transactional
    public FeatureFlagDto upsert(UpsertFeatureRequest req) {
        FeatureFlag ff = repo.findByAppAndEnvAndName(req.getApp(), req.getEnv(), req.getName())
                .orElseGet(FeatureFlag::new);

        boolean isCreate = (ff.getId() == null);

        if (!isCreate && req.getExpectedVersion() != null) {
            if (ff.getVersion() != req.getExpectedVersion()) {
                throw new BizException(ErrorCode.CONFLICT,
                        "版本冲突：当前 version=" + ff.getVersion() + ", expectedVersion=" + req.getExpectedVersion());
            }
        }
        if (isCreate) {
            ff.setVersion(1);
        } else {
            ff.setVersion(ff.getVersion() + 1);
        }

        ff.setApp(req.getApp());
        ff.setEnv(req.getEnv());
        ff.setName(req.getName());
        ff.setEnabled(Boolean.TRUE.equals(req.getEnabled()));
        ff.setRolloutPercentage(req.getRolloutPercentage());
        ff.setAllowlist(req.getAllowlist());
        ff.setUpdatedAt(Instant.now());

        if (isCreate) {
            log.info("Feature upsert(create): app={}, env={}, name={}", req.getApp(), req.getEnv(), req.getName());
        } else {
            log.info("Feature upsert(update): app={}, env={}, name={}", req.getApp(), req.getEnv(), req.getName());
        }

        FeatureFlag saved = repo.save(ff);
        historyRepo.save(toHistory(saved, "UPSERT", req.getOperator(), req.getReason()));
        return toDto(saved);
    }
    @Transactional
    public FeatureFlagDto rollback(com.example.configcenter.dto.request.RollbackFeatureRequest req) {

        FeatureFlag current = repo.findByAppAndEnvAndName(req.getApp(), req.getEnv(), req.getName())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Feature 不存在：" + req.getName()));

        long targetVer = req.getTargetVersion();

        com.example.configcenter.domain.entity.FeatureFlagHistory target = historyRepo
                .findByAppAndEnvAndNameAndVersion(req.getApp(), req.getEnv(), req.getName(), targetVer)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "找不到目标历史版本：" + targetVer));

        current.setEnabled(target.isEnabled());
        current.setRolloutPercentage(target.getRolloutPercentage());
        current.setAllowlist(target.getAllowlist());
        current.setUpdatedAt(java.time.Instant.now());

        current.setVersion(current.getVersion() + 1);

        FeatureFlag saved = repo.save(current);

        historyRepo.save(toHistory(saved, "ROLLBACK", req.getOperator(),
                "rollback-to=" + targetVer + (req.getReason() == null ? "" : (", " + req.getReason()))));

        return toDto(saved);
    }
    @Transactional(readOnly = true)
    public List<FeatureFlagDto> list(String app, String env) {
        return repo.findAllByAppAndEnvOrderByNameAsc(app, env).stream()
                .map(this::toDto)
                .toList();
    }
    @Transactional(readOnly = true)
    public java.util.List<com.example.configcenter.dto.response.FeatureHistoryDto> history(String app, String env, String name) {
        return historyRepo.findAllByAppAndEnvAndNameOrderByVersionDesc(app, env, name)
                .stream()
                .map(h -> new com.example.configcenter.dto.response.FeatureHistoryDto(
                        h.getApp(), h.getEnv(), h.getName(),
                        h.isEnabled(), h.getRolloutPercentage(), h.getAllowlist(),
                        h.getVersion(), h.getAction(), h.getOperator(), h.getReason(), h.getCreatedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public FeatureEvalResult evaluate(String app, String env, String name, String userId) {
        FeatureFlag ff = repo.findByAppAndEnvAndName(app, env, name)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Feature 不存在：" + name));

        if (!ff.isEnabled()) {
            return new FeatureEvalResult(name, userId, false, -1, "enabled=false（强制关闭）");
        }
        if (ff.getAllowlist() != null && ff.getAllowlist().contains(userId)) {
            return new FeatureEvalResult(name, userId, true, -1, "allowlist 命中");
        }

        int bucket = evaluator.stableBucket(userId, name);
        boolean on = bucket < ff.getRolloutPercentage();
        String decision = "灰度：bucket=" + bucket + ", rollout=" + ff.getRolloutPercentage() + "%";
        return new FeatureEvalResult(name, userId, on, bucket, decision);
    }

    private FeatureFlagDto toDto(FeatureFlag ff) {
        return new FeatureFlagDto(
                ff.getApp(), ff.getEnv(), ff.getName(),
                ff.isEnabled(), ff.getRolloutPercentage(),
                ff.getAllowlist(), ff.getUpdatedAt(),
                ff.getVersion()
        );
    }
    private com.example.configcenter.domain.entity.FeatureFlagHistory toHistory(
            FeatureFlag ff, String action, String operator, String reason) {

        com.example.configcenter.domain.entity.FeatureFlagHistory h =
                new com.example.configcenter.domain.entity.FeatureFlagHistory();

        h.setApp(ff.getApp());
        h.setEnv(ff.getEnv());
        h.setName(ff.getName());
        h.setEnabled(ff.isEnabled());
        h.setRolloutPercentage(ff.getRolloutPercentage());
        h.setAllowlist(ff.getAllowlist());
        h.setVersion(ff.getVersion());
        h.setAction(action);
        h.setOperator(operator);
        h.setReason(reason);
        h.setCreatedAt(java.time.Instant.now());
        return h;
    }
}