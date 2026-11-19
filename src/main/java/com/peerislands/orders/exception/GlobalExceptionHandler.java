package com.peerislands.orders.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Single place that turns exceptions into RFC 7807 {@link ProblemDetail} responses.
 * Spring MVC exceptions (malformed body, validation, 404 for unknown paths, ...) are handled by
 * {@link ResponseEntityExceptionHandler}; a few are overridden to give clients actionable detail.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Order not found");
        return problem;
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ProblemDetail handleIdempotencyKeyReuse(IdempotencyKeyReuseException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Idempotency key reused");
        return problem;
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ProblemDetail handleInvalidTransition(InvalidStatusTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid status transition");
        problem.setProperty("currentStatus", ex.getCurrentStatus());
        problem.setProperty("targetStatus", ex.getTargetStatus());
        return problem;
    }

    @ExceptionHandler(StatusNotPatchableException.class)
    public ProblemDetail handleStatusNotPatchable(StatusNotPatchableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Status not allowed");
        return problem;
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ProblemDetail handleInvalidCursor(InvalidCursorException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid cursor");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setTitle("Internal server error");
        return problem;
    }

    /** {@code @Valid @RequestBody} failures: 400 with a per-field {@code errors} list. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> errors.add(error(e.getField(), e.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors().forEach(e -> errors.add(error(e.getObjectName(), e.getDefaultMessage())));
        return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
    }

    /** Constraint violations on handler parameters (e.g. the Idempotency-Key header): 400. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            String name = parameterName(result.getMethodParameter());
            if (result instanceof ParameterErrors parameterErrors) {
                for (FieldError e : parameterErrors.getFieldErrors()) {
                    errors.add(error(e.getField(), e.getDefaultMessage()));
                }
            } else {
                result.getResolvableErrors().forEach(e -> errors.add(error(name, e.getDefaultMessage())));
            }
        }
        return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
    }

    /** Malformed JSON, unknown fields, wrong types (e.g. quantity 1.5): 400 naming the offending field. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, readableDetail(ex));
        problem.setTitle("Malformed request body");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Query/path parameter of the wrong type (e.g. {@code size=abc}, {@code status=BOGUS}): 400. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        StringBuilder detail = new StringBuilder("Invalid value '")
                .append(ex.getValue()).append("' for parameter '").append(name).append("'");
        Class<?> requiredType = ex.getRequiredType();
        if (requiredType != null && requiredType.isEnum()) {
            detail.append("; allowed values: ").append(Arrays.toString(requiredType.getEnumConstants()));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail.toString());
        problem.setTitle("Invalid parameter");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static Map<String, String> error(String field, String message) {
        return Map.of("field", field, "message", message == null ? "invalid" : message);
    }

    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        String name = parameter.getParameterName();
        return name != null ? name : "arg" + parameter.getParameterIndex();
    }

    private static String readableDetail(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return "Unknown field '" + jsonPath(unknown) + "'";
        }
        if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            return "Invalid value for field '" + jsonPath(mapping) + "'";
        }
        if (cause instanceof JsonProcessingException) {
            return "Malformed JSON";
        }
        return "Request body is missing or unreadable";
    }

    private static String jsonPath(JsonMappingException ex) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference ref : ex.getPath()) {
            if (ref.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
