#pragma once

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#define PROGMEM

using std::isnan;

class String {
public:
  String() = default;
  String(const char *s) : data_(s ? s : "") {}
  String(const std::string &s) : data_(s) {}
  String(int v) : data_(std::to_string(v)) {}
  String(unsigned int v) : data_(std::to_string(v)) {}
  String(long v) : data_(std::to_string(v)) {}
  String(unsigned long v) : data_(std::to_string(v)) {}
  String(long long v) : data_(std::to_string(v)) {}
  String(unsigned long long v) : data_(std::to_string(v)) {}

  const char *c_str() const { return data_.c_str(); }
  size_t length() const { return data_.length(); }
  const std::string &str() const { return data_; }

  void trim() {
    size_t start = 0;
    while (start < data_.size() && std::isspace(static_cast<unsigned char>(data_[start]))) {
      start++;
    }
    size_t end = data_.size();
    while (end > start && std::isspace(static_cast<unsigned char>(data_[end - 1]))) {
      end--;
    }
    data_ = data_.substr(start, end - start);
  }

  int indexOf(char ch) const {
    auto pos = data_.find(ch);
    return pos == std::string::npos ? -1 : static_cast<int>(pos);
  }

  String substring(int from) const {
    if (from < 0) {
      from = 0;
    }
    if (static_cast<size_t>(from) >= data_.size()) {
      return String("");
    }
    return String(data_.substr(static_cast<size_t>(from)));
  }

  String substring(int from, int to) const {
    if (from < 0) {
      from = 0;
    }
    if (to < from || static_cast<size_t>(from) >= data_.size()) {
      return String("");
    }
    size_t len = static_cast<size_t>(to - from);
    return String(data_.substr(static_cast<size_t>(from), len));
  }

  bool startsWith(const char *prefix) const {
    std::string p(prefix ? prefix : "");
    return data_.rfind(p, 0) == 0;
  }

  bool equals(const char *other) const {
    return data_ == (other ? other : "");
  }

  bool equalsIgnoreCase(const char *other) const {
    std::string o(other ? other : "");
    if (data_.size() != o.size()) {
      return false;
    }
    for (size_t i = 0; i < data_.size(); i++) {
      if (std::tolower(static_cast<unsigned char>(data_[i])) !=
          std::tolower(static_cast<unsigned char>(o[i]))) {
        return false;
      }
    }
    return true;
  }

  long toInt() const {
    char *end = nullptr;
    long val = std::strtol(data_.c_str(), &end, 10);
    return val;
  }

  String &operator+=(const String &rhs) {
    data_ += rhs.data_;
    return *this;
  }

  String &operator+=(const char *rhs) {
    if (rhs) {
      data_ += rhs;
    }
    return *this;
  }

  friend String operator+(const String &lhs, const String &rhs) {
    return String(lhs.data_ + rhs.data_);
  }

  friend String operator+(const String &lhs, const char *rhs) {
    return String(lhs.data_ + (rhs ? rhs : ""));
  }

  friend String operator+(const char *lhs, const String &rhs) {
    return String((lhs ? lhs : "") + rhs.data_);
  }

  bool operator==(const String &rhs) const { return data_ == rhs.data_; }
  bool operator!=(const String &rhs) const { return data_ != rhs.data_; }
  bool operator==(const char *rhs) const { return data_ == (rhs ? rhs : ""); }
  bool operator!=(const char *rhs) const { return data_ != (rhs ? rhs : ""); }

private:
  std::string data_;
};

class Stream {
public:
  virtual ~Stream() = default;
  virtual int available() { return !rxLines_.empty() ? 1 : 0; }
  virtual String readStringUntil(char /*terminator*/) {
    if (rxLines_.empty()) {
      return String("");
    }
    String line = rxLines_.front();
    rxLines_.erase(rxLines_.begin());
    return line;
  }
  virtual void begin(unsigned long /*baud*/) {}
  virtual void print(const char *s) {
    if (s) {
      txOutput_ += s;
    }
  }

  void injectLine(const std::string &line) { rxLines_.emplace_back(line); }
  void clearOutput() { txOutput_.clear(); }
  const std::string &output() const { return txOutput_; }

private:
  std::vector<String> rxLines_;
  std::string txOutput_;
};

extern Stream Serial;
extern Stream Serial0;

namespace MockArduinoState {
extern unsigned long currentMillis;
extern time_t currentEpoch;
extern bool ntpSyncShouldSucceed;
}  // namespace MockArduinoState

inline unsigned long millis() { return MockArduinoState::currentMillis; }
inline void delay(unsigned long ms) { MockArduinoState::currentMillis += ms; }

inline void configTime(long /*gmtOffset_sec*/, int /*daylightOffset_sec*/,
                       const char * /*server1*/, const char * /*server2*/) {}

inline bool getLocalTime(struct tm *info, uint32_t /*ms*/ = 5000) {
  if (!MockArduinoState::ntpSyncShouldSucceed) {
    MockArduinoState::currentMillis += 1000;
    return false;
  }
  time_t t = MockArduinoState::currentEpoch;
  gmtime_r(&t, info);
  return true;
}
