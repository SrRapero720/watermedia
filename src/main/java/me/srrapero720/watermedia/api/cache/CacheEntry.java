package me.srrapero720.watermedia.api.cache;

import java.io.File;

public final class CacheEntry {
    private final String url;
    private String tag;
    private long time;
    private long expireTime;

    public CacheEntry(String url, String tag, long time, long expireTime) {
        this.url = url;
        this.tag = tag;
        this.time = time;
        this.expireTime = expireTime;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public void setExpireTime(long expireTime) {
        this.expireTime = expireTime;
    }

    public String getUrl() {
        return url;
    }

    public String getTag() {
        return tag;
    }

    public long getTime() {
        return time;
    }

    public long getExpireTime() {
        return expireTime;
    }

    public File getFile() {
        return CacheAPI.entry$getFile(url);
    }
}