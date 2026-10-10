package com.ntaganira.heritier.iWarehouse.controller.api;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller.api
 * - File      : MobileApiAdvice.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Errors of the mobile POS API as JSON, in the phone's language: a business refusal is 422 with its message
 *               key and text, a missing record 404, a missing right 403, an unreadable body 400, a record changed at the
 *               same time 409. Anything else is logged and answered 500 with a plain text, never the exception's own.
 *               First of the advices, so the web pages' (a redirect on a stale record) never answers the phone.
 * </pre>
 */
@RestControllerAdvice(basePackageClasses = MobileApiAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MobileApiAdvice {

    private static final Logger log = LoggerFactory.getLogger(MobileApiAdvice.class);

    private final Messages messages;

    public MobileApiAdvice(Messages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<MobileApi.ErrorView> refused(BusinessException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new MobileApi.ErrorView(e.getMessageKey(), messages.get(e.getMessageKey(), e.getArgs())));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<MobileApi.ErrorView> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MobileApi.ErrorView("notFound", e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<MobileApi.ErrorView> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new MobileApi.ErrorView("forbidden", messages.get("mobile.forbidden")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<MobileApi.ErrorView> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new MobileApi.ErrorView("badRequest", messages.get("mobile.badRequest")));
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<MobileApi.ErrorView> stale(Exception e) {
        log.info("Mobile API request on a record changed at the same time: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new MobileApi.ErrorView("stale", messages.get("mobile.error.stale")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<MobileApi.ErrorView> failed(Exception e) {
        // Spring's own refusals (wrong method, a malformed id...) keep their status
        if (e instanceof ErrorResponse r && r.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(r.getStatusCode()).body(new MobileApi.ErrorView("badRequest", messages.get("mobile.badRequest")));
        }
        log.error("Mobile API request failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new MobileApi.ErrorView("server", messages.get("mobile.error.server")));
    }
}
