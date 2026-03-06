package com.example.configcenter.dto.response;

/**
 * watch 接口返回值。
 * changed=false 表示这轮只是超时，没有新变化，不是接口坏了。
 */
public class ConfigWatchDto {
    private boolean changed;
    private long latestVersion;

    public ConfigWatchDto() {}

    public ConfigWatchDto(boolean changed, long latestVersion) {
        this.changed = changed;
        this.latestVersion = latestVersion;
    }

    public boolean isChanged() {
        return changed;
    }

    public void setChanged(boolean changed) {
        this.changed = changed;
    }

    public long getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(long latestVersion) {
        this.latestVersion = latestVersion;
    }
}
