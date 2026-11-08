#pragma once

// In ra đồng thời cả cổng USB (Serial / HWCDC) và cổng UART (Serial0 / CH343).
void logPrintf(const char *format, ...) __attribute__((format(printf, 1, 2)));
