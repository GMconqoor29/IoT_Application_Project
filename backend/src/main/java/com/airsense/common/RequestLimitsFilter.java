package com.airsense.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestLimitsFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Cache-Control", "no-store");
    if (!request.getMethod().equals("POST") && !request.getMethod().equals("PUT")) {
      chain.doFilter(request, response);
      return;
    }
    byte[] bytes = request.getInputStream().readNBytes(8193);
    if (bytes.length > 8192) {
      response.setStatus(400);
      response.setContentType("application/json");
      JsonSupport.MAPPER.writeValue(
          response.getOutputStream(),
          new ApiExceptionHandler.ErrorResponse(
              "INVALID_REQUEST", "Request body exceeds 8192 bytes.", List.of()));
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
              public int read() {
                return input.read();
              }

              public boolean isFinished() {
                return input.available() == 0;
              }

              public boolean isReady() {
                return true;
              }

              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
              }
            };
          }

          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        response);
  }
}
