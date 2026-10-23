// Tiny parsers for the committed test vectors (engine/host/test/vectors). Test-only; no dependencies.
#ifndef SIGNAL_ATLAS_TEST_VECTORS_H
#define SIGNAL_ATLAS_TEST_VECTORS_H

#include <cmath>
#include <cstdlib>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

namespace sa_test {

inline std::string vectorPath(const std::string& name) { return std::string(SA_VECTOR_DIR) + "/" + name; }

inline std::string readFile(const std::string& name) {
    std::ifstream in(vectorPath(name), std::ios::binary);
    std::stringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

inline std::vector<std::string> split(const std::string& line, char sep) {
    std::vector<std::string> out;
    std::string cur;
    for (char c : line) {
        if (c == sep) {
            out.push_back(cur);
            cur.clear();
        } else {
            cur.push_back(c);
        }
    }
    out.push_back(cur);
    return out;
}

// Rows of a header-ed CSV (header skipped). Empty file or missing file gives no rows; tests assert row counts.
inline std::vector<std::vector<std::string>> readCsv(const std::string& name) {
    std::stringstream in(readFile(name));
    std::vector<std::vector<std::string>> rows;
    std::string line;
    bool header = true;
    while (std::getline(in, line)) {
        if (header) {
            header = false;
            continue;
        }
        if (!line.empty()) rows.push_back(split(line, ','));
    }
    return rows;
}

inline double num(const std::string& s) {
    if (s == "nan" || s == "null") return std::numeric_limits<double>::quiet_NaN();
    return std::strtod(s.c_str(), nullptr);
}

// Position just after `"key"` + optional spaces + ':' + optional spaces, or npos. Vectors are single-line JSON, so
// the extractors below never depend on line layout. Keys are matched with their quotes, so a string value equal to
// the key cannot be mistaken for it only when it is followed by ':' (values never are).
inline size_t jsonValueAt(const std::string& json, const std::string& key) {
    const std::string needle = "\"" + key + "\"";
    size_t at = 0;
    while ((at = json.find(needle, at)) != std::string::npos) {
        size_t q = at + needle.size();
        while (q < json.size() && (json[q] == ' ' || json[q] == '\t' || json[q] == '\n' || json[q] == '\r')) ++q;
        if (q < json.size() && json[q] == ':') {
            ++q;
            while (q < json.size() && (json[q] == ' ' || json[q] == '\t' || json[q] == '\n' || json[q] == '\r')) ++q;
            return q;
        }
        at += needle.size();
    }
    return std::string::npos;
}

// The numbers of `"key": [ ... ]` (null -> NaN); empty when the key is missing.
inline std::vector<double> jsonArray(const std::string& json, const std::string& key) {
    std::vector<double> out;
    size_t q = jsonValueAt(json, key);
    if (q == std::string::npos || q >= json.size() || json[q] != '[') return out;
    ++q;
    const size_t end = json.find(']', q);
    if (end == std::string::npos) return out;
    for (const std::string& tok : split(json.substr(q, end - q), ',')) {
        size_t a = tok.find_first_not_of(" \t\r\n");
        out.push_back(a == std::string::npos ? std::numeric_limits<double>::quiet_NaN() : num(tok.substr(a)));
    }
    return out;
}

// A scalar number (or true/false as 1/0); NaN when missing.
inline double jsonNumber(const std::string& json, const std::string& key) {
    const size_t q = jsonValueAt(json, key);
    if (q == std::string::npos) return std::numeric_limits<double>::quiet_NaN();
    if (json.compare(q, 4, "true") == 0) return 1.0;
    if (json.compare(q, 5, "false") == 0) return 0.0;
    return std::strtod(json.c_str() + q, nullptr);
}

// A string value without quotes; empty when missing.
inline std::string jsonString(const std::string& json, const std::string& key) {
    size_t q = jsonValueAt(json, key);
    if (q == std::string::npos || q >= json.size() || json[q] != '"') return "";
    const size_t end = json.find('"', q + 1);
    return end == std::string::npos ? "" : json.substr(q + 1, end - q - 1);
}

}  // namespace sa_test

#endif  // SIGNAL_ATLAS_TEST_VECTORS_H
