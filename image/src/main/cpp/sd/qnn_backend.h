#pragma once
#include <string>
#include <vector>
#include <cstdint>

namespace aura {

struct TensorDesc { std::string name; std::vector<int64_t> dims; int dataType = 0; };

class QnnGraphRunner {
public:

    [[nodiscard]] static bool initBackend(const std::string& libDir, const std::string& skelDir);
    static void releasePerfVote();

    [[nodiscard]] bool loadContext(const std::string& binPath);

    [[nodiscard]] bool loadPatchedContext(const std::string& binPath, const std::string& patchPath);

    [[nodiscard]] bool execute(const std::vector<std::vector<float>>& inputs,
                               std::vector<std::vector<float>>& outputs) const;
    void freeContext();
    [[nodiscard]] bool loaded() const { return ctx_ != nullptr; }
    void logIo() const;
    [[nodiscard]] std::vector<TensorDesc> inputs() const;
    [[nodiscard]] std::vector<TensorDesc> outputs() const;

    QnnGraphRunner() = default;

    QnnGraphRunner(const QnnGraphRunner&) = delete;
    QnnGraphRunner& operator=(const QnnGraphRunner&) = delete;
    ~QnnGraphRunner();

private:
    void* ctx_ = nullptr;
    void* graphsInfo_ = nullptr;
    uint32_t graphsCount_ = 0;
    [[nodiscard]] bool loadFromMemory(const uint8_t* data, size_t size);
};

}
