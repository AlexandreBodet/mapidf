package com.mapidf.exceptions;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mapidf.data.dto.ErrorResponse;
import com.mapidf.data.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private static ErrorResponse write(HttpServletResponse response, HttpStatus status,
                                        ErrorCode errorCode, HttpServletRequest request) {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON.toString());
        return ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(status.value())
            .errorCode(errorCode)
            .path(request.getRequestURI())
            .build();
    }

    @ExceptionHandler(ApiException.class)
    public ErrorResponse handleApiException(HttpServletRequest request, HttpServletResponse response, ApiException ex) {
        if (ex.getHttpStatus().is5xxServerError()) {
            log.error("Server error [{}]: {}", ex.getErrorCode(), ex.getMessage(), ex);
        } else {
            log.debug("Client error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        }
        return write(response, ex.getHttpStatus(), ex.getErrorCode(), request);
    }

    @ExceptionHandler(Exception.class)
    public ErrorResponse handleUnexpected(HttpServletRequest request, HttpServletResponse response, Exception ex) {
        // Les exceptions du framework portent leur propre statut via l'interface ErrorResponse de
        // Spring (homonyme de notre DTO, d'où le nom complet). Sans ce test, l'attrape-tout les
        // classait toutes en 500 : un chemin non mappé répondait « erreur interne » et laissait
        // une trace de pile dans le journal pour une faute du client (QUA-14).
        if (ex instanceof org.springframework.web.ErrorResponse framework
            && framework.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
            ErrorCode errorCode = status == HttpStatus.NOT_FOUND ? ErrorCode.NOT_FOUND : ErrorCode.BAD_REQUEST;
            log.debug("Client error [{}]: {}", errorCode, ex.getMessage());
            return write(response, status, errorCode, request);
        }
        log.error("Unexpected error", ex);
        return write(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, request);
    }
}
