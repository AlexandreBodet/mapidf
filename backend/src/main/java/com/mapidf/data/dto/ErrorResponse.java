package com.mapidf.data.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mapidf.data.enums.ErrorCode;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    Instant timestamp;
    int status;
    ErrorCode errorCode;
    String path;
}
