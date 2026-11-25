package com.example.configcenter.service;

import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;

final class WriteRules {
    private WriteRules() {}

    static void checkExpectedVersion(long currentVersion, Long expectedVersion) {
        if (expectedVersion != null && currentVersion != expectedVersion) {
            throw new BizException(ErrorCode.CONFLICT,
                    "版本冲突：当前 version=" + currentVersion + ", expectedVersion=" + expectedVersion);
        }
    }

    static String rollbackReason(long targetVersion, String reason) {
        return "rollback-to=" + targetVersion + (reason == null ? "" : ", " + reason);
    }
}
