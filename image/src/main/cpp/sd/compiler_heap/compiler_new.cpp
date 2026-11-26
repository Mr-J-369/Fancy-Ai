// C++ allocation entry points used by QNN. Route them through the same heap
// as C allocations instead of depending on the phone's system libc++ binding.
#include <cstdlib>
#include <new>

void* operator new(std::size_t size) {
    if (size == 0) size = 1;
    for (;;) {
        if (void* pointer = std::malloc(size)) return pointer;
        const auto handler = std::get_new_handler();
        if (!handler) throw std::bad_alloc();
        handler();
    }
}

void* operator new[](std::size_t) __attribute__((alias("_Znwm")));

void* operator new(std::size_t size, const std::nothrow_t&) noexcept {
    try { return ::operator new(size); }
    catch (...) { return nullptr; }
}

void* operator new[](std::size_t, const std::nothrow_t&) noexcept
    __attribute__((alias("_ZnwmRKSt9nothrow_t")));

void* operator new(std::size_t size, std::align_val_t alignment) {
    if (size == 0) size = 1;
    for (;;) {
        void* pointer = nullptr;
        if (posix_memalign(&pointer, static_cast<std::size_t>(alignment) < sizeof(void*)
                ? sizeof(void*) : static_cast<std::size_t>(alignment), size) == 0)
            return pointer;
        const auto handler = std::get_new_handler();
        if (!handler) throw std::bad_alloc();
        handler();
    }
}

void* operator new[](std::size_t, std::align_val_t)
    __attribute__((alias("_ZnwmSt11align_val_t")));

void* operator new(std::size_t size, std::align_val_t alignment,
                   const std::nothrow_t&) noexcept {
    try { return ::operator new(size, alignment); }
    catch (...) { return nullptr; }
}

void* operator new[](std::size_t, std::align_val_t, const std::nothrow_t&) noexcept
    __attribute__((alias("_ZnwmSt11align_val_tRKSt9nothrow_t")));

void operator delete(void* pointer) noexcept { std::free(pointer); }
void operator delete[](void*) noexcept __attribute__((alias("_ZdlPv")));
void operator delete(void*, std::size_t) noexcept __attribute__((alias("_ZdlPv")));
void operator delete[](void*, std::size_t) noexcept __attribute__((alias("_ZdlPv")));
void operator delete(void*, const std::nothrow_t&) noexcept __attribute__((alias("_ZdlPv")));
void operator delete[](void*, const std::nothrow_t&) noexcept __attribute__((alias("_ZdlPv")));
void operator delete(void*, std::align_val_t) noexcept __attribute__((alias("_ZdlPv")));
void operator delete[](void*, std::align_val_t) noexcept __attribute__((alias("_ZdlPv")));
void operator delete(void*, std::size_t, std::align_val_t) noexcept __attribute__((alias("_ZdlPv")));
void operator delete[](void*, std::size_t, std::align_val_t) noexcept __attribute__((alias("_ZdlPv")));
void operator delete(void*, std::align_val_t, const std::nothrow_t&) noexcept __attribute__((alias("_ZdlPv")));
void operator delete[](void*, std::align_val_t, const std::nothrow_t&) noexcept __attribute__((alias("_ZdlPv")));
