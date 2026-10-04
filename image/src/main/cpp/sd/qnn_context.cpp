#include "qnn_backend.h"
#include "file_backed_buffer.h"
#include "zstd.h"

#include <android/log.h>
#include <filesystem>
#include <fstream>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#if FANCY_INTEGRITY_REQUIRED
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "fancyqnn", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "fancyqnn", __VA_ARGS__)
#endif

namespace aura {

bool QnnGraphRunner::loadContext(const std::string& binPath) {

    int fd = open(binPath.c_str(), O_RDONLY);
    if (fd < 0) { LOGE("loadContext: cannot open %s", binPath.c_str()); return false; }
    off_t fsz = lseek(fd, 0, SEEK_END); lseek(fd, 0, SEEK_SET);
    if (fsz <= 0) { LOGE("loadContext: empty/bad file %s", binPath.c_str()); close(fd); return false; }
    auto size = static_cast<size_t>(fsz);
    void* map = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) { LOGE("loadContext: mmap failed for %s (%zu bytes)", binPath.c_str(), size); return false; }
    madvise(map, size, MADV_SEQUENTIAL);
    bool ok = loadFromMemory(static_cast<uint8_t*>(map), size);
    munmap(map, size);
    if (ok) LOGI("loadContext: %s loaded (%u graph[s])", binPath.c_str(), graphsCount_);
    return ok;
}

bool QnnGraphRunner::loadPatchedContext(const std::string& binPath, const std::string& patchPath) {

    int fd = open(binPath.c_str(), O_RDONLY);
    if (fd < 0) { LOGE("loadPatched: cannot open base %s", binPath.c_str()); return false; }
    off_t dsz = lseek(fd, 0, SEEK_END); lseek(fd, 0, SEEK_SET);
    if (dsz <= 0) { LOGE("loadPatched: empty base %s", binPath.c_str()); close(fd); return false; }
    auto dictSize = static_cast<size_t>(dsz);
    void* dict = mmap(nullptr, dictSize, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (dict == MAP_FAILED) { LOGE("loadPatched: mmap base failed"); return false; }

    std::ifstream pf(patchPath, std::ios::binary | std::ios::ate);
    if (!pf.is_open()) { LOGE("loadPatched: cannot open patch %s", patchPath.c_str()); munmap(dict, dictSize); return false; }
    std::streamsize psz = pf.tellg(); pf.seekg(0);
    const std::string backingDir = std::filesystem::path(binPath).parent_path().string();
    FileBackedBuffer patch;
    if (psz <= 0 || !patch.resize(static_cast<size_t>(psz), backingDir) ||
        !pf.read(reinterpret_cast<char*>(patch.data()), psz)) { LOGE("loadPatched: read patch failed"); munmap(dict, dictSize); return false; }

    unsigned long long outSize = ZSTD_getFrameContentSize(patch.data(), patch.size());
    if (outSize == ZSTD_CONTENTSIZE_ERROR || outSize == ZSTD_CONTENTSIZE_UNKNOWN || outSize == 0) {
        LOGE("loadPatched: bad zstd frame (size=%llu)", outSize); munmap(dict, dictSize); return false;
    }
    FileBackedBuffer decoded;
    if (!decoded.resize(static_cast<size_t>(outSize), backingDir)) { LOGE("loadPatched: alloc %llu failed", outSize); munmap(dict, dictSize); return false; }

    ZSTD_DCtx* dctx = ZSTD_createDCtx();
    size_t got = dctx ? ZSTD_decompress_usingDict(dctx, decoded.data(), outSize, patch.data(), patch.size(),
                                                  dict, dictSize)
                      : (size_t)-1;
    if (dctx) ZSTD_freeDCtx(dctx);
    munmap(dict, dictSize);
    if (!dctx || ZSTD_isError(got)) {
        LOGE("loadPatched: decompress failed: %s", dctx ? ZSTD_getErrorName(got) : "no dctx");
        return false;
    }
    bool ok = loadFromMemory(decoded.data(), got);
    if (ok) LOGI("loadPatched: %s + %s -> %zu-byte context (%u graph[s])",
                 binPath.c_str(), patchPath.c_str(), (size_t)got, graphsCount_);
    return ok;
}

}
