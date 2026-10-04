#pragma once
#include <string>
#include <vector>
#include <cstdint>

namespace aura {

class MnnClip {
public:

    bool load(const std::string& dir);

    bool loadAs(const std::string& dir, const std::string& mnnFile,
                const std::string& tokenEmbFile, const std::string& posEmbFile,
                int dim, bool wantPooled);
    [[nodiscard]] std::vector<float> encode(const std::vector<int32_t>& ids) const;

    [[nodiscard]] bool encodePooled(const std::vector<int32_t>& ids,
                                    std::vector<float>& hidden, std::vector<float>& pooledAllTokens) const;
    [[nodiscard]] bool ready() const { return interp_ != nullptr; }

    void free();
    ~MnnClip();

private:
    void* interp_ = nullptr;
    void* session_ = nullptr;

    const void* tokenEmbBase_ = nullptr;
    const void* posEmbBase_ = nullptr;
    bool tokenEmbF32_ = false;
    bool posEmbF32_ = true;
    void* tokenEmbMap_ = nullptr; size_t tokenEmbBytes_ = 0;
    void* posEmbMap_ = nullptr;   size_t posEmbBytes_ = 0;
    int dim_ = 768;
    int seq_ = 77;
    bool wantPooled_ = false;
    std::string mnnFile_ = "clip_v2.mnn";
    [[nodiscard]] std::vector<float> buildInputEmb(const std::vector<int32_t>& ids) const;
};

}
