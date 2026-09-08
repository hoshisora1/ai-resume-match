package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.application.analysis.IdempotencyConflictException;
import com.zhulikang.aimatch.application.analysis.RateLimitExceededException;
import com.zhulikang.aimatch.application.analysis.RateLimitUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiProblemDetail> handleValidation(
        MethodArgumentNotValidException ex,
        HttpServletRequest request
    ) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", request);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestPartException.class,
        MissingServletRequestParameterException.class,
        ConstraintViolationException.class,
        MaxUploadSizeExceededException.class
    })
    public ResponseEntity<ApiProblemDetail> handleFrameworkBadRequest(
        Exception ex,
        HttpServletRequest request
    ) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiProblemDetail> handleBadRequest(
        IllegalArgumentException ex,
        HttpServletRequest request
    ) {
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiProblemDetail> handleNotFound(
        ResourceNotFoundException ex,
        HttpServletRequest request
    ) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiProblemDetail> handleIdempotencyConflict(
        IdempotencyConflictException ex,
        HttpServletRequest request
    ) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiProblemDetail> handleRateLimitExceeded(
        RateLimitExceededException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", Long.toString(ex.getRetryAfterSeconds()))
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiProblemDetail.of(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMIT_EXCEEDED",
                ex.getMessage(),
                request.getRequestURI()
            ));
    }

    @ExceptionHandler(RateLimitUnavailableException.class)
    public ResponseEntity<ApiProblemDetail> handleRateLimitUnavailable(
        RateLimitUnavailableException ex,
        HttpServletRequest request
    ) {
        return problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "RATE_LIMIT_UNAVAILABLE",
            ex.getMessage(),
            request
        );
    }

    private ResponseEntity<ApiProblemDetail> problem(
        HttpStatus status,
        String code,
        String detail,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiProblemDetail.of(status, code, detail, request.getRequestURI()));
    }
}
