package com.mapidf.data.enums;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    STATION_NOT_FOUND("Station not found"),
    NOT_FOUND("Resource not found"),
    BAD_REQUEST("Invalid request"),
    TOO_MANY_REQUESTS("Too many requests"),
    INTERNAL_ERROR("Internal server error");

    @NonNull
    private final String description;
}
