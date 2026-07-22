package com.mapidf.data.enums;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    LINE_NOT_FOUND("Line not found"),
    BAD_REQUEST("Invalid request"),
    INTERNAL_ERROR("Internal server error");

    @NonNull
    private final String description;
}
