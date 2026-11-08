package com.teamformation.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Every error leaves the API in one shape: {status, code, message, fields?}. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorBody(int status, String code, String message, Map<String, String> fields) {}

    private static ResponseEntity<ErrorBody> body(HttpStatus s, String code, String msg, Map<String, String> fields) {
        return ResponseEntity.status(s).body(new ErrorBody(s.value(), code, msg, fields));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
                       MissingRequestHeaderException.class})
    ResponseEntity<ErrorBody> unreadable(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request", null);
    }

    /**
     * Raised when a database constraint (the last line of defence) rejects a write that
     * slipped past the application checks. The transaction has already rolled back.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> integrity(DataIntegrityViolationException e) {
        String c = constraint(e);
        return switch (c) {
            case "uq_user_hackathon" -> body(HttpStatus.CONFLICT, "ALREADY_IN_TEAM", "You are already in a team for this hackathon", null);
            case "chk_member_count"  -> body(HttpStatus.CONFLICT, "TEAM_FULL", "Team is full", null);
            case "uq_pending_request"-> body(HttpStatus.CONFLICT, "REQUEST_PENDING", "You already have a pending request for this team", null);
            case "users_email_key"   -> body(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Email is already registered", null);
            default -> {
                log.warn("Unmapped integrity violation: {}", c, e);
                yield body(HttpStatus.CONFLICT, "CONFLICT", "The change conflicts with the current state; please retry", null);
            }
        };
    }

    /** Unknown route, wrong method, bad media type, missing query param: a client error, never a 500. */
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class,
                       HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class,
                       MissingServletRequestParameterException.class})
    ResponseEntity<ErrorBody> framework(Exception e) {
        HttpStatus s = e instanceof ErrorResponse er ? HttpStatus.valueOf(er.getStatusCode().value()) : HttpStatus.BAD_REQUEST;
        String code = s == HttpStatus.NOT_FOUND ? "NOT_FOUND" : s == HttpStatus.METHOD_NOT_ALLOWED ? "METHOD_NOT_ALLOWED" : "BAD_REQUEST";
        return body(s, code, s == HttpStatus.NOT_FOUND ? "Not found" : "Invalid request", null);
    }

    @ExceptionHandler(CannotAcquireLockException.class)
    ResponseEntity<ErrorBody> lock(CannotAcquireLockException e) {   // e.g. deadlock victim: safe to retry
        return body(HttpStatus.CONFLICT, "RETRY", "Concurrent update, please retry", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> other(Exception e) {
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error", null);
    }

    private static String constraint(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException p && p.getServerErrorMessage() != null
                    && p.getServerErrorMessage().getConstraint() != null) {
                return p.getServerErrorMessage().getConstraint();
            }
        }
        return "";
    }
}
