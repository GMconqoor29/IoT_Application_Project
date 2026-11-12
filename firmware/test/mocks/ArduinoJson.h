#pragma once

#include "Arduino.h"
#include <cstdint>
#include <cstdio>
#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

class JsonObject;

struct JsonNode {
  enum class Type { Null, Bool, Int, UInt, Float, StringVal, Object };
  Type type = Type::Null;
  bool boolVal = false;
  int64_t intVal = 0;
  uint64_t uintVal = 0;
  double floatVal = 0.0;
  std::string strVal;
  std::vector<std::pair<std::string, std::shared_ptr<JsonNode>>> children;

  std::shared_ptr<JsonNode> getOrCreate(const std::string &key) {
    for (auto &kv : children) {
      if (kv.first == key) {
        return kv.second;
      }
    }
    auto node = std::make_shared<JsonNode>();
    children.emplace_back(key, node);
    return node;
  }

  void serialize(std::ostringstream &os) const {
    switch (type) {
      case Type::Null:
        os << "null";
        break;
      case Type::Bool:
        os << (boolVal ? "true" : "false");
        break;
      case Type::Int:
        os << intVal;
        break;
      case Type::UInt:
        os << uintVal;
        break;
      case Type::Float: {
        char buf[64];
        std::snprintf(buf, sizeof(buf), "%.2f", floatVal);
        // Trim trailing zeros after decimal point if needed, or keep standard number
        os << std::strtod(buf, nullptr);
        break;
      }
      case Type::StringVal:
        os << '"';
        for (char c : strVal) {
          if (c == '"' || c == '\\') {
            os << '\\';
          }
          os << c;
        }
        os << '"';
        break;
      case Type::Object:
        os << '{';
        for (size_t i = 0; i < children.size(); i++) {
          if (i > 0) {
            os << ',';
          }
          os << '"' << children[i].first << "\":";
          children[i].second->serialize(os);
        }
        os << '}';
        break;
    }
  }
};

class JsonObject {
public:
  explicit JsonObject(std::shared_ptr<JsonNode> node) : node_(std::move(node)) {
    if (node_) {
      node_->type = JsonNode::Type::Object;
    }
  }

  class Proxy {
  public:
    explicit Proxy(std::shared_ptr<JsonNode> n) : n_(std::move(n)) {}

    Proxy &operator=(std::nullptr_t) {
      n_->type = JsonNode::Type::Null;
      return *this;
    }
    Proxy &operator=(bool v) {
      n_->type = JsonNode::Type::Bool;
      n_->boolVal = v;
      return *this;
    }
    Proxy &operator=(int v) {
      n_->type = JsonNode::Type::Int;
      n_->intVal = v;
      return *this;
    }
    Proxy &operator=(uint32_t v) {
      n_->type = JsonNode::Type::UInt;
      n_->uintVal = v;
      return *this;
    }
    Proxy &operator=(uint64_t v) {
      n_->type = JsonNode::Type::UInt;
      n_->uintVal = v;
      return *this;
    }
    Proxy &operator=(float v) {
      n_->type = JsonNode::Type::Float;
      n_->floatVal = v;
      return *this;
    }
    Proxy &operator=(double v) {
      n_->type = JsonNode::Type::Float;
      n_->floatVal = v;
      return *this;
    }
    Proxy &operator=(const char *v) {
      if (!v) {
        n_->type = JsonNode::Type::Null;
      } else {
        n_->type = JsonNode::Type::StringVal;
        n_->strVal = v;
      }
      return *this;
    }
    Proxy &operator=(const String &v) {
      n_->type = JsonNode::Type::StringVal;
      n_->strVal = v.str();
      return *this;
    }

    template <typename T>
    T to() {
      return T(n_);
    }

  private:
    std::shared_ptr<JsonNode> n_;
  };

  Proxy operator[](const char *key) {
    return Proxy(node_->getOrCreate(key ? key : ""));
  }

private:
  std::shared_ptr<JsonNode> node_;
};

class JsonDocument {
public:
  JsonDocument() : root_(std::make_shared<JsonNode>()) {
    root_->type = JsonNode::Type::Object;
  }

  JsonObject::Proxy operator[](const char *key) {
    return JsonObject::Proxy(root_->getOrCreate(key ? key : ""));
  }

  const std::shared_ptr<JsonNode> &root() const { return root_; }

private:
  std::shared_ptr<JsonNode> root_;
};

inline void serializeJson(const JsonDocument &doc, String &output) {
  std::ostringstream os;
  doc.root()->serialize(os);
  output = String(os.str());
}
