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

// The numbers of `"key":[ ... ]` in a tile JSON (null -> NaN).
inline std::vector<double> jsonArray(const std::string& json, const std::string& key) {
    std::vector<double> out;
    const std::string needle = "\"" + key + "\":[";
    const size_t at = json.find(needle);
    if (at == std::string::npos) return out;
    const size_t start = at + needle.size();
    const size_t end = json.find(']', start);
    for (const std::string& tok : split(json.substr(start, end - start), ',')) out.push_back(num(tok));
    return out;
}

}  // namespace sa_test

#endif  // SIGNAL_ATLAS_TEST_VECTORS_H
