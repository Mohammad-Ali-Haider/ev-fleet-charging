package com.moveinsync.evfleet.api;

import com.moveinsync.evfleet.domain.DomainException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns exceptions thrown anywhere in a request into a safe JSON error with no internal details. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Builds the standard error body {"error":{"code":...}}. */
    private ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code)));
    }

    /** Our own business errors: return their code and HTTP status. */
    @ExceptionHandler(DomainException.class)
    ResponseEntity<?> domain(DomainException e) {
        return error(e.status(), e.getMessage());
    }

    /** Malformed JSON, a missing header or a bad value: 422. */
    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class, IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception e) {
        return error(422, "INVALID_REQUEST");
    }

    /** Two requests wrote the same row at once: 409. */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> conflict(Exception e) {
        return error(409, "CONCURRENT_WRITE_CONFLICT");
    }

    /** The database is unreachable or failed: 503. */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(Exception e) {
        return error(503, "DATABASE_UNAVAILABLE");
    }

    /** The caller lacks the required role: 403. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> forbidden(Exception e) {
        return error(403, "FORBIDDEN");
    }

    /** Anything unexpected: 500, without leaking the cause. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        return error(500, "INTERNAL_ERROR");
    }
}
