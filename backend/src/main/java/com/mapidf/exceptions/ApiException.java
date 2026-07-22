package com.mapidf.exceptions;

import com.mapidf.data.enums.ErrorCode;
import lombok.Getter;
import lombok.NonNull;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final ErrorCode errorCode;

    public ApiException(@NonNull HttpStatus httpStatus, @NonNull ErrorCode errorCode) {
        super(errorCode.getDescription());
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public ApiException(@NonNull HttpStatus httpStatus, @NonNull ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDescription(), cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }
}
