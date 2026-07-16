package com.example.configcenter.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 固定条带数的进程内锁，用来串行化命名空间修订行的首次创建。
 * 锁持有到当前事务完成，避免另一个事务在首行尚未提交时再次尝试插入。
 */
@Component
public class NamespaceRevisionLock {

    private static final int STRIPE_COUNT = 64;

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

    public NamespaceRevisionLock() {
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new ReentrantLock();
        }
    }

    public void lockUntilTransactionCompletion(String app, String env) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Namespace revision lock requires an active transaction");
        }

        ReentrantLock lock = stripes[Math.floorMod(Objects.hash(app, env), stripes.length)];
        lock.lock();
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    lock.unlock();
                }
            });
        } catch (RuntimeException | Error e) {
            lock.unlock();
            throw e;
        }
    }
}
