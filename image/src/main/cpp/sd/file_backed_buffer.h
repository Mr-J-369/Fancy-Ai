#pragma once

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <unistd.h>
#include <utility>

namespace aura {

// Owns CPU tensor/context storage. Unlinked files remain alive only while mapped;
// these are ordinary CPU pointers, not QNN/DMA registrations or OpenCL buffers.
class FileBackedBuffer {
public:
    FileBackedBuffer() = default;
    FileBackedBuffer(const FileBackedBuffer&) = delete;
    FileBackedBuffer& operator=(const FileBackedBuffer&) = delete;
    FileBackedBuffer(FileBackedBuffer&& other) noexcept
        : data_(std::exchange(other.data_, nullptr)), size_(std::exchange(other.size_, 0)) {}
    FileBackedBuffer& operator=(FileBackedBuffer&& other) noexcept {
        if (this != &other) {
            release();
            data_ = std::exchange(other.data_, nullptr);
            size_ = std::exchange(other.size_, 0);
        }
        return *this;
    }
    ~FileBackedBuffer() { release(); }

    bool resize(size_t bytes, const std::string& directory) {
        if (bytes == size_) return true;
        release();
        if (bytes < threshold) {
            data_ = static_cast<uint8_t*>(std::calloc(bytes, 1));
        } else {
            std::string path = directory + "/.aura-host-XXXXXX";
            const int fd = mkstemp(path.data());
            if (fd < 0) return false;
            if (unlink(path.c_str()) != 0 || fallocate(fd, 0, 0, static_cast<off_t>(bytes)) != 0) {
                const int error = errno;
                close(fd);
                errno = error;
                return false;
            }
            void* mapped = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            const int error = errno;
            close(fd);
            if (mapped == MAP_FAILED) {
                errno = error;
                return false;
            }
            data_ = static_cast<uint8_t*>(mapped);
        }
        if (!data_) return false;
        size_ = bytes;
        return true;
    }

    uint8_t* data() { return data_; }
    const uint8_t* data() const { return data_; }
    size_t size() const { return size_; }

private:
    void release() {
        if (size_ >= threshold) munmap(data_, size_);
        else std::free(data_);
        data_ = nullptr;
        size_ = 0;
    }
    static constexpr size_t threshold = 64 * 1024;
    uint8_t* data_ = nullptr;
    size_t size_ = 0;
};

}
