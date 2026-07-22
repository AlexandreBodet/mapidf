package com.mapidf.configurations.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.line")
public record LineProperties(
    String gtfsRouteId,
    String siriLineRef,
    String color
) {
}
