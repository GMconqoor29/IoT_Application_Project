#include "logger.h"

#include <Arduino.h>
#include <stdarg.h>

void logPrintf(const char *format, ...) {
  char buf[384];
  va_list args;
  va_start(args, format);
  vsnprintf(buf, sizeof(buf), format, args);
  va_end(args);

  Serial.print(buf);
  Serial0.print(buf);
}
