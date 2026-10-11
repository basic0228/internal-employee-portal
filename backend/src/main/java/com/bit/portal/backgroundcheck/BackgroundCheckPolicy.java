package com.bit.portal.backgroundcheck;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Initial engineering limits, not empirically optimal settings. See docs/BACKGROUND_CHECK_POLICY.md. */
@Component
@Profile("portal-dev")
@ConfigurationProperties(prefix = "background-check.policy")
public class BackgroundCheckPolicy {
    private int maxRetries=0, retryBackoffMs=1000, pollingIntervalMs=5000,
            pollingMaxDurationMs=120000, pollingMaxAttempts=24;
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int value) { maxRetries=value; }
    public int getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(int value) { retryBackoffMs=value; }
    public int getPollingIntervalMs() { return pollingIntervalMs; }
    public void setPollingIntervalMs(int value) { pollingIntervalMs=value; }
    public int getPollingMaxDurationMs() { return pollingMaxDurationMs; }
    public void setPollingMaxDurationMs(int value) { pollingMaxDurationMs=value; }
    public int getPollingMaxAttempts() { return pollingMaxAttempts; }
    public void setPollingMaxAttempts(int value) { pollingMaxAttempts=value; }
    public void validate() {
        if(maxRetries<0 || maxRetries>1 || retryBackoffMs<100 || retryBackoffMs>5000
            || pollingIntervalMs<100 || pollingIntervalMs>60000
            || pollingMaxDurationMs<100 || pollingMaxDurationMs>300000
            || pollingMaxAttempts<1 || pollingMaxAttempts>24) throw new IllegalStateException("Invalid background check policy");
    }
}
