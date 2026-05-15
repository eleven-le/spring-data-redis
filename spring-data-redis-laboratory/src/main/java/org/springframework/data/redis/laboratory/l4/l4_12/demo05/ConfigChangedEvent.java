package org.springframework.data.redis.laboratory.l4.l4_12.demo05;

public class ConfigChangedEvent {
    private String key;
    private long version;
    private long eventTime;
    private String traceId;

    public ConfigChangedEvent() {}
    public ConfigChangedEvent(String key, long version, long eventTime, String traceId) {
        this.key = key; this.version = version; this.eventTime = eventTime; this.traceId = traceId;
    }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public long getEventTime() { return eventTime; }
    public void setEventTime(long eventTime) { this.eventTime = eventTime; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
