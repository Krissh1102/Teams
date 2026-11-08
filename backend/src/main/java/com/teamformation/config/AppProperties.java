package com.teamformation.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String jwtSecret, long jwtTtlMinutes, List<String> corsOrigins,
                            int inviteTtlHours, RateLimit rateLimit) {
    public record RateLimit(boolean enabled, int authPerMinutePerIp,
                            int joinPerMinutePerUser, int joinPerMinutePerIp) {}
}
