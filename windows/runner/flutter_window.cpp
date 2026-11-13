#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include "flutter_window.h"

#include <windows.h>
#include <commdlg.h>
#include <dwmapi.h>
#include <flutter/standard_method_codec.h>
#include <objbase.h>
#include <shellapi.h>
#include <shlwapi.h>
#include <wincrypt.h>
#include <winhttp.h>
#include <wrl.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <optional>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "WebView2.h"
#include "flutter/generated_plugin_registrant.h"
#include "resource.h"
#include "utils.h"

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;

namespace {

#ifndef DWMWA_USE_IMMERSIVE_DARK_MODE
#define DWMWA_USE_IMMERSIVE_DARK_MODE 20
#endif

#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif

constexpr DWORD kDwmwcpRound = 2;

constexpr UINT kWmTrayIcon = WM_APP + 0x10;
constexpr UINT kWmUiTask = WM_APP + 0x11;

constexpr UINT_PTR kTimerApiInitWatchdog = 0x701;
constexpr UINT_PTR kTimerModalProbe = 0x702;

constexpr UINT kCmdTrayOpen = 41001;
constexpr UINT kCmdTrayMessages = 41002;
constexpr UINT kCmdTrayPublish = 41003;
constexpr UINT kCmdTrayQuit = 41004;

constexpr const wchar_t kModalWindowClass[] = L"LinDoFlutterModalPopupHost";
constexpr const wchar_t kBridgeBootstrapUrl[] =
    L"https://linux.do/__lindo_bridge__";

std::wstring Utf16FromUtf8(const std::string& utf8) {
  if (utf8.empty()) return L"";
  int needed = ::MultiByteToWideChar(
      CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), nullptr, 0);
  if (needed <= 0) return L"";
  std::wstring out(static_cast<size_t>(needed), L'\0');
  ::MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()),
                        out.data(), needed);
  return out;
}

std::string ToLowerAscii(std::string s) {
  for (char& c : s) {
    if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
  }
  return s;
}

std::wstring ToLowerWide(std::wstring s) {
  for (wchar_t& c : s) {
    if (c >= L'A' && c <= L'Z') c = static_cast<wchar_t>(c - L'A' + L'a');
  }
  return s;
}

std::string TrimAscii(const std::string& s) {
  size_t b = 0;
  while (b < s.size() && std::isspace(static_cast<unsigned char>(s[b]))) ++b;
  size_t e = s.size();
  while (e > b && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
  return s.substr(b, e - b);
}

std::wstring TrimWide(const std::wstring& s) {
  size_t b = 0;
  while (b < s.size() && iswspace(s[b])) ++b;
  size_t e = s.size();
  while (e > b && iswspace(s[e - 1])) --e;
  return s.substr(b, e - b);
}

std::string ResolveTargetUrlUtf8(const std::string& raw_url) {
  std::string trimmed = TrimAscii(raw_url);
  if (trimmed.empty()) return "";
  if (trimmed[0] == '/') return "https://linux.do" + trimmed;
  return trimmed;
}

bool ParseUrlSchemeHost(const std::string& url, std::string& scheme_out,
                        std::string& host_out) {
  size_t pos = url.find("://");
  if (pos == std::string::npos) return false;
  scheme_out = ToLowerAscii(url.substr(0, pos));
  size_t host_start = pos + 3;
  size_t host_end = url.find_first_of(":/?#", host_start);
  host_out = ToLowerAscii(host_end == std::string::npos
                              ? url.substr(host_start)
                              : url.substr(host_start, host_end - host_start));
  return !host_out.empty();
}

std::wstring JsQuoteWide(const std::wstring& s) {
  std::wstring out;
  out.reserve(s.size() + 16);
  out.push_back(L'"');
  for (wchar_t ch : s) {
    switch (ch) {
      case L'\\':
        out += L"\\\\";
        break;
      case L'"':
        out += L"\\\"";
        break;
      case L'\n':
        out += L"\\n";
        break;
      case L'\r':
        out += L"\\r";
        break;
      case L'\t':
        out += L"\\t";
        break;
      case 0x2028:
        out += L"\\u2028";
        break;
      case 0x2029:
        out += L"\\u2029";
        break;
      default:
        if (ch < 0x20) {
          wchar_t buf[8];
          wsprintfW(buf, L"\\u%04x", static_cast<unsigned int>(ch));
          out += buf;
        } else {
          out.push_back(ch);
        }
        break;
    }
  }
  out.push_back(L'"');
  return out;
}

std::string JsonQuoteUtf8(const std::string& s) {
  std::string out;
  out.reserve(s.size() + 16);
  out.push_back('"');
  for (unsigned char ch : s) {
    switch (ch) {
      case '\\':
        out += "\\\\";
        break;
      case '"':
        out += "\\\"";
        break;
      case '\b':
        out += "\\b";
        break;
      case '\f':
        out += "\\f";
        break;
      case '\n':
        out += "\\n";
        break;
      case '\r':
        out += "\\r";
        break;
      case '\t':
        out += "\\t";
        break;
      default:
        if (ch < 0x20) {
          char buf[8];
          snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned int>(ch));
          out += buf;
        } else {
          out.push_back(static_cast<char>(ch));
        }
        break;
    }
  }
  out.push_back('"');
  return out;
}

std::string StringMapToJson(
    const std::unordered_map<std::string, std::string>& m) {
  std::string out = "{";
  bool first = true;
  for (const auto& kv : m) {
    if (!first) out.push_back(',');
    first = false;
    out += JsonQuoteUtf8(kv.first);
    out.push_back(':');
    out += JsonQuoteUtf8(kv.second);
  }
  out.push_back('}');
  return out;
}

std::wstring UnescapeExecuteScriptString(const std::wstring& raw) {
  if (raw.size() < 2 || raw.front() != L'"' || raw.back() != L'"') {
    return raw == L"null" ? L"" : raw;
  }
  std::wstring out;
  out.reserve(raw.size() - 2);
  for (size_t i = 1; i + 1 < raw.size(); ++i) {
    wchar_t c = raw[i];
    if (c == L'\\' && i + 1 < raw.size() - 1) {
      wchar_t n = raw[++i];
      switch (n) {
        case L'"':
          out.push_back(L'"');
          break;
        case L'\\':
          out.push_back(L'\\');
          break;
        case L'/':
          out.push_back(L'/');
          break;
        case L'b':
          out.push_back(L'\b');
          break;
        case L'f':
          out.push_back(L'\f');
          break;
        case L'n':
          out.push_back(L'\n');
          break;
        case L'r':
          out.push_back(L'\r');
          break;
        case L't':
          out.push_back(L'\t');
          break;
        case L'u':
          if (i + 4 < raw.size() - 1) {
            wchar_t hex[5] = {raw[i + 1], raw[i + 2], raw[i + 3], raw[i + 4],
                              0};
            wchar_t* end_ptr = nullptr;
            unsigned long code = wcstoul(hex, &end_ptr, 16);
            if (end_ptr == hex + 4) {
              out.push_back(static_cast<wchar_t>(code));
              i += 4;
            }
          }
          break;
        default:
          out.push_back(n);
          break;
      }
    } else {
      out.push_back(c);
    }
  }
  return out;
}

// Minimal RFC-8259 JSON parser producing flutter::EncodableValue.
class MiniJsonParser {
 public:
  explicit MiniJsonParser(const std::string& src) : s_(src) {}

  bool Parse(flutter::EncodableValue& out) {
    SkipWs();
    if (!ParseValue(out)) return false;
    SkipWs();
    return pos_ == s_.size();
  }

 private:
  void SkipWs() {
    while (pos_ < s_.size()) {
      unsigned char c = static_cast<unsigned char>(s_[pos_]);
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
        ++pos_;
      } else {
        break;
      }
    }
  }

  static void AppendCodepointUtf8(uint32_t cp, std::string& dst) {
    if (cp <= 0x7F) {
      dst.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FF) {
      dst.push_back(static_cast<char>(0xC0 | (cp >> 6)));
      dst.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
      dst.push_back(static_cast<char>(0xE0 | (cp >> 12)));
      dst.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
      dst.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0x10FFFF) {
      dst.push_back(static_cast<char>(0xF0 | (cp >> 18)));
      dst.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
      dst.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
      dst.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
  }

  bool ParseHex4(uint32_t& out) {
    if (pos_ + 4 > s_.size()) return false;
    uint32_t v = 0;
    for (int i = 0; i < 4; ++i) {
      char c = s_[pos_++];
      v <<= 4;
      if (c >= '0' && c <= '9')
        v |= static_cast<uint32_t>(c - '0');
      else if (c >= 'a' && c <= 'f')
        v |= static_cast<uint32_t>(c - 'a' + 10);
      else if (c >= 'A' && c <= 'F')
        v |= static_cast<uint32_t>(c - 'A' + 10);
      else
        return false;
    }
    out = v;
    return true;
  }

  bool ParseString(std::string& out) {
    if (pos_ >= s_.size() || s_[pos_] != '"') return false;
    ++pos_;
    out.clear();
    while (pos_ < s_.size()) {
      char c = s_[pos_++];
      if (c == '"') return true;
      if (c == '\\') {
        if (pos_ >= s_.size()) return false;
        char esc = s_[pos_++];
        switch (esc) {
          case '"':
            out.push_back('"');
            break;
          case '\\':
            out.push_back('\\');
            break;
          case '/':
            out.push_back('/');
            break;
          case 'b':
            out.push_back('\b');
            break;
          case 'f':
            out.push_back('\f');
            break;
          case 'n':
            out.push_back('\n');
            break;
          case 'r':
            out.push_back('\r');
            break;
          case 't':
            out.push_back('\t');
            break;
          case 'u': {
            uint32_t u1 = 0;
            if (!ParseHex4(u1)) return false;
            if (u1 >= 0xD800 && u1 <= 0xDBFF && pos_ + 2 <= s_.size() &&
                s_[pos_] == '\\' && s_[pos_ + 1] == 'u') {
              size_t saved = pos_;
              pos_ += 2;
              uint32_t u2 = 0;
              if (ParseHex4(u2) && u2 >= 0xDC00 && u2 <= 0xDFFF) {
                uint32_t cp =
                    0x10000 + (((u1 - 0xD800) << 10) | (u2 - 0xDC00));
                AppendCodepointUtf8(cp, out);
                break;
              }
              pos_ = saved;
            }
            AppendCodepointUtf8(u1, out);
            break;
          }
          default:
            out.push_back(esc);
            break;
        }
      } else {
        out.push_back(c);
      }
    }
    return false;
  }

  bool ParseNumber(flutter::EncodableValue& out) {
    size_t start = pos_;
    if (pos_ < s_.size() && s_[pos_] == '-') ++pos_;
    if (pos_ >= s_.size() || !std::isdigit(static_cast<unsigned char>(s_[pos_])))
      return false;
    while (pos_ < s_.size() &&
           std::isdigit(static_cast<unsigned char>(s_[pos_]))) {
      ++pos_;
    }
    bool is_float = false;
    if (pos_ < s_.size() && s_[pos_] == '.') {
      is_float = true;
      ++pos_;
      while (pos_ < s_.size() &&
             std::isdigit(static_cast<unsigned char>(s_[pos_]))) {
        ++pos_;
      }
    }
    if (pos_ < s_.size() && (s_[pos_] == 'e' || s_[pos_] == 'E')) {
      is_float = true;
      ++pos_;
      if (pos_ < s_.size() && (s_[pos_] == '+' || s_[pos_] == '-')) ++pos_;
      while (pos_ < s_.size() &&
             std::isdigit(static_cast<unsigned char>(s_[pos_]))) {
        ++pos_;
      }
    }
    std::string token = s_.substr(start, pos_ - start);
    if (!is_float) {
      char* end_ptr = nullptr;
      long long iv = std::strtoll(token.c_str(), &end_ptr, 10);
      if (end_ptr == token.c_str() + token.size()) {
        if (iv >= INT32_MIN && iv <= INT32_MAX) {
          out = flutter::EncodableValue(static_cast<int32_t>(iv));
        } else {
          out = flutter::EncodableValue(static_cast<int64_t>(iv));
        }
        return true;
      }
    }
    char* end_ptr = nullptr;
    double dv = std::strtod(token.c_str(), &end_ptr);
    if (end_ptr != token.c_str() + token.size()) return false;
    out = flutter::EncodableValue(dv);
    return true;
  }

  bool ParseArray(flutter::EncodableValue& out) {
    ++pos_;  // '['
    flutter::EncodableList list;
    SkipWs();
    if (pos_ < s_.size() && s_[pos_] == ']') {
      ++pos_;
      out = flutter::EncodableValue(std::move(list));
      return true;
    }
    while (pos_ < s_.size()) {
      flutter::EncodableValue elem;
      if (!ParseValue(elem)) return false;
      list.push_back(std::move(elem));
      SkipWs();
      if (pos_ < s_.size() && s_[pos_] == ',') {
        ++pos_;
        SkipWs();
      } else if (pos_ < s_.size() && s_[pos_] == ']') {
        ++pos_;
        out = flutter::EncodableValue(std::move(list));
        return true;
      } else {
        return false;
      }
    }
    return false;
  }

  bool ParseObject(flutter::EncodableValue& out) {
    ++pos_;  // '{'
    flutter::EncodableMap map;
    SkipWs();
    if (pos_ < s_.size() && s_[pos_] == '}') {
      ++pos_;
      out = flutter::EncodableValue(std::move(map));
      return true;
    }
    while (pos_ < s_.size()) {
      std::string key;
      if (!ParseString(key)) return false;
      SkipWs();
      if (pos_ >= s_.size() || s_[pos_] != ':') return false;
      ++pos_;
      SkipWs();
      flutter::EncodableValue val;
      if (!ParseValue(val)) return false;
      map.emplace(flutter::EncodableValue(key), std::move(val));
      SkipWs();
      if (pos_ < s_.size() && s_[pos_] == ',') {
        ++pos_;
        SkipWs();
      } else if (pos_ < s_.size() && s_[pos_] == '}') {
        ++pos_;
        out = flutter::EncodableValue(std::move(map));
        return true;
      } else {
        return false;
      }
    }
    return false;
  }

  bool ParseValue(flutter::EncodableValue& out) {
    SkipWs();
    if (pos_ >= s_.size()) return false;
    char c = s_[pos_];
    if (c == '"') {
      std::string str;
      if (!ParseString(str)) return false;
      out = flutter::EncodableValue(std::move(str));
      return true;
    }
    if (c == '{') return ParseObject(out);
    if (c == '[') return ParseArray(out);
    if (c == 't' && s_.compare(pos_, 4, "true") == 0) {
      pos_ += 4;
      out = flutter::EncodableValue(true);
      return true;
    }
    if (c == 'f' && s_.compare(pos_, 5, "false") == 0) {
      pos_ += 5;
      out = flutter::EncodableValue(false);
      return true;
    }
    if (c == 'n' && s_.compare(pos_, 4, "null") == 0) {
      pos_ += 4;
      out = flutter::EncodableValue();
      return true;
    }
    if (c == '-' || std::isdigit(static_cast<unsigned char>(c))) {
      return ParseNumber(out);
    }
    return false;
  }

  const std::string& s_;
  size_t pos_ = 0;
};

const flutter::EncodableValue* FindMapField(const flutter::EncodableMap& map,
                                            const char* key) {
  auto it = map.find(flutter::EncodableValue(std::string(key)));
  return it != map.end() ? &it->second : nullptr;
}

std::string GetMapString(const flutter::EncodableMap& map, const char* key,
                         const std::string& fallback = "") {
  const auto* v = FindMapField(map, key);
  if (v && std::holds_alternative<std::string>(*v)) {
    return std::get<std::string>(*v);
  }
  return fallback;
}

bool GetMapBool(const flutter::EncodableMap& map, const char* key,
                bool fallback = false) {
  const auto* v = FindMapField(map, key);
  if (v && std::holds_alternative<bool>(*v)) {
    return std::get<bool>(*v);
  }
  return fallback;
}

int64_t GetMapInt(const flutter::EncodableMap& map, const char* key,
                  int64_t fallback = 0) {
  const auto* v = FindMapField(map, key);
  if (!v) return fallback;
  if (std::holds_alternative<int32_t>(*v)) {
    return static_cast<int64_t>(std::get<int32_t>(*v));
  }
  if (std::holds_alternative<int64_t>(*v)) {
    return std::get<int64_t>(*v);
  }
  if (std::holds_alternative<double>(*v)) {
    return static_cast<int64_t>(std::get<double>(*v));
  }
  return fallback;
}

double GetMapDouble(const flutter::EncodableMap& map, const char* key,
                    double fallback = 0.0) {
  const auto* v = FindMapField(map, key);
  if (!v) return fallback;
  if (std::holds_alternative<double>(*v)) return std::get<double>(*v);
  if (std::holds_alternative<int32_t>(*v)) {
    return static_cast<double>(std::get<int32_t>(*v));
  }
  if (std::holds_alternative<int64_t>(*v)) {
    return static_cast<double>(std::get<int64_t>(*v));
  }
  return fallback;
}

std::unordered_map<std::string, std::string> GetMapStringMap(
    const flutter::EncodableMap& map, const char* key) {
  std::unordered_map<std::string, std::string> out;
  const auto* v = FindMapField(map, key);
  if (!v || !std::holds_alternative<flutter::EncodableMap>(*v)) return out;
  const auto& inner = std::get<flutter::EncodableMap>(*v);
  for (const auto& kv : inner) {
    if (std::holds_alternative<std::string>(kv.first) &&
        std::holds_alternative<std::string>(kv.second)) {
      out[std::get<std::string>(kv.first)] = std::get<std::string>(kv.second);
    }
  }
  return out;
}

bool DecodeBase64Bytes(const std::string& b64, std::vector<uint8_t>& out) {
  if (b64.empty()) return false;
  DWORD len = 0;
  if (!::CryptStringToBinaryA(b64.data(), static_cast<DWORD>(b64.size()),
                              CRYPT_STRING_BASE64, nullptr, &len, nullptr,
                              nullptr) ||
      len == 0) {
    return false;
  }
  out.resize(len);
  if (!::CryptStringToBinaryA(b64.data(), static_cast<DWORD>(b64.size()),
                              CRYPT_STRING_BASE64, out.data(), &len, nullptr,
                              nullptr)) {
    out.clear();
    return false;
  }
  out.resize(len);
  return true;
}

std::string DetectMimeTypeFromPath(const std::wstring& path) {
  const wchar_t* ext_ptr = ::PathFindExtensionW(path.c_str());
  std::wstring ext = ext_ptr ? ToLowerWide(ext_ptr) : L"";
  if (ext == L".png") return "image/png";
  if (ext == L".jpg" || ext == L".jpeg") return "image/jpeg";
  if (ext == L".gif") return "image/gif";
  if (ext == L".webp") return "image/webp";
  if (ext == L".svg") return "image/svg+xml";
  if (ext == L".pdf") return "application/pdf";
  if (ext == L".txt" || ext == L".log" || ext == L".md") return "text/plain";
  if (ext == L".json") return "application/json";
  if (ext == L".zip") return "application/zip";
  if (ext == L".mp4") return "video/mp4";
  return "application/octet-stream";
}

struct ParsedCookieSpec {
  std::wstring name;
  std::wstring value;
  std::wstring domain;
  std::wstring path;
  bool secure = true;
  bool http_only = false;
};

std::vector<ParsedCookieSpec> ParseCookieDefinitions(
    const std::string& raw_cookies, const std::string& default_host_utf8) {
  std::vector<ParsedCookieSpec> out;
  std::wstring default_host =
      Utf16FromUtf8(default_host_utf8.empty() ? "linux.do" : default_host_utf8);

  auto is_attr_key = [](const std::string& k) {
    return k == "path" || k == "domain" || k == "expires" || k == "max-age" ||
           k == "samesite" || k == "secure" || k == "httponly" ||
           k == "priority" || k == "partitioned";
  };

  std::istringstream line_stream(raw_cookies);
  std::string line;
  while (std::getline(line_stream, line)) {
    line = TrimAscii(line);
    if (line.empty()) continue;

    std::vector<std::string> segs;
    size_t start = 0;
    while (start <= line.size()) {
      size_t semi = line.find(';', start);
      std::string piece =
          TrimAscii(semi == std::string::npos ? line.substr(start)
                                              : line.substr(start, semi - start));
      if (!piece.empty()) segs.push_back(piece);
      if (semi == std::string::npos) break;
      start = semi + 1;
    }
    if (segs.empty()) continue;

    bool has_attrs = false;
    for (size_t i = 1; i < segs.size(); ++i) {
      size_t eq = segs[i].find('=');
      std::string k = ToLowerAscii(
          TrimAscii(eq == std::string::npos ? segs[i] : segs[i].substr(0, eq)));
      if (is_attr_key(k)) {
        has_attrs = true;
        break;
      }
    }

    if (has_attrs) {
      size_t eq = segs[0].find('=');
      if (eq == std::string::npos || eq == 0) continue;
      std::string n = TrimAscii(segs[0].substr(0, eq));
      std::string v = TrimAscii(segs[0].substr(eq + 1));
      if (n.empty()) continue;

      bool host_only = (n == "_t" || n == "_forum_session");
      ParsedCookieSpec spec;
      spec.name = Utf16FromUtf8(n);
      spec.value = Utf16FromUtf8(v);
      spec.domain =
          host_only
              ? default_host
              : (default_host == L"linux.do" ? L".linux.do" : default_host);
      spec.path = L"/";
      spec.secure = true;
      spec.http_only = (host_only || n == "cf_clearance");

      for (size_t i = 1; i < segs.size(); ++i) {
        size_t aeq = segs[i].find('=');
        std::string ak = ToLowerAscii(TrimAscii(
            aeq == std::string::npos ? segs[i] : segs[i].substr(0, aeq)));
        std::string av = aeq == std::string::npos
                             ? ""
                             : TrimAscii(segs[i].substr(aeq + 1));
        if (ak == "domain" && !av.empty()) spec.domain = Utf16FromUtf8(av);
        if (ak == "path" && !av.empty()) spec.path = Utf16FromUtf8(av);
        if (ak == "secure") spec.secure = true;
        if (ak == "httponly") spec.http_only = true;
      }
      out.push_back(std::move(spec));
    } else {
      for (const auto& seg : segs) {
        size_t eq = seg.find('=');
        if (eq == std::string::npos || eq == 0) continue;
        std::string n = TrimAscii(seg.substr(0, eq));
        std::string v = TrimAscii(seg.substr(eq + 1));
        if (n.empty()) continue;
        bool host_only = (n == "_t" || n == "_forum_session");
        ParsedCookieSpec spec;
        spec.name = Utf16FromUtf8(n);
        spec.value = Utf16FromUtf8(v);
        spec.domain =
            host_only
                ? default_host
                : (default_host == L"linux.do" ? L".linux.do" : default_host);
        spec.path = L"/";
        spec.secure = true;
        spec.http_only = (host_only || n == "cf_clearance");
        out.push_back(std::move(spec));
      }
    }
  }
  return out;
}

std::string ExtractCsrfFromHtmlOrJson(
    const std::string& body,
    const std::unordered_map<std::string, std::string>& headers) {
  auto it = headers.find("x-csrf-token");
  if (it != headers.end() && !it->second.empty()) return it->second;

  std::string trimmed = TrimAscii(body);
  if (!trimmed.empty() && trimmed.front() == '{') {
    flutter::EncodableValue parsed;
    MiniJsonParser parser(trimmed);
    if (parser.Parse(parsed) &&
        std::holds_alternative<flutter::EncodableMap>(parsed)) {
      std::string csrf =
          GetMapString(std::get<flutter::EncodableMap>(parsed), "csrf");
      if (!csrf.empty()) return csrf;
    }
  }

  std::string lower = ToLowerAscii(body);
  size_t meta_pos = lower.find("csrf-token");
  if (meta_pos != std::string::npos) {
    size_t tag_start = lower.rfind("<meta", meta_pos);
    size_t tag_end = lower.find('>', meta_pos);
    if (tag_start != std::string::npos && tag_end != std::string::npos &&
        tag_end > tag_start) {
      std::string tag = body.substr(tag_start, tag_end - tag_start + 1);
      std::string tag_lower = ToLowerAscii(tag);
      size_t cpos = tag_lower.find("content=");
      if (cpos != std::string::npos) {
        cpos += 8;
        if (cpos < tag.size() && (tag[cpos] == '"' || tag[cpos] == '\'')) {
          char quote = tag[cpos++];
          size_t end_q = tag.find(quote, cpos);
          if (end_q != std::string::npos) {
            return tag.substr(cpos, end_q - cpos);
          }
        }
      }
    }
  }
  return "";
}

struct CookieSnapshotItem {
  std::string name;
  std::string value;
  std::string domain;
};

bool DomainMatchesCookie(const std::string& host, const std::string& ck_dom) {
  std::string d = ToLowerAscii(ck_dom);
  if (!d.empty() && d.front() == '.') d.erase(d.begin());
  if (d.empty()) return false;
  if (host == d) return true;
  if (host.size() > d.size() &&
      host.compare(host.size() - d.size(), d.size(), d) == 0 &&
      host[host.size() - d.size() - 1] == '.') {
    return true;
  }
  return false;
}

struct WinHttpResult {
  int status = 0;
  std::string final_url;
  std::unordered_map<std::string, std::string> headers;
  std::vector<uint8_t> raw_bytes;
  std::vector<std::string> set_cookies;
  std::string error;
};

WinHttpResult PerformWinHttpRequest(
    const std::string& initial_url, const std::string& method,
    const std::unordered_map<std::string, std::string>& req_headers,
    const std::optional<std::string>& req_body,
    const std::string& user_agent_utf8,
    std::vector<CookieSnapshotItem> cookies) {
  WinHttpResult out;
  out.final_url = initial_url;

  std::wstring ua =
      Utf16FromUtf8(user_agent_utf8.empty() ? "Mozilla/5.0" : user_agent_utf8);
  HINTERNET h_session = ::WinHttpOpen(ua.c_str(),
                                      WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                      WINHTTP_NO_PROXY_NAME,
                                      WINHTTP_NO_PROXY_BYPASS, 0);
  if (!h_session) {
    out.error = "WinHttpOpen failed";
    return out;
  }

  ::WinHttpSetTimeouts(h_session, 10000, 10000, 15000, 20000);

  std::string current_url = initial_url;
  std::string current_method = method;
  std::optional<std::string> current_body = req_body;

  for (int hop = 0; hop < 8; ++hop) {
    std::wstring wurl = Utf16FromUtf8(current_url);
    URL_COMPONENTS uc{};
    uc.dwStructSize = sizeof(uc);
    uc.dwSchemeLength = static_cast<DWORD>(-1);
    uc.dwHostNameLength = static_cast<DWORD>(-1);
    uc.dwUrlPathLength = static_cast<DWORD>(-1);
    uc.dwExtraInfoLength = static_cast<DWORD>(-1);

    if (!::WinHttpCrackUrl(wurl.c_str(), static_cast<DWORD>(wurl.size()), 0,
                           &uc)) {
      out.error = "Invalid URL";
      break;
    }

    std::wstring whost(uc.lpszHostName, uc.dwHostNameLength);
    std::wstring wpath;
    if (uc.lpszUrlPath && uc.dwUrlPathLength > 0) {
      wpath.assign(uc.lpszUrlPath, uc.dwUrlPathLength);
    } else {
      wpath = L"/";
    }
    if (uc.lpszExtraInfo && uc.dwExtraInfoLength > 0) {
      wpath.append(uc.lpszExtraInfo, uc.dwExtraInfoLength);
    }
    std::string host_lower = ToLowerAscii(Utf8FromUtf16(whost.c_str()));

    HINTERNET h_connect =
        ::WinHttpConnect(h_session, whost.c_str(), uc.nPort, 0);
    if (!h_connect) {
      out.error = "WinHttpConnect failed";
      break;
    }

    DWORD req_flags =
        (uc.nScheme == INTERNET_SCHEME_HTTPS) ? WINHTTP_FLAG_SECURE : 0;
    std::wstring wmethod = Utf16FromUtf8(current_method);
    HINTERNET h_req =
        ::WinHttpOpenRequest(h_connect, wmethod.c_str(), wpath.c_str(), nullptr,
                             WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                             req_flags);
    if (!h_req) {
      ::WinHttpCloseHandle(h_connect);
      out.error = "WinHttpOpenRequest failed";
      break;
    }

    DWORD disable_flags = WINHTTP_DISABLE_REDIRECTS | WINHTTP_DISABLE_COOKIES;
    ::WinHttpSetOption(h_req, WINHTTP_OPTION_DISABLE_FEATURE, &disable_flags,
                       sizeof(disable_flags));

    for (const auto& kv : req_headers) {
      std::string lk = ToLowerAscii(kv.first);
      if (lk == "cookie" || lk == "host" || lk == "content-length") continue;
      std::wstring hdr_line =
          Utf16FromUtf8(kv.first + ": " + kv.second + "\r\n");
      ::WinHttpAddRequestHeaders(h_req, hdr_line.c_str(),
                                 static_cast<DWORD>(hdr_line.size()),
                                 WINHTTP_ADDREQ_FLAG_ADD |
                                     WINHTTP_ADDREQ_FLAG_REPLACE);
    }

    std::string cookie_hdr;
    for (const auto& ck : cookies) {
      if (DomainMatchesCookie(host_lower, ck.domain) && !ck.name.empty()) {
        if (!cookie_hdr.empty()) cookie_hdr += "; ";
        cookie_hdr += ck.name + "=" + ck.value;
      }
    }
    if (!cookie_hdr.empty()) {
      std::wstring wck = Utf16FromUtf8("Cookie: " + cookie_hdr + "\r\n");
      ::WinHttpAddRequestHeaders(h_req, wck.c_str(),
                                 static_cast<DWORD>(wck.size()),
                                 WINHTTP_ADDREQ_FLAG_ADD |
                                     WINHTTP_ADDREQ_FLAG_REPLACE);
    }

    const void* body_ptr = WINHTTP_NO_REQUEST_DATA;
    DWORD body_len = 0;
    if (current_method != "GET" && current_method != "HEAD" &&
        current_body.has_value()) {
      body_ptr = current_body->data();
      body_len = static_cast<DWORD>(current_body->size());
    }

    BOOL sent = ::WinHttpSendRequest(h_req, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                                     const_cast<void*>(body_ptr), body_len,
                                     body_len, 0);
    if (!sent || !::WinHttpReceiveResponse(h_req, nullptr)) {
      ::WinHttpCloseHandle(h_req);
      ::WinHttpCloseHandle(h_connect);
      out.error = "Network request failed";
      break;
    }

    DWORD status_code = 0;
    DWORD status_sz = sizeof(status_code);
    ::WinHttpQueryHeaders(
        h_req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
        WINHTTP_HEADER_NAME_BY_INDEX, &status_code, &status_sz,
        WINHTTP_NO_HEADER_INDEX);

    DWORD raw_hdr_bytes = 0;
    ::WinHttpQueryHeaders(h_req, WINHTTP_QUERY_RAW_HEADERS_CRLF,
                          WINHTTP_HEADER_NAME_BY_INDEX, nullptr, &raw_hdr_bytes,
                          WINHTTP_NO_HEADER_INDEX);
    std::unordered_map<std::string, std::string> resp_headers;
    std::string location_hdr;
    if (raw_hdr_bytes > sizeof(wchar_t)) {
      std::wstring raw_hdr_w(raw_hdr_bytes / sizeof(wchar_t), L'\0');
      if (::WinHttpQueryHeaders(h_req, WINHTTP_QUERY_RAW_HEADERS_CRLF,
                                WINHTTP_HEADER_NAME_BY_INDEX, raw_hdr_w.data(),
                                &raw_hdr_bytes, WINHTTP_NO_HEADER_INDEX)) {
        std::string raw_hdr_u8 = Utf8FromUtf16(raw_hdr_w.c_str());
        std::istringstream hs(raw_hdr_u8);
        std::string hline;
        bool first_status_line = true;
        while (std::getline(hs, hline)) {
          hline = TrimAscii(hline);
          if (first_status_line) {
            first_status_line = false;
            continue;
          }
          size_t colon = hline.find(':');
          if (colon == std::string::npos) continue;
          std::string hk = ToLowerAscii(TrimAscii(hline.substr(0, colon)));
          std::string hv = TrimAscii(hline.substr(colon + 1));
          if (hk == "set-cookie") {
            out.set_cookies.push_back(hv);
            auto parsed = ParseCookieDefinitions(hv, host_lower);
            for (const auto& sp : parsed) {
              std::string cn = Utf8FromUtf16(sp.name.c_str());
              std::string cv = Utf8FromUtf16(sp.value.c_str());
              std::string cd = Utf8FromUtf16(sp.domain.c_str());
              bool updated = false;
              for (auto& existing : cookies) {
                if (existing.name == cn && existing.domain == cd) {
                  existing.value = cv;
                  updated = true;
                  break;
                }
              }
              if (!updated) cookies.push_back({cn, cv, cd});
            }
          } else {
            if (hk == "location") location_hdr = hv;
            resp_headers[hk] = hv;
          }
        }
      }
    }

    if ((status_code == 301 || status_code == 302 || status_code == 303 ||
         status_code == 307 || status_code == 308) &&
        !location_hdr.empty()) {
      ::WinHttpCloseHandle(h_req);
      ::WinHttpCloseHandle(h_connect);
      if (location_hdr[0] == '/') {
        std::string scheme_str =
            (uc.nScheme == INTERNET_SCHEME_HTTPS) ? "https://" : "http://";
        current_url =
            scheme_str + Utf8FromUtf16(whost.c_str()) + location_hdr;
      } else {
        current_url = location_hdr;
      }
      out.final_url = current_url;
      if (status_code == 301 || status_code == 302 || status_code == 303) {
        current_method = "GET";
        current_body = std::nullopt;
      }
      continue;
    }

    std::vector<uint8_t> body_bytes;
    DWORD avail = 0;
    while (::WinHttpQueryDataAvailable(h_req, &avail) && avail > 0) {
      size_t prev_sz = body_bytes.size();
      body_bytes.resize(prev_sz + avail);
      DWORD read_cnt = 0;
      if (!::WinHttpReadData(h_req, body_bytes.data() + prev_sz, avail,
                             &read_cnt)) {
        body_bytes.resize(prev_sz);
        break;
      }
      body_bytes.resize(prev_sz + read_cnt);
    }

    ::WinHttpCloseHandle(h_req);
    ::WinHttpCloseHandle(h_connect);

    out.status = static_cast<int>(status_code);
    out.final_url = current_url;
    out.headers = std::move(resp_headers);
    out.raw_bytes = std::move(body_bytes);
    break;
  }

  ::WinHttpCloseHandle(h_session);
  return out;
}

}  // namespace

struct FlutterWindow::WebBridgeState {
  std::wstring webview_data_dir;
  ComPtr<ICoreWebView2Environment> env;
  HWND hidden_api_hwnd = nullptr;
  ComPtr<ICoreWebView2Controller> api_controller;
  ComPtr<ICoreWebView2> api_webview;

  bool api_initializing = false;
  bool api_ready = false;
  std::string cached_user_agent;
  std::vector<std::function<void(bool)>> ready_callbacks;

  uint64_t next_call_id = 1;
  std::unordered_map<uint64_t,
                     std::function<void(bool, const std::string&)>>
      pending_js_calls;

  bool modal_class_registered = false;
  HWND modal_hwnd = nullptr;
  ComPtr<ICoreWebView2Controller> modal_controller;
  ComPtr<ICoreWebView2> modal_webview;
  std::string modal_mode = "login";
  double modal_left = 0.1;
  double modal_top = 0.1;
  double modal_width = 0.8;
  double modal_height = 0.8;
  bool modal_is_dark = false;
  bool modal_completed = false;
  bool modal_probe_in_flight = false;
  uint64_t modal_generation = 0;

  void CompleteApiInit(HWND owner_hwnd, bool ok) {
    if (owner_hwnd) ::KillTimer(owner_hwnd, kTimerApiInitWatchdog);
    api_initializing = false;
    api_ready = ok;
    auto callbacks = std::move(ready_callbacks);
    ready_callbacks.clear();
    for (auto& cb : callbacks) {
      cb(ok);
    }
  }

  void SyncModalBounds(HWND parent_hwnd) {
    if (!modal_hwnd || !parent_hwnd || !::IsWindow(parent_hwnd)) return;
    if (::IsIconic(parent_hwnd) || !::IsWindowVisible(parent_hwnd)) {
      ::ShowWindow(modal_hwnd, SW_HIDE);
      return;
    }

    HMONITOR mon = ::MonitorFromWindow(parent_hwnd, MONITOR_DEFAULTTONEAREST);
    UINT dpi = FlutterDesktopGetDpiForMonitor(mon);
    double scale = dpi > 0 ? (static_cast<double>(dpi) / 96.0) : 1.0;

    RECT client_rc{};
    ::GetClientRect(parent_hwnd, &client_rc);
    double cw = static_cast<double>(
        (std::max)(1L, client_rc.right - client_rc.left));
    double ch = static_cast<double>(
        (std::max)(1L, client_rc.bottom - client_rc.top));

    POINT origin = {0, 0};
    ::ClientToScreen(parent_hwnd, &origin);

    int x_px = origin.x + static_cast<int>(modal_left * cw + 0.5);
    int y_px = origin.y + static_cast<int>(modal_top * ch + 0.5);
    int w_px = (std::max)(1, static_cast<int>(modal_width * cw + 0.5));
    int h_px = (std::max)(1, static_cast<int>(modal_height * ch + 0.5));
    int r_px = (std::max)(0, static_cast<int>(14.0 * scale * 2.0 + 0.5));

    ::SetWindowPos(modal_hwnd, HWND_TOP, x_px, y_px, w_px, h_px,
                   SWP_NOACTIVATE | SWP_SHOWWINDOW);
    if (r_px > 0) {
      HRGN rgn = ::CreateRoundRectRgn(0, 0, w_px + 1, h_px + 1, r_px, r_px);
      ::SetWindowRgn(modal_hwnd, rgn, TRUE);
    }
    if (modal_controller) {
      RECT bounds = {0, 0, w_px, h_px};
      modal_controller->put_Bounds(bounds);
      modal_controller->put_IsVisible(TRUE);
    }
  }

  void CloseModal(HWND parent_hwnd,
                  flutter::MethodChannel<flutter::EncodableValue>* channel,
                  bool notify_dart) {
    ++modal_generation;
    modal_completed = true;
    modal_probe_in_flight = false;

    if (parent_hwnd) {
      ::KillTimer(parent_hwnd, kTimerModalProbe);
    }

    bool had_modal = (modal_hwnd != nullptr);
    if (modal_controller) {
      modal_controller->Close();
      modal_controller.Reset();
    }
    modal_webview.Reset();
    if (modal_hwnd) {
      ::DestroyWindow(modal_hwnd);
      modal_hwnd = nullptr;
    }
    if (notify_dart && had_modal && channel) {
      channel->InvokeMethod(
          "onModalClosed",
          std::make_unique<flutter::EncodableValue>(flutter::EncodableMap{}));
    }
  }
};

FlutterWindow::FlutterWindow(const flutter::DartProject& project,
                             const std::string& initial_deep_link)
    : project_(project),
      pending_deep_link_(initial_deep_link),
      web_state_(std::make_unique<WebBridgeState>()) {}

FlutterWindow::~FlutterWindow() {}

bool FlutterWindow::OnCreate() {
  if (!Win32Window::OnCreate()) {
    return false;
  }

  RECT frame = GetClientArea();

  flutter_controller_ = std::make_unique<flutter::FlutterViewController>(
      frame.right - frame.left, frame.bottom - frame.top, project_);
  if (!flutter_controller_->engine() || !flutter_controller_->view()) {
    return false;
  }
  RegisterPlugins(flutter_controller_->engine());
  SetChildContent(flutter_controller_->view()->GetNativeWindow());

  SetupChannels();
  InitTrayIcon();

  flutter_controller_->engine()->SetNextFrameCallback([&]() { this->Show(); });
  flutter_controller_->ForceRedraw();

  return true;
}

void FlutterWindow::OnDestroy() {
  HWND hwnd = GetHandle();
  if (web_state_) {
    web_state_->CloseModal(hwnd, nullptr, false);
    if (web_state_->api_controller) {
      web_state_->api_controller->Close();
      web_state_->api_controller.Reset();
    }
    web_state_->api_webview.Reset();
    web_state_->env.Reset();
    if (web_state_->hidden_api_hwnd) {
      ::DestroyWindow(web_state_->hidden_api_hwnd);
      web_state_->hidden_api_hwnd = nullptr;
    }
  }
  RemoveTrayIcon();

  if (platform_channel_) {
    platform_channel_->SetMethodCallHandler(nullptr);
    platform_channel_.reset();
  }
  if (browser_channel_) {
    browser_channel_->SetMethodCallHandler(nullptr);
    browser_channel_.reset();
  }

  if (flutter_controller_) {
    flutter_controller_ = nullptr;
  }

  Win32Window::OnDestroy();
}

void FlutterWindow::PostUiTask(std::function<void()> task) {
  {
    std::lock_guard<std::mutex> lock(ui_task_mutex_);
    ui_tasks_.push_back(std::move(task));
  }
  HWND hwnd = GetHandle();
  if (hwnd && ::IsWindow(hwnd)) {
    ::PostMessageW(hwnd, kWmUiTask, 0, 0);
  }
}

void FlutterWindow::DrainUiTasks() {
  std::vector<std::function<void()>> batch;
  {
    std::lock_guard<std::mutex> lock(ui_task_mutex_);
    batch.swap(ui_tasks_);
  }
  for (auto& fn : batch) {
    if (fn) fn();
  }
}

std::wstring FlutterWindow::EnsureStateDirWide() const {
  wchar_t* profile_buf = nullptr;
  size_t profile_len = 0;
  std::wstring base_dir;
  if (_wdupenv_s(&profile_buf, &profile_len, L"USERPROFILE") == 0 &&
      profile_buf && profile_len > 0) {
    base_dir.assign(profile_buf);
    free(profile_buf);
  } else {
    wchar_t tmp_path[MAX_PATH] = {};
    ::GetTempPathW(MAX_PATH, tmp_path);
    base_dir.assign(tmp_path);
  }
  if (!base_dir.empty() && base_dir.back() != L'\\' &&
      base_dir.back() != L'/') {
    base_dir.push_back(L'\\');
  }
  std::wstring state_dir = base_dir + L".lindo_flutter";
  ::CreateDirectoryW(state_dir.c_str(), nullptr);
  return state_dir;
}

void FlutterWindow::ApplyWindowDarkMode(bool is_dark) {
  is_dark_mode_ = is_dark;
  HWND hwnd = GetHandle();
  if (hwnd) {
    BOOL dark_flag = is_dark ? TRUE : FALSE;
    ::DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, &dark_flag,
                            sizeof(dark_flag));
  }
  if (web_state_ && web_state_->modal_hwnd) {
    web_state_->modal_is_dark = is_dark;
    BOOL dark_flag = is_dark ? TRUE : FALSE;
    ::DwmSetWindowAttribute(web_state_->modal_hwnd,
                            DWMWA_USE_IMMERSIVE_DARK_MODE, &dark_flag,
                            sizeof(dark_flag));
  }
}

void FlutterWindow::InitTrayIcon() {
  HWND hwnd = GetHandle();
  if (!hwnd || tray_added_) return;

  std::memset(&tray_nid_, 0, sizeof(tray_nid_));
  tray_nid_.cbSize = sizeof(NOTIFYICONDATAW);
  tray_nid_.hWnd = hwnd;
  tray_nid_.uID = 1;
  tray_nid_.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
  tray_nid_.uCallbackMessage = kWmTrayIcon;
  tray_nid_.hIcon = ::LoadIconW(::GetModuleHandleW(nullptr),
                                MAKEINTRESOURCEW(IDI_APP_ICON));
  wcscpy_s(tray_nid_.szTip, L"Lindo");

  if (::Shell_NotifyIconW(NIM_ADD, &tray_nid_)) {
    tray_added_ = true;
  }
}

void FlutterWindow::UpdateTrayTooltip() {
  if (!tray_added_) {
    InitTrayIcon();
  }
  if (!tray_added_) return;

  std::wstring tip = L"Lindo";
  if (!tray_username_.empty()) {
    tip += L" (@" + tray_username_ + L")";
  }
  if (unread_count_ > 0) {
    tip += L" - " + std::to_wstring(unread_count_) + L" 条未读消息";
  }
  if (tip.size() >= std::size(tray_nid_.szTip)) {
    tip.resize(std::size(tray_nid_.szTip) - 1);
  }
  wcsncpy_s(tray_nid_.szTip, tip.c_str(), _TRUNCATE);
  tray_nid_.uFlags = NIF_TIP | NIF_ICON | NIF_MESSAGE;
  ::Shell_NotifyIconW(NIM_MODIFY, &tray_nid_);
}

void FlutterWindow::RemoveTrayIcon() {
  if (tray_added_) {
    ::Shell_NotifyIconW(NIM_DELETE, &tray_nid_);
    tray_added_ = false;
  }
}

void FlutterWindow::RestoreMainWindow() {
  HWND hwnd = GetHandle();
  if (!hwnd) return;
  if (!::IsWindowVisible(hwnd)) {
    ::ShowWindow(hwnd, SW_SHOW);
  }
  if (::IsIconic(hwnd)) {
    ::ShowWindow(hwnd, SW_RESTORE);
  }
  ::SetForegroundWindow(hwnd);
  if (web_state_) {
    web_state_->SyncModalBounds(hwnd);
  }
}

void FlutterWindow::ShowTrayContextMenu() {
  HWND hwnd = GetHandle();
  if (!hwnd) return;

  HMENU menu = ::CreatePopupMenu();
  if (!menu) return;

  ::AppendMenuW(menu, MF_STRING, kCmdTrayOpen, L"打开 Lindo");
  std::wstring msg_label =
      unread_count_ > 0
          ? (L"查看未读消息 (" + std::to_wstring(unread_count_) + L")")
          : L"查看未读消息";
  ::AppendMenuW(menu, MF_STRING, kCmdTrayMessages, msg_label.c_str());
  ::AppendMenuW(menu, MF_STRING, kCmdTrayPublish, L"发布新话题");
  ::AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
  ::AppendMenuW(menu, MF_STRING, kCmdTrayQuit, L"退出 Lindo");

  POINT pt{};
  ::GetCursorPos(&pt);
  ::SetForegroundWindow(hwnd);
  UINT selected = ::TrackPopupMenu(
      menu, TPM_RETURNCMD | TPM_NONOTIFY | TPM_RIGHTBUTTON, pt.x, pt.y, 0, hwnd,
      nullptr);
  ::PostMessageW(hwnd, WM_NULL, 0, 0);
  ::DestroyMenu(menu);

  switch (selected) {
    case kCmdTrayOpen:
      RestoreMainWindow();
      break;
    case kCmdTrayMessages:
      RestoreMainWindow();
      if (platform_channel_) {
        platform_channel_->InvokeMethod(
            "onTrayCommand",
            std::make_unique<flutter::EncodableValue>(flutter::EncodableMap{
                {flutter::EncodableValue("command"),
                 flutter::EncodableValue("open_messages")},
            }));
      }
      break;
    case kCmdTrayPublish:
      RestoreMainWindow();
      if (platform_channel_) {
        platform_channel_->InvokeMethod(
            "onTrayCommand",
            std::make_unique<flutter::EncodableValue>(flutter::EncodableMap{
                {flutter::EncodableValue("command"),
                 flutter::EncodableValue("open_publish")},
            }));
      }
      break;
    case kCmdTrayQuit:
      is_quitting_ = true;
      ::DestroyWindow(hwnd);
      break;
    default:
      break;
  }
}

void FlutterWindow::SetupChannels() {
  auto* messenger = flutter_controller_->engine()->messenger();
  const auto* codec = &flutter::StandardMethodCodec::GetInstance();

  platform_channel_ =
      std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
          messenger, "do_.linux.lindo/platform", codec);
  browser_channel_ =
      std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
          messenger, "do_.linux.lindo/browser", codec);

  platform_channel_->SetMethodCallHandler(
      [this](const auto& call, auto result) {
        this->HandlePlatformCall(call, std::move(result));
      });
  browser_channel_->SetMethodCallHandler(
      [this](const auto& call, auto result) {
        this->HandleBrowserCall(call, std::move(result));
      });
}

void FlutterWindow::HandlePlatformCall(
    const flutter::MethodCall<flutter::EncodableValue>& call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  const std::string& method = call.method_name();
  flutter::EncodableMap empty_args;
  const flutter::EncodableMap& args =
      (call.arguments() &&
       std::holds_alternative<flutter::EncodableMap>(*call.arguments()))
          ? std::get<flutter::EncodableMap>(*call.arguments())
          : empty_args;

  if (method == "getStateDirectory") {
    std::wstring state_dir = EnsureStateDirWide();
    if (!pending_deep_link_.empty() && platform_channel_) {
      std::string uri = std::move(pending_deep_link_);
      pending_deep_link_.clear();
      platform_channel_->InvokeMethod(
          "onAuthDeepLink",
          std::make_unique<flutter::EncodableValue>(flutter::EncodableMap{
              {flutter::EncodableValue("uri"), flutter::EncodableValue(uri)},
          }));
    }
    result->Success(
        flutter::EncodableValue(Utf8FromUtf16(state_dir.c_str())));
    return;
  }

  if (method == "syncWindowTheme") {
    bool is_dark = GetMapBool(args, "isDark", false);
    ApplyWindowDarkMode(is_dark);
    result->Success();
    return;
  }

  if (method == "updateTrayState") {
    unread_count_ = static_cast<int>(
        (std::max)(int64_t{0}, GetMapInt(args, "unreadCount", 0)));
    tray_username_ =
        TrimWide(Utf16FromUtf8(GetMapString(args, "username", "")));
    UpdateTrayTooltip();
    result->Success();
    return;
  }

  if (method == "applyLauncherIcon") {
    result->Success(flutter::EncodableValue(true));
    return;
  }

  if (method == "saveDiagnosticFile") {
    std::string suggested =
        GetMapString(args, "suggestedName", "lindo-diagnostics.log");
    std::string content = GetMapString(args, "content", "");

    wchar_t file_buf[MAX_PATH] = {};
    std::wstring wname = Utf16FromUtf8(suggested);
    wcsncpy_s(file_buf, wname.c_str(), _TRUNCATE);

    OPENFILENAMEW ofn{};
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = GetHandle();
    ofn.lpstrFilter = L"Log Files (*.log)\0*.log\0All Files (*.*)\0*.*\0";
    ofn.lpstrFile = file_buf;
    ofn.nMaxFile = MAX_PATH;
    ofn.lpstrDefExt = L"log";
    ofn.Flags = OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;

    if (!::GetSaveFileNameW(&ofn)) {
      result->Success();
      return;
    }

    HANDLE hf = ::CreateFileW(file_buf, GENERIC_WRITE, 0, nullptr,
                              CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hf == INVALID_HANDLE_VALUE) {
      result->Success();
      return;
    }
    DWORD written = 0;
    BOOL ok = content.empty()
                  ? TRUE
                  : ::WriteFile(hf, content.data(),
                                static_cast<DWORD>(content.size()), &written,
                                nullptr);
    ::CloseHandle(hf);
    if (!ok) {
      result->Success();
      return;
    }
    result->Success(flutter::EncodableValue(Utf8FromUtf16(file_buf)));
    return;
  }

  if (method == "pickAttachmentFile") {
    wchar_t file_buf[MAX_PATH] = {};
    OPENFILENAMEW ofn{};
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = GetHandle();
    ofn.lpstrFilter = L"All Files (*.*)\0*.*\0";
    ofn.lpstrFile = file_buf;
    ofn.nMaxFile = MAX_PATH;
    ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;

    if (!::GetOpenFileNameW(&ofn)) {
      result->Success();
      return;
    }

    HANDLE hf =
        ::CreateFileW(file_buf, GENERIC_READ, FILE_SHARE_READ, nullptr,
                      OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hf == INVALID_HANDLE_VALUE) {
      result->Success();
      return;
    }
    LARGE_INTEGER file_size{};
    if (!::GetFileSizeEx(hf, &file_size) || file_size.QuadPart < 0 ||
        file_size.QuadPart > 64LL * 1024LL * 1024LL) {
      ::CloseHandle(hf);
      result->Success();
      return;
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(file_size.QuadPart));
    DWORD total_read = 0;
    if (!bytes.empty()) {
      if (!::ReadFile(hf, bytes.data(), static_cast<DWORD>(bytes.size()),
                      &total_read, nullptr)) {
        ::CloseHandle(hf);
        result->Success();
        return;
      }
      bytes.resize(total_read);
    }
    ::CloseHandle(hf);

    std::wstring full_path(file_buf);
    const wchar_t* base_name = ::PathFindFileNameW(full_path.c_str());
    std::string name_u8 =
        Utf8FromUtf16(base_name ? base_name : full_path.c_str());
    std::string mime = DetectMimeTypeFromPath(full_path);

    result->Success(flutter::EncodableValue(flutter::EncodableMap{
        {flutter::EncodableValue("name"), flutter::EncodableValue(name_u8)},
        {flutter::EncodableValue("mimeType"), flutter::EncodableValue(mime)},
        {flutter::EncodableValue("bytes"),
         flutter::EncodableValue(std::move(bytes))},
        {flutter::EncodableValue("path"),
         flutter::EncodableValue(Utf8FromUtf16(full_path.c_str()))},
    }));
    return;
  }

  if (method == "openExternalUrl") {
    std::string url = GetMapString(args, "url", "");
    if (url.empty()) {
      result->Success(flutter::EncodableValue(false));
      return;
    }
    std::wstring wurl = Utf16FromUtf8(url);
    HINSTANCE h = ::ShellExecuteW(nullptr, L"open", wurl.c_str(), nullptr,
                                  nullptr, SW_SHOWNORMAL);
    bool launched = reinterpret_cast<INT_PTR>(h) > 32;
    result->Success(flutter::EncodableValue(launched));
    return;
  }

  result->NotImplemented();
}

void FlutterWindow::HandleBrowserCall(
    const flutter::MethodCall<flutter::EncodableValue>& call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  const std::string& method = call.method_name();
  flutter::EncodableMap empty_args;
  const flutter::EncodableMap& args =
      (call.arguments() &&
       std::holds_alternative<flutter::EncodableMap>(*call.arguments()))
          ? std::get<flutter::EncodableMap>(*call.arguments())
          : empty_args;

  std::shared_ptr<flutter::MethodResult<flutter::EncodableValue>> shared_res(
      std::move(result));

  auto ensure_api_ready = [this](std::function<void(bool)> on_ready) {
    if (web_state_->api_ready && web_state_->api_webview) {
      on_ready(true);
      return;
    }

    LPWSTR ver_info = nullptr;
    HRESULT ver_hr =
        ::GetAvailableCoreWebView2BrowserVersionString(nullptr, &ver_info);
    if (ver_info) ::CoTaskMemFree(ver_info);
    if (FAILED(ver_hr)) {
      on_ready(false);
      return;
    }

    web_state_->ready_callbacks.push_back(std::move(on_ready));
    if (web_state_->api_initializing) return;
    web_state_->api_initializing = true;

    HWND owner_hwnd = GetHandle();
    if (owner_hwnd) {
      ::SetTimer(owner_hwnd, kTimerApiInitWatchdog, 4800, nullptr);
    }

    std::wstring state_dir = EnsureStateDirWide();
    web_state_->webview_data_dir = state_dir + L"\\webview2";
    ::CreateDirectoryW(web_state_->webview_data_dir.c_str(), nullptr);

    if (!web_state_->hidden_api_hwnd) {
      web_state_->hidden_api_hwnd = ::CreateWindowExW(
          WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, L"STATIC", L"LinDoApiHost",
          WS_POPUP, -10000, -10000, 64, 64, nullptr, nullptr,
          ::GetModuleHandleW(nullptr), nullptr);
    }

    auto init_controller = [this, owner_hwnd]() {
      if (!web_state_->env || !web_state_->hidden_api_hwnd) {
        web_state_->CompleteApiInit(owner_hwnd, false);
        return;
      }
      web_state_->env->CreateCoreWebView2Controller(
          web_state_->hidden_api_hwnd,
          Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
              [this, owner_hwnd](HRESULT hr,
                                 ICoreWebView2Controller* ctrl) -> HRESULT {
                if (FAILED(hr) || !ctrl) {
                  web_state_->CompleteApiInit(owner_hwnd, false);
                  return S_OK;
                }
                web_state_->api_controller = ctrl;
                RECT b = {0, 0, 64, 64};
                ctrl->put_Bounds(b);
                ctrl->get_CoreWebView2(&web_state_->api_webview);
                if (!web_state_->api_webview) {
                  web_state_->CompleteApiInit(owner_hwnd, false);
                  return S_OK;
                }

                ComPtr<ICoreWebView2Settings> settings;
                if (SUCCEEDED(
                        web_state_->api_webview->get_Settings(&settings)) &&
                    settings) {
                  settings->put_IsScriptEnabled(TRUE);
                  settings->put_IsWebMessageEnabled(TRUE);
                  settings->put_AreDefaultScriptDialogsEnabled(FALSE);
                }

                // Zero-network https://linux.do origin bootstrap via local HTML response
                web_state_->api_webview->AddWebResourceRequestedFilter(
                    kBridgeBootstrapUrl,
                    COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT);
                web_state_->api_webview->add_WebResourceRequested(
                    Callback<ICoreWebView2WebResourceRequestedEventHandler>(
                        [this](ICoreWebView2*,
                               ICoreWebView2WebResourceRequestedEventArgs* e)
                            -> HRESULT {
                          if (!e || !web_state_->env) return S_OK;
                          const char* html =
                              "<!doctype html><html><head><meta "
                              "charset='utf-8'></head><body></body></html>";
                          size_t len = std::strlen(html);
                          HGLOBAL hmem = ::GlobalAlloc(GMEM_MOVEABLE, len);
                          if (!hmem) return S_OK;
                          void* p = ::GlobalLock(hmem);
                          if (p) {
                            std::memcpy(p, html, len);
                            ::GlobalUnlock(hmem);
                          }
                          ComPtr<IStream> stream;
                          if (SUCCEEDED(::CreateStreamOnHGlobal(hmem, TRUE,
                                                                &stream)) &&
                              stream) {
                            ComPtr<ICoreWebView2WebResourceResponse> resp;
                            if (SUCCEEDED(
                                    web_state_->env->CreateWebResourceResponse(
                                        stream.Get(), 200, L"OK",
                                        L"Content-Type: text/html; "
                                        L"charset=utf-8\r\nCache-Control: "
                                        L"no-store",
                                        &resp)) &&
                                resp) {
                              e->put_Response(resp.Get());
                            }
                          }
                          return S_OK;
                        })
                        .Get(),
                    nullptr);

                web_state_->api_webview->add_WebMessageReceived(
                    Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                        [this](ICoreWebView2*,
                               ICoreWebView2WebMessageReceivedEventArgs* e)
                            -> HRESULT {
                          if (!e) return S_OK;
                          LPWSTR raw_msg = nullptr;
                          if (FAILED(e->TryGetWebMessageAsString(&raw_msg)) ||
                              !raw_msg) {
                            return S_OK;
                          }
                          std::string msg = Utf8FromUtf16(raw_msg);
                          ::CoTaskMemFree(raw_msg);

                          const std::string prefix = "LINDOCB\t";
                          if (msg.rfind(prefix, 0) != 0) return S_OK;
                          size_t t1 = prefix.size();
                          size_t t2 = msg.find('\t', t1);
                          if (t2 == std::string::npos) return S_OK;
                          size_t t3 = msg.find('\t', t2 + 1);
                          if (t3 == std::string::npos) return S_OK;
                          bool ok = (msg.substr(t1, t2 - t1) == "1");
                          uint64_t cid = std::strtoull(
                              msg.substr(t2 + 1, t3 - t2 - 1).c_str(), nullptr,
                              10);
                          std::string payload = msg.substr(t3 + 1);

                          auto it = web_state_->pending_js_calls.find(cid);
                          if (it != web_state_->pending_js_calls.end()) {
                            auto cb = std::move(it->second);
                            web_state_->pending_js_calls.erase(it);
                            cb(ok, payload);
                          }
                          return S_OK;
                        })
                        .Get(),
                    nullptr);

                web_state_->api_webview->add_NavigationCompleted(
                    Callback<ICoreWebView2NavigationCompletedEventHandler>(
                        [this, owner_hwnd](
                            ICoreWebView2* sender,
                            ICoreWebView2NavigationCompletedEventArgs*)
                            -> HRESULT {
                          if (web_state_->api_ready) return S_OK;
                          sender->ExecuteScript(
                              L"navigator.userAgent",
                              Callback<
                                  ICoreWebView2ExecuteScriptCompletedHandler>(
                                  [this, owner_hwnd](
                                      HRESULT, LPCWSTR json_res) -> HRESULT {
                                    std::wstring ua =
                                        UnescapeExecuteScriptString(
                                            json_res ? json_res : L"");
                                    web_state_->cached_user_agent =
                                        Utf8FromUtf16(ua.c_str());
                                    web_state_->CompleteApiInit(owner_hwnd,
                                                                true);
                                    return S_OK;
                                  })
                                  .Get());
                          return S_OK;
                        })
                        .Get(),
                    nullptr);

                web_state_->api_webview->Navigate(kBridgeBootstrapUrl);
                return S_OK;
              })
              .Get());
    };

    if (web_state_->env) {
      init_controller();
    } else {
      ::CreateCoreWebView2EnvironmentWithOptions(
          nullptr, web_state_->webview_data_dir.c_str(), nullptr,
          Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
              [this, owner_hwnd,
               init_controller](HRESULT hr,
                                ICoreWebView2Environment* created_env) -> HRESULT {
                if (FAILED(hr) || !created_env) {
                  web_state_->CompleteApiInit(owner_hwnd, false);
                  return S_OK;
                }
                web_state_->env = created_env;
                init_controller();
                return S_OK;
              })
              .Get());
    }
  };

  auto run_api_async_js =
      [this](const std::wstring& script_body, const std::string& arg_json_u8,
             std::function<void(bool, const std::string&)> cb) {
        if (!web_state_->api_webview) {
          cb(false, "WebView2 API bridge unavailable");
          return;
        }
        uint64_t cid = web_state_->next_call_id++;
        web_state_->pending_js_calls[cid] = std::move(cb);

        std::wstring cid_w = std::to_wstring(cid);
        std::wstring arg_w = Utf16FromUtf8(arg_json_u8);
        std::wstring wrapper =
            L"(function(){ const __cid = '" + cid_w + L"'; const __arg = " +
            JsQuoteWide(arg_w) + L"; (async function(argJson){\n" +
            script_body +
            L"\n})(__arg).then(function(r){ "
            L"window.chrome.webview.postMessage('LINDOCB\\t1\\t' + __cid + "
            L"'\\t' + (r == null ? '' : String(r))); }, function(e){ "
            L"window.chrome.webview.postMessage('LINDOCB\\t0\\t' + __cid + "
            L"'\\t' + (e && e.message ? e.message : String(e))); }); })();";
        web_state_->api_webview->ExecuteScript(wrapper.c_str(), nullptr);
      };

  auto snapshot_all_cookies =
      [this](std::function<void(std::vector<CookieSnapshotItem>)> on_done) {
        if (!web_state_->api_webview) {
          on_done({});
          return;
        }
        ComPtr<ICoreWebView2_2> wv2;
        if (FAILED(web_state_->api_webview.As(&wv2)) || !wv2) {
          on_done({});
          return;
        }
        ComPtr<ICoreWebView2CookieManager> cm;
        if (FAILED(wv2->get_CookieManager(&cm)) || !cm) {
          on_done({});
          return;
        }
        cm->GetCookies(
            L"",
            Callback<ICoreWebView2GetCookiesCompletedHandler>(
                [on_done](HRESULT hr, ICoreWebView2CookieList* list) -> HRESULT {
                  std::vector<CookieSnapshotItem> items;
                  if (SUCCEEDED(hr) && list) {
                    UINT count = 0;
                    list->get_Count(&count);
                    for (UINT i = 0; i < count; ++i) {
                      ComPtr<ICoreWebView2Cookie> ck;
                      if (SUCCEEDED(list->GetValueAtIndex(i, &ck)) && ck) {
                        LPWSTR n = nullptr;
                        LPWSTR v = nullptr;
                        LPWSTR d = nullptr;
                        ck->get_Name(&n);
                        ck->get_Value(&v);
                        ck->get_Domain(&d);
                        if (n && n[0] != 0) {
                          items.push_back({Utf8FromUtf16(n),
                                           v ? Utf8FromUtf16(v) : "",
                                           d ? Utf8FromUtf16(d) : ""});
                        }
                        if (n) ::CoTaskMemFree(n);
                        if (v) ::CoTaskMemFree(v);
                        if (d) ::CoTaskMemFree(d);
                      }
                    }
                  }
                  on_done(std::move(items));
                  return S_OK;
                })
                .Get());
      };

  auto apply_cookie_specs_to_manager =
      [this](const std::vector<ParsedCookieSpec>& specs) {
        if (!web_state_->api_webview || specs.empty()) return;
        ComPtr<ICoreWebView2_2> wv2;
        if (FAILED(web_state_->api_webview.As(&wv2)) || !wv2) return;
        ComPtr<ICoreWebView2CookieManager> cm;
        if (FAILED(wv2->get_CookieManager(&cm)) || !cm) return;
        double expires =
            static_cast<double>(std::time(nullptr)) + 86400.0 * 180.0;
        for (const auto& sp : specs) {
          ComPtr<ICoreWebView2Cookie> ck;
          if (SUCCEEDED(cm->CreateCookie(sp.name.c_str(), sp.value.c_str(),
                                         sp.domain.c_str(), sp.path.c_str(),
                                         &ck)) &&
              ck) {
            ck->put_IsSecure(sp.secure ? TRUE : FALSE);
            ck->put_IsHttpOnly(sp.http_only ? TRUE : FALSE);
            ck->put_Expires(expires);
            cm->AddOrUpdateCookie(ck.Get());
          }
        }
      };

  if (method == "initialize") {
    ensure_api_ready([this, shared_res](bool ok) {
      shared_res->Success(flutter::EncodableValue(flutter::EncodableMap{
          {flutter::EncodableValue("ok"), flutter::EncodableValue(ok)},
          {flutter::EncodableValue("userAgent"),
           flutter::EncodableValue(ok ? web_state_->cached_user_agent
                                      : std::string(""))},
      }));
    });
    return;
  }

  if (method == "executeFetch") {
    std::string req_method =
        GetMapString(args, "method", "GET");
    for (char& c : req_method) {
      if (c >= 'a' && c <= 'z') c = static_cast<char>(c - 'a' + 'A');
    }
    std::string raw_url = GetMapString(args, "url", "");
    std::string target_url = ResolveTargetUrlUtf8(raw_url);
    auto headers = GetMapStringMap(args, "headers");
    std::optional<std::string> req_body;
    if (const auto* bv = FindMapField(args, "body");
        bv && std::holds_alternative<std::string>(*bv)) {
      req_body = std::get<std::string>(*bv);
    }

    if (target_url.empty()) {
      shared_res->Success(flutter::EncodableValue(flutter::EncodableMap{
          {flutter::EncodableValue("status"), flutter::EncodableValue(0)},
          {flutter::EncodableValue("url"), flutter::EncodableValue(raw_url)},
          {flutter::EncodableValue("headers"),
           flutter::EncodableValue(flutter::EncodableMap{})},
          {flutter::EncodableValue("body"), flutter::EncodableValue("")},
          {flutter::EncodableValue("csrfToken"), flutter::EncodableValue("")},
          {flutter::EncodableValue("error"),
           flutter::EncodableValue("Invalid URL")},
      }));
      return;
    }

    auto run_winhttp_fallback = [this, shared_res, target_url, req_method,
                                 headers, req_body, snapshot_all_cookies,
                                 apply_cookie_specs_to_manager]() {
      std::string ua = web_state_->cached_user_agent;
      snapshot_all_cookies([this, shared_res, target_url, req_method, headers,
                            req_body, ua, apply_cookie_specs_to_manager](
                               std::vector<CookieSnapshotItem> cookies) {
        std::thread([this, shared_res, target_url, req_method, headers,
                     req_body, ua, cookies = std::move(cookies),
                     apply_cookie_specs_to_manager]() mutable {
          WinHttpResult http_res =
              PerformWinHttpRequest(target_url, req_method, headers, req_body,
                                    ua, std::move(cookies));
          PostUiTask([shared_res, http_res = std::move(http_res),
                      apply_cookie_specs_to_manager]() mutable {
            std::string host;
            std::string scheme;
            ParseUrlSchemeHost(http_res.final_url, scheme, host);
            for (const auto& sc : http_res.set_cookies) {
              auto specs = ParseCookieDefinitions(sc, host);
              apply_cookie_specs_to_manager(specs);
            }

            std::string body_str(
                reinterpret_cast<const char*>(http_res.raw_bytes.data()),
                http_res.raw_bytes.size());
            std::string csrf =
                ExtractCsrfFromHtmlOrJson(body_str, http_res.headers);

            flutter::EncodableMap hdr_enc;
            for (const auto& kv : http_res.headers) {
              hdr_enc.emplace(flutter::EncodableValue(kv.first),
                              flutter::EncodableValue(kv.second));
            }
            flutter::EncodableMap out_map{
                {flutter::EncodableValue("status"),
                 flutter::EncodableValue(http_res.status)},
                {flutter::EncodableValue("url"),
                 flutter::EncodableValue(http_res.final_url)},
                {flutter::EncodableValue("headers"),
                 flutter::EncodableValue(std::move(hdr_enc))},
                {flutter::EncodableValue("body"),
                 flutter::EncodableValue(std::move(body_str))},
                {flutter::EncodableValue("csrfToken"),
                 flutter::EncodableValue(csrf)},
            };
            if (!http_res.error.empty()) {
              out_map.emplace(flutter::EncodableValue("error"),
                              flutter::EncodableValue(http_res.error));
            }
            shared_res->Success(flutter::EncodableValue(std::move(out_map)));
          });
        }).detach();
      });
    };

    ensure_api_ready([shared_res, target_url, req_method, headers, req_body,
                      run_api_async_js, run_winhttp_fallback](bool ok) {
      if (!ok) {
        shared_res->Success(flutter::EncodableValue(flutter::EncodableMap{
            {flutter::EncodableValue("status"), flutter::EncodableValue(0)},
            {flutter::EncodableValue("url"),
             flutter::EncodableValue(target_url)},
            {flutter::EncodableValue("headers"),
             flutter::EncodableValue(flutter::EncodableMap{})},
            {flutter::EncodableValue("body"), flutter::EncodableValue("")},
            {flutter::EncodableValue("csrfToken"), flutter::EncodableValue("")},
            {flutter::EncodableValue("error"),
             flutter::EncodableValue("WebView2 runtime unavailable")},
        }));
        return;
      }

      std::string scheme;
      std::string host;
      bool is_same_origin = ParseUrlSchemeHost(target_url, scheme, host) &&
                            scheme == "https" && host == "linux.do";
      if (!is_same_origin) {
        run_winhttp_fallback();
        return;
      }

      std::string arg_json =
          std::string("{\"url\":") + JsonQuoteUtf8(target_url) +
          ",\"method\":" + JsonQuoteUtf8(req_method) +
          ",\"headers\":" + StringMapToJson(headers) + ",\"body\":" +
          (req_body.has_value() ? JsonQuoteUtf8(*req_body) : "null") + "}";

      static const std::wstring kFetchJs = LR"JS(
        const cfg = JSON.parse(argJson);
        const forbidden = new Set([
          'cookie', 'user-agent', 'host', 'origin', 'referer',
          'content-length', 'accept-encoding', 'connection'
        ]);
        const safeHeaders = {};
        let refererUrl = 'https://linux.do/';
        if (cfg.headers && typeof cfg.headers === 'object') {
          for (const [k, v] of Object.entries(cfg.headers)) {
            const lower = String(k).toLowerCase();
            if (lower === 'referer' && v) {
              refererUrl = String(v);
              continue;
            }
            if (!forbidden.has(lower) && !lower.startsWith('sec-')) {
              safeHeaders[k] = String(v);
            }
          }
        }
        const isRead = cfg.method === 'GET' || cfg.method === 'HEAD';
        if (!isRead && !safeHeaders['X-CSRF-Token'] && !safeHeaders['x-csrf-token']) {
          const meta = document.querySelector('meta[name="csrf-token"]');
          const cached = (meta && meta.content) || window.__lindoCsrf || '';
          if (cached) safeHeaders['X-CSRF-Token'] = cached;
        }
        const opts = {
          method: cfg.method,
          headers: safeHeaders,
          credentials: 'include',
          redirect: 'follow',
          referrer: refererUrl
        };
        if (!isRead && cfg.body !== null && cfg.body !== undefined) {
          opts.body = String(cfg.body);
        }
        try {
          const resp = await fetch(cfg.url, opts);
          const text = await resp.text();
          const outHeaders = {};
          resp.headers.forEach((v, k) => {
            outHeaders[String(k).toLowerCase()] = String(v);
          });
          let csrf = outHeaders['x-csrf-token'] || '';
          if (!csrf && text) {
            if (text.trim().startsWith('{')) {
              try {
                const parsed = JSON.parse(text);
                if (parsed && typeof parsed.csrf === 'string') csrf = parsed.csrf;
              } catch (_) {}
            }
            if (!csrf) {
              const m = text.match(/<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']/i)
                     || text.match(/<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']/i);
              if (m && m[1]) csrf = m[1];
            }
          }
          if (!csrf && window.__lindoCsrf) csrf = window.__lindoCsrf;
          if (csrf) window.__lindoCsrf = csrf;
          return JSON.stringify({
            ok: true,
            status: resp.status || 0,
            url: resp.url || cfg.url,
            headers: outHeaders,
            body: text,
            csrfToken: csrf
          });
        } catch (err) {
          return JSON.stringify({
            ok: false,
            error: (err && err.message) ? err.message : String(err)
          });
        }
      )JS";

      run_api_async_js(
          kFetchJs, arg_json,
          [shared_res, target_url, run_winhttp_fallback](
              bool js_ok, const std::string& payload) {
            flutter::EncodableValue parsed;
            MiniJsonParser parser(payload);
            if (js_ok && parser.Parse(parsed) &&
                std::holds_alternative<flutter::EncodableMap>(parsed)) {
              auto& m = std::get<flutter::EncodableMap>(parsed);
              if (GetMapBool(m, "ok", false)) {
                m.erase(flutter::EncodableValue("ok"));
                shared_res->Success(flutter::EncodableValue(std::move(m)));
                return;
              }
            }
            run_winhttp_fallback();
          });
    });
    return;
  }

  if (method == "uploadMultipart") {
    std::string raw_url = GetMapString(args, "url", "");
    std::string target_url = ResolveTargetUrlUtf8(raw_url);
    auto headers = GetMapStringMap(args, "headers");
    auto fields = GetMapStringMap(args, "fields");
    std::string file_field = GetMapString(args, "fileField", "files[]");
    std::string file_name = GetMapString(args, "fileName", "upload.bin");
    std::string mime_type =
        GetMapString(args, "mimeType", "application/octet-stream");
    std::string base64_data = GetMapString(args, "base64Data", "");

    ensure_api_ready([shared_res, target_url, headers, fields, file_field,
                      file_name, mime_type, base64_data,
                      run_api_async_js](bool ok) {
      if (!ok || target_url.empty()) {
        shared_res->Success(flutter::EncodableValue(flutter::EncodableMap{
            {flutter::EncodableValue("status"), flutter::EncodableValue(0)},
            {flutter::EncodableValue("url"),
             flutter::EncodableValue(target_url)},
            {flutter::EncodableValue("body"), flutter::EncodableValue("")},
            {flutter::EncodableValue("csrfToken"), flutter::EncodableValue("")},
            {flutter::EncodableValue("error"),
             flutter::EncodableValue("Upload bridge unavailable")},
        }));
        return;
      }

      std::string arg_json =
          std::string("{\"url\":") + JsonQuoteUtf8(target_url) +
          ",\"headers\":" + StringMapToJson(headers) +
          ",\"fields\":" + StringMapToJson(fields) +
          ",\"fileField\":" + JsonQuoteUtf8(file_field) +
          ",\"fileName\":" + JsonQuoteUtf8(file_name) +
          ",\"mimeType\":" + JsonQuoteUtf8(mime_type) +
          ",\"base64Data\":" + JsonQuoteUtf8(base64_data) + "}";

      static const std::wstring kUploadJs = LR"JS(
        const cfg = JSON.parse(argJson);
        const forbidden = new Set([
          'cookie', 'user-agent', 'host', 'origin', 'referer',
          'content-length', 'content-type', 'accept-encoding', 'connection'
        ]);
        const safeHeaders = {};
        if (cfg.headers && typeof cfg.headers === 'object') {
          for (const [k, v] of Object.entries(cfg.headers)) {
            const lower = String(k).toLowerCase();
            if (!forbidden.has(lower) && !lower.startsWith('sec-')) {
              safeHeaders[k] = String(v);
            }
          }
        }
        if (!safeHeaders['X-CSRF-Token'] && !safeHeaders['x-csrf-token']) {
          let token = window.__lindoCsrf || '';
          if (!token) {
            try {
              const cr = await fetch('https://linux.do/session/csrf.json', {
                headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
                credentials: 'include',
                cache: 'no-store'
              });
              if (cr.ok) {
                const cd = await cr.json();
                if (cd && cd.csrf) {
                  token = cd.csrf;
                  window.__lindoCsrf = token;
                }
              }
            } catch (_) {}
          }
          if (token) safeHeaders['X-CSRF-Token'] = token;
        }
        const binStr = atob(cfg.base64Data || '');
        const bytes = new Uint8Array(binStr.length);
        for (let i = 0; i < binStr.length; i++) {
          bytes[i] = binStr.charCodeAt(i);
        }
        const blob = new Blob([bytes], { type: cfg.mimeType || 'application/octet-stream' });
        const formData = new FormData();
        if (cfg.fields && typeof cfg.fields === 'object') {
          for (const [k, v] of Object.entries(cfg.fields)) {
            formData.append(k, String(v));
          }
        }
        formData.append(cfg.fileField || 'files[]', blob, cfg.fileName || 'upload.bin');
        try {
          const resp = await fetch(cfg.url, {
            method: 'POST',
            headers: safeHeaders,
            body: formData,
            credentials: 'include',
            referrer: 'https://linux.do/'
          });
          const text = await resp.text();
          const csrf = resp.headers.get('x-csrf-token') || window.__lindoCsrf || '';
          if (csrf) window.__lindoCsrf = csrf;
          return JSON.stringify({
            status: resp.status || 0,
            url: resp.url || cfg.url,
            body: text,
            csrfToken: csrf
          });
        } catch (err) {
          return JSON.stringify({
            status: 0,
            url: cfg.url,
            body: '',
            csrfToken: '',
            error: (err && err.message) ? err.message : String(err)
          });
        }
      )JS";

      run_api_async_js(kUploadJs, arg_json,
                       [shared_res, target_url](bool js_ok,
                                                const std::string& payload) {
                         flutter::EncodableValue parsed;
                         MiniJsonParser parser(payload);
                         if (js_ok && parser.Parse(parsed) &&
                             std::holds_alternative<flutter::EncodableMap>(
                                 parsed)) {
                           shared_res->Success(std::move(parsed));
                           return;
                         }
                         shared_res->Success(
                             flutter::EncodableValue(flutter::EncodableMap{
                                 {flutter::EncodableValue("status"),
                                  flutter::EncodableValue(0)},
                                 {flutter::EncodableValue("url"),
                                  flutter::EncodableValue(target_url)},
                                 {flutter::EncodableValue("body"),
                                  flutter::EncodableValue("")},
                                 {flutter::EncodableValue("csrfToken"),
                                  flutter::EncodableValue("")},
                                 {flutter::EncodableValue("error"),
                                  flutter::EncodableValue(payload)},
                             }));
                       });
    });
    return;
  }

  if (method == "fetchBinary") {
    std::string target_url = ResolveTargetUrlUtf8(GetMapString(args, "url", ""));
    if (target_url.empty()) {
      shared_res->Success();
      return;
    }

    auto fetch_binary_winhttp = [this, shared_res, target_url,
                                 snapshot_all_cookies]() {
      std::string ua = web_state_->cached_user_agent;
      snapshot_all_cookies(
          [this, shared_res, target_url, ua](
              std::vector<CookieSnapshotItem> cookies) {
            std::thread([this, shared_res, target_url, ua,
                         cookies = std::move(cookies)]() mutable {
              WinHttpResult res = PerformWinHttpRequest(
                  target_url, "GET", {}, std::nullopt, ua, std::move(cookies));
              PostUiTask([shared_res, res = std::move(res)]() mutable {
                if (res.status >= 200 && res.status < 300 &&
                    !res.raw_bytes.empty()) {
                  auto ct_it = res.headers.find("content-type");
                  if (ct_it != res.headers.end() &&
                      ct_it->second.find("text/html") != std::string::npos) {
                    shared_res->Success();
                    return;
                  }
                  shared_res->Success(
                      flutter::EncodableValue(std::move(res.raw_bytes)));
                } else {
                  shared_res->Success();
                }
              });
            }).detach();
          });
    };

    ensure_api_ready([shared_res, target_url, run_api_async_js,
                      fetch_binary_winhttp](bool ok) {
      if (!ok) {
        shared_res->Success();
        return;
      }
      std::string scheme;
      std::string host;
      bool same_origin = ParseUrlSchemeHost(target_url, scheme, host) &&
                         scheme == "https" && host == "linux.do";
      if (!same_origin) {
        fetch_binary_winhttp();
        return;
      }

      std::string arg_json =
          std::string("{\"url\":") + JsonQuoteUtf8(target_url) + "}";
      static const std::wstring kBinJs = LR"JS(
        const cfg = JSON.parse(argJson);
        try {
          const resp = await fetch(cfg.url, {
            method: 'GET',
            credentials: 'include',
            referrer: 'https://linux.do/'
          });
          if (!resp.ok) return '';
          const ct = (resp.headers.get('content-type') || '').toLowerCase();
          if (ct.includes('text/html')) return '';
          const buf = await resp.arrayBuffer();
          const bytes = new Uint8Array(buf);
          const chunk = 0x6000;
          const parts = [];
          for (let i = 0; i < bytes.length; i += chunk) {
            parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + chunk)));
          }
          return btoa(parts.join(''));
        } catch (_) {
          return '';
        }
      )JS";

      run_api_async_js(
          kBinJs, arg_json,
          [shared_res, fetch_binary_winhttp](bool js_ok,
                                             const std::string& b64) {
            std::vector<uint8_t> decoded;
            if (js_ok && !b64.empty() && DecodeBase64Bytes(b64, decoded)) {
              shared_res->Success(flutter::EncodableValue(std::move(decoded)));
              return;
            }
            fetch_binary_winhttp();
          });
    });
    return;
  }

  if (method == "getCookies") {
    std::string target_url =
        ResolveTargetUrlUtf8(GetMapString(args, "url", "https://linux.do/"));
    ensure_api_ready([this, shared_res, target_url](bool ok) {
      if (!ok || !web_state_->api_webview) {
        shared_res->Success(flutter::EncodableValue(""));
        return;
      }
      ComPtr<ICoreWebView2_2> wv2;
      ComPtr<ICoreWebView2CookieManager> cm;
      if (FAILED(web_state_->api_webview.As(&wv2)) || !wv2 ||
          FAILED(wv2->get_CookieManager(&cm)) || !cm) {
        shared_res->Success(flutter::EncodableValue(""));
        return;
      }
      std::wstring wurl = Utf16FromUtf8(target_url);
      cm->GetCookies(
          wurl.c_str(),
          Callback<ICoreWebView2GetCookiesCompletedHandler>(
              [shared_res](HRESULT hr, ICoreWebView2CookieList* list) -> HRESULT {
                std::string joined;
                if (SUCCEEDED(hr) && list) {
                  UINT count = 0;
                  list->get_Count(&count);
                  for (UINT i = 0; i < count; ++i) {
                    ComPtr<ICoreWebView2Cookie> ck;
                    if (SUCCEEDED(list->GetValueAtIndex(i, &ck)) && ck) {
                      LPWSTR n = nullptr;
                      LPWSTR v = nullptr;
                      ck->get_Name(&n);
                      ck->get_Value(&v);
                      if (n && n[0] != 0) {
                        if (!joined.empty()) joined += "; ";
                        joined += Utf8FromUtf16(n);
                        joined.push_back('=');
                        if (v) joined += Utf8FromUtf16(v);
                      }
                      if (n) ::CoTaskMemFree(n);
                      if (v) ::CoTaskMemFree(v);
                    }
                  }
                }
                shared_res->Success(flutter::EncodableValue(joined));
                return S_OK;
              })
              .Get());
    });
    return;
  }

  if (method == "setCookies") {
    std::string target_url =
        ResolveTargetUrlUtf8(GetMapString(args, "url", "https://linux.do/"));
    std::string raw_cookies = GetMapString(args, "cookies", "");
    ensure_api_ready([shared_res, target_url, raw_cookies,
                      apply_cookie_specs_to_manager](bool ok) {
      if (ok && !raw_cookies.empty()) {
        std::string scheme;
        std::string host;
        ParseUrlSchemeHost(target_url, scheme, host);
        auto specs = ParseCookieDefinitions(raw_cookies, host);
        apply_cookie_specs_to_manager(specs);
      }
      shared_res->Success();
    });
    return;
  }

  if (method == "clearSession") {
    ensure_api_ready([this, shared_res](bool ok) {
      if (ok && web_state_->api_webview) {
        ComPtr<ICoreWebView2_2> wv2;
        ComPtr<ICoreWebView2CookieManager> cm;
        if (SUCCEEDED(web_state_->api_webview.As(&wv2)) && wv2 &&
            SUCCEEDED(wv2->get_CookieManager(&cm)) && cm) {
          cm->DeleteAllCookies();
        }
        web_state_->api_webview->ExecuteScript(L"window.__lindoCsrf = null;",
                                               nullptr);
        web_state_->api_webview->Navigate(kBridgeBootstrapUrl);
      }
      shared_res->Success();
    });
    return;
  }

  if (method == "openModal") {
    std::string url =
        ResolveTargetUrlUtf8(GetMapString(args, "url", "https://linux.do/login"));
    std::string mode = GetMapString(args, "mode", "login");
    double left = GetMapDouble(args, "left", 0.1);
    double top = GetMapDouble(args, "top", 0.1);
    double width = GetMapDouble(args, "width", 0.8);
    double height = GetMapDouble(args, "height", 0.8);
    bool is_dark = GetMapBool(args, "isDark", is_dark_mode_);

    ensure_api_ready([this, url, mode, left, top, width, height,
                      is_dark](bool ok) {
      if (!ok || !web_state_->env) return;
      HWND parent_hwnd = GetHandle();
      if (!parent_hwnd) return;

      ++web_state_->modal_generation;
      uint64_t active_gen = web_state_->modal_generation;
      web_state_->modal_mode = mode;
      web_state_->modal_left = left;
      web_state_->modal_top = top;
      web_state_->modal_width = width;
      web_state_->modal_height = height;
      web_state_->modal_is_dark = is_dark;
      web_state_->modal_completed = false;
      web_state_->modal_probe_in_flight = false;

      if (!web_state_->modal_class_registered) {
        WNDCLASSEXW mwc{};
        mwc.cbSize = sizeof(mwc);
        mwc.lpfnWndProc = ::DefWindowProcW;
        mwc.hInstance = ::GetModuleHandleW(nullptr);
        mwc.hbrBackground =
            reinterpret_cast<HBRUSH>(::GetStockObject(BLACK_BRUSH));
        mwc.lpszClassName = kModalWindowClass;
        ::RegisterClassExW(&mwc);
        web_state_->modal_class_registered = true;
      }

      if (!web_state_->modal_hwnd) {
        web_state_->modal_hwnd = ::CreateWindowExW(
            0, kModalWindowClass, L"", WS_POPUP | WS_CLIPCHILDREN, 0, 0, 480,
            560, parent_hwnd, nullptr, ::GetModuleHandleW(nullptr), nullptr);
        DWORD corner_pref = kDwmwcpRound;
        ::DwmSetWindowAttribute(web_state_->modal_hwnd,
                                DWMWA_WINDOW_CORNER_PREFERENCE, &corner_pref,
                                sizeof(corner_pref));
        BOOL dark_flag = is_dark ? TRUE : FALSE;
        ::DwmSetWindowAttribute(web_state_->modal_hwnd,
                                DWMWA_USE_IMMERSIVE_DARK_MODE, &dark_flag,
                                sizeof(dark_flag));
        web_state_->SyncModalBounds(parent_hwnd);

        std::wstring initial_wurl = Utf16FromUtf8(url);
        web_state_->env->CreateCoreWebView2Controller(
            web_state_->modal_hwnd,
            Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                [this, parent_hwnd, initial_wurl, active_gen](
                    HRESULT hr, ICoreWebView2Controller* ctrl) -> HRESULT {
                  if (FAILED(hr) || !ctrl || !web_state_->modal_hwnd ||
                      active_gen != web_state_->modal_generation) {
                    return S_OK;
                  }
                  web_state_->modal_controller = ctrl;
                  ctrl->get_CoreWebView2(&web_state_->modal_webview);
                  if (!web_state_->modal_webview) return S_OK;

                  ComPtr<ICoreWebView2Settings> settings;
                  if (SUCCEEDED(
                          web_state_->modal_webview->get_Settings(&settings)) &&
                      settings) {
                    settings->put_IsScriptEnabled(TRUE);
                    settings->put_IsWebMessageEnabled(TRUE);
                  }

                  web_state_->modal_webview->add_NavigationStarting(
                      Callback<ICoreWebView2NavigationStartingEventHandler>(
                          [this](ICoreWebView2*,
                                 ICoreWebView2NavigationStartingEventArgs* e)
                              -> HRESULT {
                            if (!e) return S_OK;
                            LPWSTR raw_uri = nullptr;
                            e->get_Uri(&raw_uri);
                            std::string uri =
                                raw_uri ? Utf8FromUtf16(raw_uri) : "";
                            if (raw_uri) ::CoTaskMemFree(raw_uri);

                            if (uri.rfind("lindo://", 0) == 0 ||
                                uri.rfind("discourse://", 0) == 0) {
                              e->put_Cancel(TRUE);
                              if (platform_channel_) {
                                platform_channel_->InvokeMethod(
                                    "onAuthDeepLink",
                                    std::make_unique<flutter::EncodableValue>(
                                        flutter::EncodableMap{
                                            {flutter::EncodableValue("uri"),
                                             flutter::EncodableValue(uri)},
                                        }));
                              }
                              return S_OK;
                            }

                            if (browser_channel_) {
                              browser_channel_->InvokeMethod(
                                  "onModalPageStarted",
                                  std::make_unique<flutter::EncodableValue>(
                                      flutter::EncodableMap{
                                          {flutter::EncodableValue("url"),
                                           flutter::EncodableValue(uri)},
                                      }));
                              browser_channel_->InvokeMethod(
                                  "onModalProgress",
                                  std::make_unique<flutter::EncodableValue>(
                                      flutter::EncodableMap{
                                          {flutter::EncodableValue("progress"),
                                           flutter::EncodableValue(0.2)},
                                          {flutter::EncodableValue("url"),
                                           flutter::EncodableValue(uri)},
                                      }));
                            }
                            return S_OK;
                          })
                          .Get(),
                      nullptr);

                  web_state_->modal_webview->add_ContentLoading(
                      Callback<ICoreWebView2ContentLoadingEventHandler>(
                          [this](ICoreWebView2* sender,
                                 ICoreWebView2ContentLoadingEventArgs*)
                              -> HRESULT {
                            LPWSTR src = nullptr;
                            sender->get_Source(&src);
                            std::string uri = src ? Utf8FromUtf16(src) : "";
                            if (src) ::CoTaskMemFree(src);
                            if (browser_channel_) {
                              browser_channel_->InvokeMethod(
                                  "onModalProgress",
                                  std::make_unique<flutter::EncodableValue>(
                                      flutter::EncodableMap{
                                          {flutter::EncodableValue("progress"),
                                           flutter::EncodableValue(0.65)},
                                          {flutter::EncodableValue("url"),
                                           flutter::EncodableValue(uri)},
                                      }));
                            }
                            return S_OK;
                          })
                          .Get(),
                      nullptr);

                  web_state_->modal_webview->add_DocumentTitleChanged(
                      Callback<ICoreWebView2DocumentTitleChangedEventHandler>(
                          [this](ICoreWebView2* sender, IUnknown*) -> HRESULT {
                            LPWSTR raw_title = nullptr;
                            LPWSTR raw_src = nullptr;
                            sender->get_DocumentTitle(&raw_title);
                            sender->get_Source(&raw_src);
                            std::string title =
                                raw_title ? Utf8FromUtf16(raw_title) : "";
                            std::string uri =
                                raw_src ? Utf8FromUtf16(raw_src) : "";
                            if (raw_title) ::CoTaskMemFree(raw_title);
                            if (raw_src) ::CoTaskMemFree(raw_src);
                            if (browser_channel_) {
                              browser_channel_->InvokeMethod(
                                  "onModalTitleChanged",
                                  std::make_unique<flutter::EncodableValue>(
                                      flutter::EncodableMap{
                                          {flutter::EncodableValue("title"),
                                           flutter::EncodableValue(title)},
                                          {flutter::EncodableValue("url"),
                                           flutter::EncodableValue(uri)},
                                      }));
                            }
                            return S_OK;
                          })
                          .Get(),
                      nullptr);

                  web_state_->modal_webview->add_NewWindowRequested(
                      Callback<ICoreWebView2NewWindowRequestedEventHandler>(
                          [](ICoreWebView2* sender,
                             ICoreWebView2NewWindowRequestedEventArgs* e)
                              -> HRESULT {
                            if (!e) return S_OK;
                            LPWSTR raw_uri = nullptr;
                            e->get_Uri(&raw_uri);
                            std::wstring uri = raw_uri ? raw_uri : L"";
                            if (raw_uri) ::CoTaskMemFree(raw_uri);
                            e->put_Handled(TRUE);
                            if (uri.rfind(L"http://", 0) == 0 ||
                                uri.rfind(L"https://", 0) == 0) {
                              sender->Navigate(uri.c_str());
                            } else if (!uri.empty()) {
                              ::ShellExecuteW(nullptr, L"open", uri.c_str(),
                                              nullptr, nullptr, SW_SHOWNORMAL);
                            }
                            return S_OK;
                          })
                          .Get(),
                      nullptr);

                  web_state_->modal_webview->add_WebMessageReceived(
                      Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                          [this](ICoreWebView2* sender,
                                 ICoreWebView2WebMessageReceivedEventArgs* e)
                              -> HRESULT {
                            if (!e) return S_OK;
                            LPWSTR raw_msg = nullptr;
                            if (FAILED(
                                    e->TryGetWebMessageAsString(&raw_msg)) ||
                                !raw_msg) {
                              return S_OK;
                            }
                            std::string msg = Utf8FromUtf16(raw_msg);
                            ::CoTaskMemFree(raw_msg);

                            const std::string prefix = "LINDOMODAL\t";
                            if (msg.rfind(prefix, 0) != 0) return S_OK;
                            web_state_->modal_probe_in_flight = false;

                            size_t t1 = prefix.size();
                            size_t t2 = msg.find('\t', t1);
                            if (t2 == std::string::npos) return S_OK;
                            uint64_t gen = std::strtoull(
                                msg.substr(t1, t2 - t1).c_str(), nullptr, 10);
                            if (gen != web_state_->modal_generation ||
                                web_state_->modal_completed) {
                              return S_OK;
                            }

                            std::string payload = msg.substr(t2 + 1);
                            flutter::EncodableValue parsed;
                            MiniJsonParser parser(payload);
                            if (!parser.Parse(parsed) ||
                                !std::holds_alternative<flutter::EncodableMap>(
                                    parsed)) {
                              return S_OK;
                            }
                            const auto& dict =
                                std::get<flutter::EncodableMap>(parsed);
                            std::string state = GetMapString(dict, "state", "");
                            if (state != "authenticated" &&
                                state != "verified") {
                              return S_OK;
                            }

                            web_state_->modal_completed = true;
                            std::string final_url =
                                GetMapString(dict, "url", "https://linux.do/");
                            std::string csrf =
                                GetMapString(dict, "csrfToken", "");
                            flutter::EncodableValue user_val;
                            if (const auto* uv = FindMapField(dict, "user")) {
                              user_val = *uv;
                            }

                            ComPtr<ICoreWebView2_2> wv2;
                            ComPtr<ICoreWebView2CookieManager> cm;
                            if (FAILED(sender->QueryInterface(
                                    IID_PPV_ARGS(&wv2))) ||
                                !wv2 || FAILED(wv2->get_CookieManager(&cm)) ||
                                !cm) {
                              HWND hwnd = GetHandle();
                              web_state_->CloseModal(hwnd, nullptr, false);
                              return S_OK;
                            }

                            cm->GetCookies(
                                L"https://linux.do/",
                                Callback<
                                    ICoreWebView2GetCookiesCompletedHandler>(
                                    [this, state, final_url, csrf, user_val](
                                        HRESULT hr,
                                        ICoreWebView2CookieList* list)
                                        -> HRESULT {
                                      std::string cookies;
                                      if (SUCCEEDED(hr) && list) {
                                        UINT cnt = 0;
                                        list->get_Count(&cnt);
                                        for (UINT i = 0; i < cnt; ++i) {
                                          ComPtr<ICoreWebView2Cookie> ck;
                                          if (SUCCEEDED(list->GetValueAtIndex(
                                                  i, &ck)) &&
                                              ck) {
                                            LPWSTR n = nullptr;
                                            LPWSTR v = nullptr;
                                            ck->get_Name(&n);
                                            ck->get_Value(&v);
                                            if (n && n[0] != 0) {
                                              if (!cookies.empty())
                                                cookies += "; ";
                                              cookies += Utf8FromUtf16(n);
                                              cookies.push_back('=');
                                              if (v)
                                                cookies += Utf8FromUtf16(v);
                                            }
                                            if (n) ::CoTaskMemFree(n);
                                            if (v) ::CoTaskMemFree(v);
                                          }
                                        }
                                      }
                                      HWND hwnd = GetHandle();
                                      web_state_->CloseModal(hwnd, nullptr,
                                                             false);
                                      if (!browser_channel_) return S_OK;
                                      if (state == "authenticated") {
                                        browser_channel_->InvokeMethod(
                                            "onModalLoginAuthenticated",
                                            std::make_unique<
                                                flutter::EncodableValue>(
                                                flutter::EncodableMap{
                                                    {flutter::EncodableValue(
                                                         "url"),
                                                     flutter::EncodableValue(
                                                         final_url)},
                                                    {flutter::EncodableValue(
                                                         "cookies"),
                                                     flutter::EncodableValue(
                                                         cookies)},
                                                    {flutter::EncodableValue(
                                                         "csrfToken"),
                                                     flutter::EncodableValue(
                                                         csrf)},
                                                    {flutter::EncodableValue(
                                                         "user"),
                                                     user_val},
                                                }));
                                      } else {
                                        browser_channel_->InvokeMethod(
                                            "onModalSiteVerified",
                                            std::make_unique<
                                                flutter::EncodableValue>(
                                                flutter::EncodableMap{
                                                    {flutter::EncodableValue(
                                                         "url"),
                                                     flutter::EncodableValue(
                                                         final_url)},
                                                    {flutter::EncodableValue(
                                                         "cookies"),
                                                     flutter::EncodableValue(
                                                         cookies)},
                                                    {flutter::EncodableValue(
                                                         "csrfToken"),
                                                     flutter::EncodableValue(
                                                         csrf)},
                                                }));
                                      }
                                      return S_OK;
                                    })
                                    .Get());
                            return S_OK;
                          })
                          .Get(),
                      nullptr);

                  web_state_->modal_webview->add_NavigationCompleted(
                      Callback<ICoreWebView2NavigationCompletedEventHandler>(
                          [this](ICoreWebView2* sender,
                                 ICoreWebView2NavigationCompletedEventArgs* e)
                              -> HRESULT {
                            LPWSTR raw_src = nullptr;
                            sender->get_Source(&raw_src);
                            std::string uri =
                                raw_src ? Utf8FromUtf16(raw_src) : "";
                            if (raw_src) ::CoTaskMemFree(raw_src);

                            BOOL nav_ok = TRUE;
                            if (e) e->get_IsSuccess(&nav_ok);
                            if (nav_ok) {
                              if (browser_channel_) {
                                browser_channel_->InvokeMethod(
                                    "onModalProgress",
                                    std::make_unique<flutter::EncodableValue>(
                                        flutter::EncodableMap{
                                            {flutter::EncodableValue(
                                                 "progress"),
                                             flutter::EncodableValue(1.0)},
                                            {flutter::EncodableValue("url"),
                                             flutter::EncodableValue(uri)},
                                        }));
                              }
                              HWND hwnd = GetHandle();
                              if (hwnd) {
                                ::PostMessageW(hwnd, WM_TIMER,
                                               kTimerModalProbe, 0);
                              }
                            } else {
                              COREWEBVIEW2_WEB_ERROR_STATUS err_st =
                                  COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
                              if (e) e->get_WebErrorStatus(&err_st);
                              if (err_st !=
                                      COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED &&
                                  browser_channel_) {
                                browser_channel_->InvokeMethod(
                                    "onModalLoadError",
                                    std::make_unique<flutter::EncodableValue>(
                                        flutter::EncodableMap{
                                            {flutter::EncodableValue("url"),
                                             flutter::EncodableValue(uri)},
                                            {flutter::EncodableValue("message"),
                                             flutter::EncodableValue(
                                                 "Navigation failed")},
                                        }));
                              }
                            }
                            return S_OK;
                          })
                          .Get(),
                      nullptr);

                  web_state_->SyncModalBounds(parent_hwnd);
                  ::SetTimer(parent_hwnd, kTimerModalProbe, 1000, nullptr);
                  if (!initial_wurl.empty()) {
                    web_state_->modal_webview->Navigate(initial_wurl.c_str());
                  }
                  return S_OK;
                })
                .Get());
      } else {
        BOOL dark_flag = is_dark ? TRUE : FALSE;
        ::DwmSetWindowAttribute(web_state_->modal_hwnd,
                                DWMWA_USE_IMMERSIVE_DARK_MODE, &dark_flag,
                                sizeof(dark_flag));
        web_state_->SyncModalBounds(parent_hwnd);
        ::SetTimer(parent_hwnd, kTimerModalProbe, 1000, nullptr);
        if (web_state_->modal_webview && !url.empty()) {
          std::wstring wurl = Utf16FromUtf8(url);
          web_state_->modal_webview->Navigate(wurl.c_str());
        }
      }
    });
    shared_res->Success();
    return;
  }

  if (method == "updateModalRect") {
    web_state_->modal_left = GetMapDouble(args, "left", web_state_->modal_left);
    web_state_->modal_top = GetMapDouble(args, "top", web_state_->modal_top);
    web_state_->modal_width =
        (std::max)(0.01, GetMapDouble(args, "width", web_state_->modal_width));
    web_state_->modal_height = (std::max)(
        0.01, GetMapDouble(args, "height", web_state_->modal_height));
    web_state_->SyncModalBounds(GetHandle());
    shared_res->Success();
    return;
  }

  if (method == "reloadModal") {
    if (web_state_->modal_webview) {
      web_state_->modal_webview->Reload();
    }
    shared_res->Success();
    return;
  }

  if (method == "goBackModal") {
    if (web_state_->modal_webview) {
      BOOL can_back = FALSE;
      web_state_->modal_webview->get_CanGoBack(&can_back);
      if (can_back) {
        web_state_->modal_webview->GoBack();
      }
    }
    shared_res->Success();
    return;
  }

  if (method == "closeModal") {
    web_state_->CloseModal(GetHandle(), browser_channel_.get(), true);
    shared_res->Success();
    return;
  }

  shared_res->NotImplemented();
}

LRESULT FlutterWindow::MessageHandler(HWND hwnd, UINT const message,
                                      WPARAM const wparam,
                                      LPARAM const lparam) noexcept {
  if (message == kWmUiTask) {
    DrainUiTasks();
    return 0;
  }

  if (message == kWmTrayIcon) {
    UINT evt = LOWORD(lparam);
    if (evt == WM_LBUTTONUP || evt == WM_LBUTTONDBLCLK) {
      RestoreMainWindow();
      return 0;
    }
    if (evt == WM_RBUTTONUP || evt == WM_CONTEXTMENU) {
      ShowTrayContextMenu();
      return 0;
    }
    return 0;
  }

  if (message == WM_CLOSE && !is_quitting_) {
    if (web_state_) {
      web_state_->CloseModal(hwnd, browser_channel_.get(), true);
    }
    ::ShowWindow(hwnd, SW_HIDE);
    return 0;
  }

  if (message == WM_TIMER) {
    if (wparam == kTimerApiInitWatchdog) {
      if (web_state_ && web_state_->api_initializing) {
        web_state_->CompleteApiInit(hwnd, web_state_->api_webview != nullptr);
      }
      return 0;
    }
    if (wparam == kTimerModalProbe) {
      if (web_state_ && web_state_->modal_webview &&
          !web_state_->modal_completed && !web_state_->modal_probe_in_flight) {
        std::string mode_lower = ToLowerAscii(web_state_->modal_mode);
        bool is_login = (mode_lower == "login");
        bool is_verify =
            (mode_lower == "siteverify" || mode_lower == "site_verify" ||
             mode_lower == "verify");
        if (is_login || is_verify) {
          LPWSTR raw_src = nullptr;
          web_state_->modal_webview->get_Source(&raw_src);
          std::string src = raw_src ? Utf8FromUtf16(raw_src) : "";
          if (raw_src) ::CoTaskMemFree(raw_src);
          std::string scheme;
          std::string host;
          if (ParseUrlSchemeHost(src, scheme, host) && host == "linux.do") {
            web_state_->modal_probe_in_flight = true;
            std::wstring gen_w = std::to_wstring(web_state_->modal_generation);
            std::wstring mode_w = is_login ? L"login" : L"siteVerify";
            std::wstring probe_js =
                L"(async function(){ const __gen = '" + gen_w +
                L"'; const probeMode = '" + mode_w + L"'; " + LR"JS(
              const post = (obj) => window.chrome.webview.postMessage('LINDOMODAL\t' + __gen + '\t' + JSON.stringify(obj));
              try {
                const title = document.title || '';
                const isChallenge =
                  title.includes('Just a moment') ||
                  title.includes('请稍候') ||
                  title.includes('Attention Required') ||
                  !!document.querySelector('#challenge-running, #challenge-stage, #cf-wrapper, [name="cf-turnstile-response"], iframe[src*="challenges.cloudflare.com"]');
                if (isChallenge) { post({ state: 'challenge' }); return; }
                const path = window.location.pathname || '';
                const href = window.location.href || '';
                const meta = document.querySelector('meta[name="csrf-token"]');
                let csrf = (meta && meta.content) ? meta.content : '';
                if (probeMode === 'login') {
                  if (path.startsWith('/login') ||
                      path.startsWith('/session/') ||
                      path.startsWith('/auth/') ||
                      path.startsWith('/u/account-created') ||
                      path.startsWith('/user-api-key/')) {
                    post({ state: 'pending' });
                    return;
                  }
                  const resp = await fetch('/session/current.json', {
                    method: 'GET',
                    credentials: 'include',
                    cache: 'no-store',
                    headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
                  });
                  if (!resp.ok) { post({ state: 'unauthenticated' }); return; }
                  const data = await resp.json();
                  const cu = data && data.current_user;
                  if (!cu || !cu.username) { post({ state: 'unauthenticated' }); return; }
                  if (!csrf) {
                    try {
                      const cr = await fetch('/session/csrf.json', {
                        credentials: 'include',
                        cache: 'no-store',
                        headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
                      });
                      if (cr.ok) { const cd = await cr.json(); if (cd && cd.csrf) csrf = cd.csrf; }
                    } catch (_) {}
                  }
                  post({ state: 'authenticated', url: href, csrfToken: csrf, user: cu });
                } else {
                  if (document.readyState === 'loading') { post({ state: 'loading' }); return; }
                  const sr = await fetch('/srv/status', { method: 'GET', credentials: 'include', cache: 'no-store' });
                  const cfMit = sr.headers.get('cf-mitigated') || '';
                  if (!sr.ok || cfMit) { post({ state: 'challenge' }); return; }
                  if (!csrf) {
                    try {
                      const cr = await fetch('/session/csrf.json', {
                        credentials: 'include',
                        cache: 'no-store',
                        headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
                      });
                      if (cr.ok) { const cd = await cr.json(); if (cd && cd.csrf) csrf = cd.csrf; }
                    } catch (_) {}
                  }
                  post({ state: 'verified', url: href, csrfToken: csrf });
                }
              } catch (_) {
                post({ state: 'pending' });
              }
            })();)JS";
            web_state_->modal_webview->ExecuteScript(probe_js.c_str(), nullptr);
          }
        }
      }
      return 0;
    }
  }

  if (flutter_controller_) {
    std::optional<LRESULT> result =
        flutter_controller_->HandleTopLevelWindowProc(hwnd, message, wparam,
                                                      lparam);
    if (result) {
      if (message == WM_MOVE || message == WM_SIZE ||
          message == WM_DPICHANGED || message == WM_SHOWWINDOW) {
        if (web_state_) web_state_->SyncModalBounds(hwnd);
      }
      return *result;
    }
  }

  switch (message) {
    case WM_FONTCHANGE:
      flutter_controller_->engine()->ReloadSystemFonts();
      break;
    case WM_MOVE:
    case WM_MOVING:
    case WM_SHOWWINDOW:
      if (web_state_) web_state_->SyncModalBounds(hwnd);
      break;
    case WM_SIZE: {
      LRESULT res = Win32Window::MessageHandler(hwnd, message, wparam, lparam);
      if (web_state_) web_state_->SyncModalBounds(hwnd);
      return res;
    }
  }

  return Win32Window::MessageHandler(hwnd, message, wparam, lparam);
}
