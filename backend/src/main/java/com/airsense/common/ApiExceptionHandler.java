package com.airsense.common;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  public record ErrorResponse(String code, String message, List<String> details) {}

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> known(ApiException e) {
    return ResponseEntity.status(e.status)
        .body(new ErrorResponse(e.code, e.getMessage(), List.of()));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<?> bad(Exception e) {
    return known(new ApiException(400, "INVALID_REQUEST", "Invalid request."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unavailable(Exception e) {
    return known(new ApiException(503, "SERVICE_UNAVAILABLE", "Service temporarily unavailable."));
  }
}
