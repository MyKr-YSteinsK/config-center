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
    private final FeatureEvaluator evaluator = new FeatureEvaluator();

    public FeatureFlagService(FeatureFlagRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public FeatureFlagDto upsert(UpsertFeatureRequest req) {
        FeatureFlag ff = repo.findByAppAndEnvAndName(req.getApp(), req.getEnv(), req.getName())
                .orElseGet(FeatureFlag::new);

        boolean isCreate = (ff.getId() == null);

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
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<FeatureFlagDto> list(String app, String env) {
        return repo.findAllByAppAndEnvOrderByNameAsc(app, env).stream()
                .map(this::toDto)
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

    private FeatureFlagDto toDto(FeatureFlag e) {
        return new FeatureFlagDto(
                e.getApp(), e.getEnv(), e.getName(),
                e.isEnabled(), e.getRolloutPercentage(),
                e.getAllowlist(), e.getUpdatedAt()
        );
    }
}