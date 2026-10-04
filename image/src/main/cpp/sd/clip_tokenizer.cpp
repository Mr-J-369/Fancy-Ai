#include "clip_tokenizer.h"
#include <nlohmann/json.hpp>
#include <fstream>
#include <limits>
#include <algorithm>

namespace aura {

using json = nlohmann::json;

static void encodeUtf8(uint32_t cp, std::string& out) {
    if (cp < 0x80) { out.push_back(static_cast<char>(cp)); }
    else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

void ClipTokenizer::buildByteEncoder() {
    bool present[256] = {false};
    std::vector<int> bs, cs;
    auto addRange = [&](int lo, int hi) { for (int c = lo; c <= hi; ++c) { bs.push_back(c); cs.push_back(c); present[c] = true; } };
    addRange('!', '~');
    addRange(0xA1, 0xAC);
    addRange(0xAE, 0xFF);
    int n = 0;
    for (int b = 0; b < 256; ++b) {
        if (!present[b]) { bs.push_back(b); cs.push_back(256 + n); ++n; }
    }
    for (size_t i = 0; i < bs.size(); ++i) {
        std::string u; encodeUtf8(static_cast<uint32_t>(cs[i]), u);
        byteEncoder_[static_cast<size_t>(bs[i])] = u;
    }
}

bool ClipTokenizer::load(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    json j;
    try { f >> j; } catch (...) { return false; }
    const auto& model = j["model"];
    if (!model.contains("vocab")) return false;
    for (auto it = model["vocab"].begin(); it != model["vocab"].end(); ++it)
        vocab_[it.key()] = it.value().get<int32_t>();

    int rank = 0;
    for (const auto& m : model["merges"]) {
        std::string key;
        if (m.is_array() && m.size() == 2) key = m[0].get<std::string>() + " " + m[1].get<std::string>();
        else if (m.is_string()) key = m.get<std::string>();
        else continue;
        mergeRank_[key] = rank++;
    }
    if (auto it = vocab_.find("<|startoftext|>"); it != vocab_.end()) bos_ = it->second;
    if (auto it = vocab_.find("<|endoftext|>"); it != vocab_.end()) eos_ = it->second;
    buildByteEncoder();
    return !vocab_.empty();
}

std::string ClipTokenizer::normalize(const std::string& text) {

    std::string out; out.reserve(text.size());
    bool prevSpace = true;
    for (unsigned char c : text) {
        bool ws = (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v');
        if (ws) { if (!prevSpace) { out.push_back(' '); prevSpace = true; } }
        else { out.push_back((c >= 'A' && c <= 'Z') ? static_cast<char>(c + 32) : static_cast<char>(c)); prevSpace = false; }
    }
    while (!out.empty() && out.back() == ' ') out.pop_back();
    return out;
}

static inline bool isAsciiLetter(unsigned char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
static inline bool isDigit(unsigned char c) { return c >= '0' && c <= '9'; }
static inline bool isLetterByte(unsigned char c) { return isAsciiLetter(c) || c >= 0x80; }

std::vector<std::string> ClipTokenizer::preTokenize(const std::string& text) {

    std::vector<std::string> pieces;
    size_t i = 0, n = text.size();
    while (i < n) {
        auto c = static_cast<unsigned char>(text[i]);
        if (c == ' ') { ++i; continue; }
        if (c == '\'' && i + 1 < n) {
            std::string two = text.substr(i, 2);
            std::string three = (i + 2 < n) ? text.substr(i, 3) : "";
            if (three == "'re" || three == "'ve" || three == "'ll") { pieces.push_back(three); i += 3; continue; }
            char d = text[i + 1];
            if (d == 's' || d == 't' || d == 'm' || d == 'd') { pieces.push_back(two); i += 2; continue; }

        }
        if (isLetterByte(c)) {
            size_t j = i; while (j < n && isLetterByte(static_cast<unsigned char>(text[j])) && text[j] != '\'') ++j;
            pieces.push_back(text.substr(i, j - i)); i = j;
        } else if (isDigit(c)) {
            pieces.push_back(text.substr(i, 1)); ++i;
        } else {
            size_t j = i;
            while (j < n) {
                auto d = static_cast<unsigned char>(text[j]);
                if (d == ' ' || isLetterByte(d) || isDigit(d)) break;
                if (d == '\'') {
                    std::string three = (j + 2 < n) ? text.substr(j, 3) : "";
                    char e = (j + 1 < n) ? text[j + 1] : 0;
                    if (three == "'re" || three == "'ve" || three == "'ll" ||
                        e == 's' || e == 't' || e == 'm' || e == 'd') break;
                }
                ++j;
            }
            if (j > i) { pieces.push_back(text.substr(i, j - i)); i = j; } else ++i;
        }
    }
    return pieces;
}

std::vector<std::string> ClipTokenizer::bpe(std::vector<std::string> symbols) const {
    if (symbols.size() < 2) return symbols;
    while (true) {
        int bestRank = std::numeric_limits<int>::max();
        int bestIdx = -1;
        for (size_t i = 0; i + 1 < symbols.size(); ++i) {
            auto it = mergeRank_.find(symbols[i] + " " + symbols[i + 1]);
            if (it != mergeRank_.end() && it->second < bestRank) { bestRank = it->second; bestIdx = (int)i; }
        }
        if (bestIdx < 0) break;
        symbols[bestIdx] += symbols[bestIdx + 1];
        symbols.erase(symbols.begin() + bestIdx + 1);
        if (symbols.size() < 2) break;
    }
    return symbols;
}

std::vector<int32_t> ClipTokenizer::encodeContent(const std::string& text) const {
    std::vector<int32_t> ids;
    for (const auto& piece : preTokenize(normalize(text))) {
        std::vector<std::string> symbols;
        symbols.reserve(piece.size());
        for (unsigned char b : piece) symbols.push_back(byteEncoder_[b]);
        if (symbols.empty()) continue;
        symbols.back() += "</w>";
        for (const auto& sym : bpe(std::move(symbols))) {
            auto it = vocab_.find(sym);
            if (it != vocab_.end()) ids.push_back(it->second);
        }
    }
    return ids;
}

std::vector<int32_t> ClipTokenizer::encode(const std::string& text) const {
    return encodeChunks(text).front();
}

size_t ClipTokenizer::chunkCount(const std::string& text) const {
    return encodeChunks(text).size();
}

std::vector<std::vector<int32_t>> ClipTokenizer::encodeChunks(
    const std::string& text,
    size_t minimumChunks) const {
    std::vector<std::vector<int32_t>> result;
    auto isWord = [](unsigned char c) { return isLetterByte(c) || isDigit(c) || c == '_'; };
    auto add = [&](const std::string& part) {
        const std::vector<int32_t> ids = encodeContent(part);
        const size_t chunks = std::max<size_t>(1, (ids.size() + 74) / 75);
        for (size_t chunkIndex = 0; chunkIndex < chunks; ++chunkIndex) {
            std::vector<int32_t> chunk(77, eos_);
            chunk[0] = bos_;
            const size_t begin = chunkIndex * 75;
            const size_t count = begin < ids.size() ? std::min<size_t>(75, ids.size() - begin) : 0;
            if (count > 0) {
                std::copy_n(ids.begin() + static_cast<std::ptrdiff_t>(begin), count, chunk.begin() + 1);
            }
            chunk[1 + count] = eos_;
            result.push_back(std::move(chunk));
        }
    };
    size_t start = 0;
    for (size_t pos = 0; pos + 5 <= text.size();) {
        const bool match = text.compare(pos, 5, "BREAK") == 0 &&
            (pos == 0 || !isWord(static_cast<unsigned char>(text[pos - 1]))) &&
            (pos + 5 == text.size() || !isWord(static_cast<unsigned char>(text[pos + 5])));
        if (match) {
            add(text.substr(start, pos - start));
            start = pos + 5;
            pos = start;
        } else {
            ++pos;
        }
    }
    add(text.substr(start));
    while (result.size() < minimumChunks) {
        std::vector<int32_t> chunk(77, eos_);
        chunk[0] = bos_;
        chunk[1] = eos_;
        result.push_back(std::move(chunk));
    }
    return result;
}

int32_t ClipTokenizer::countTokens(const std::string& text) const {

    return (int32_t)encodeContent(text).size() + 2;
}

}
