#pragma once
#include <string>
#include <vector>
#include <array>
#include <unordered_map>
#include <cstdint>
#include <cstddef>

namespace aura {

class ClipTokenizer {
public:
    bool load(const std::string& tokenizerJsonPath);

    [[nodiscard]] std::vector<int32_t> encode(const std::string& text) const;

    /**
     * Split untruncated content into 75-token CLIP chunks. Every returned chunk
     * is exactly 77 ids: BOS, up to 75 content ids, EOS, then EOS padding.
     */
    [[nodiscard]] std::vector<std::vector<int32_t>> encodeChunks(
        const std::string& text,
        size_t minimumChunks = 1) const;

    [[nodiscard]] size_t chunkCount(const std::string& text) const;

    [[nodiscard]] int32_t countTokens(const std::string& text) const;

private:
    std::unordered_map<std::string, int32_t> vocab_;
    std::unordered_map<std::string, int> mergeRank_;
    std::array<std::string, 256> byteEncoder_;
    int32_t bos_ = 49406, eos_ = 49407;

    void buildByteEncoder();
    [[nodiscard]] std::vector<int32_t> encodeContent(const std::string& text) const;
    [[nodiscard]] static std::string normalize(const std::string& text);
    [[nodiscard]] static std::vector<std::string> preTokenize(const std::string& text);
    [[nodiscard]] std::vector<std::string> bpe(std::vector<std::string> symbols) const;
};

}
