#pragma once

#include "Arduino.h"
#include <cstdint>
#include <map>
#include <string>

class Preferences {
public:
  bool begin(const char *name, bool readOnly = false) {
    currentNamespace_ = name ? name : "";
    readOnly_ = readOnly;
    return true;
  }

  void end() { currentNamespace_.clear(); }

  bool clear() {
    if (readOnly_) {
      return false;
    }
    store_[currentNamespace_].clear();
    return true;
  }

  size_t putString(const char *key, const String &value) {
    if (readOnly_ || !key) {
      return 0;
    }
    store_[currentNamespace_][key] = value.str();
    return value.length();
  }

  String getString(const char *key, const String &defaultValue = String("")) {
    auto nsIt = store_.find(currentNamespace_);
    if (nsIt == store_.end() || !key) {
      return defaultValue;
    }
    auto it = nsIt->second.find(key);
    if (it == nsIt->second.end()) {
      return defaultValue;
    }
    return String(it->second);
  }

  size_t putUShort(const char *key, uint16_t value) {
    if (readOnly_ || !key) {
      return 0;
    }
    store_[currentNamespace_][key] = std::to_string(value);
    return sizeof(uint16_t);
  }

  uint16_t getUShort(const char *key, uint16_t defaultValue = 0) {
    auto nsIt = store_.find(currentNamespace_);
    if (nsIt == store_.end() || !key) {
      return defaultValue;
    }
    auto it = nsIt->second.find(key);
    if (it == nsIt->second.end()) {
      return defaultValue;
    }
    return static_cast<uint16_t>(std::stoul(it->second));
  }

  size_t putUInt(const char *key, uint32_t value) {
    if (readOnly_ || !key) {
      return 0;
    }
    store_[currentNamespace_][key] = std::to_string(value);
    return sizeof(uint32_t);
  }

  uint32_t getUInt(const char *key, uint32_t defaultValue = 0) {
    auto nsIt = store_.find(currentNamespace_);
    if (nsIt == store_.end() || !key) {
      return defaultValue;
    }
    auto it = nsIt->second.find(key);
    if (it == nsIt->second.end()) {
      return defaultValue;
    }
    return static_cast<uint32_t>(std::stoul(it->second));
  }

  static void resetAll() { store_.clear(); }

private:
  std::string currentNamespace_;
  bool readOnly_ = false;
  static std::map<std::string, std::map<std::string, std::string>> store_;
};
