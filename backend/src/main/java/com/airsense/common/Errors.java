package com.airsense.common;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> known(ApiException e) {
    return ResponseEntity.status(e.status)
        .body(Json.obj("code", e.code, "message", e.getMessage(), "details", List.of()));
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
