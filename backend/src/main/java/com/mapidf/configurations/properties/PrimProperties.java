package com.mapidf.configurations.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.prim")
public record PrimProperties(
    String apiKey,
    String authHeader,
    String gtfsStaticUrl,
    String realtimeBaseUrl,
    Duration pollInterval
) {
}
