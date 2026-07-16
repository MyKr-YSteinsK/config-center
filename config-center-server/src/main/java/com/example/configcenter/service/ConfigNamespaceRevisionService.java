package com.example.configcenter.service;

import com.example.configcenter.domain.entity.ConfigNamespaceRevision;
import com.example.configcenter.repository.ConfigNamespaceRevisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfigNamespaceRevisionService {

    private final ConfigNamespaceRevisionRepository repository;
    private final NamespaceRevisionLock namespaceLock;

    public ConfigNamespaceRevisionService(
            ConfigNamespaceRevisionRepository repository,
            NamespaceRevisionLock namespaceLock) {
        this.repository = repository;
        this.namespaceLock = namespaceLock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long advance(String app, String env) {
        namespaceLock.lockUntilTransactionCompletion(app, env);
        ConfigNamespaceRevision namespace = repository.findForUpdate(app, env)
                .orElseGet(() -> newNamespace(app, env));
        namespace.setRevision(namespace.getRevision() + 1);
        return repository.save(namespace).getRevision();
    }

    @Transactional(readOnly = true)
    public long current(String app, String env) {
        return repository.findByAppAndEnv(app, env)
                .map(ConfigNamespaceRevision::getRevision)
                .orElse(0L);
    }

    private ConfigNamespaceRevision newNamespace(String app, String env) {
        ConfigNamespaceRevision namespace = new ConfigNamespaceRevision();
        namespace.setApp(app);
        namespace.setEnv(env);
        return namespace;
    }
}
