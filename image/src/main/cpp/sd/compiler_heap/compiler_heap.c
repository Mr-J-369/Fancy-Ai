// Storage-backed allocations for the SDXL context-generator subprocess.
// Small objects use compact slabs too: leaving them with Scudo can exhaust its
// size classes and spill into a separate mapping per tiny compiler graph node.
// Bootstrap allocations during dlsym use tracked anonymous mappings.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <malloc.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <signal.h>
#include <sys/syscall.h>
#include <sys/statvfs.h>
#include <ucontext.h>
#include <time.h>
#include <unistd.h>

#define MIB (1024UL * 1024UL)
#define DISK_THRESHOLD (8UL * 1024UL)
#define POOL_MAX MIB
#define SLAB_BYTES (8UL * MIB)
#define SMALL_HASH_BUCKETS 4096

static const size_t small_sizes[] = {
    16, 32, 48, 64, 80, 96, 112, 128,
    160, 192, 224, 256, 320, 384, 448, 512,
    640, 768, 896, 1024, 1280, 1536, 1792, 2048,
    2560, 3072, 3584, 4096, 5120, 6144, 7168, 8192,
};
#define SMALL_BINS (sizeof(small_sizes) / sizeof(small_sizes[0]))

typedef struct SmallSlab {
    struct SmallSlab *hash_next;
    struct SmallSlab *next;
    struct SmallSlab *previous;
    void *reservation;
    void *base;
    void *recycled;
    size_t metadata_size;
    size_t capacity;
    size_t used;
    size_t live;
    size_t bin;
    // Zero means free; requested size + 1 also represents a live malloc(0).
    uint16_t sizes[];
} SmallSlab;

typedef struct Slab Slab;

// Bionic and glibc declare different qualifiers for this otherwise identical
// ABI; host regression tests compile the same allocator source as the APK.
#ifdef __BIONIC__
typedef const void *UsablePointer;
#else
typedef void *UsablePointer;
#endif

typedef struct Allocation {
    struct Allocation *next;
    void *pointer;
    void *base;
    size_t mapping_size;
    size_t backing_size;
    size_t size;
    int disk;
    Slab *slab;
} Allocation;

struct Slab {
    Slab *next;
    Allocation *available;
    void *base;
    size_t metadata_size;
    size_t free_count;
    size_t capacity;
    int bin;
    Allocation entries[];
};

static void (*libc_free)(void *);
static size_t (*libc_usable_size)(UsablePointer);
static pthread_once_t initialization = PTHREAD_ONCE_INIT;
static pthread_mutex_t allocations_lock = PTHREAD_MUTEX_INITIALIZER;
static _Thread_local int resolving;
static _Atomic int ready;
static _Atomic unsigned long sequence;
static Allocation *allocations[4096];
// 8 KiB through 1 MiB blocks share 8 MiB mappings and metadata. A separate
// file and metadata mmap per allocation exhausted the tester's 65,530 VMAs.
static Slab *slabs[8];
static SmallSlab *small_available[SMALL_BINS];
// One empty slab per class avoids repeated fallocate/mmap for short-lived
// strings and containers. Other empty slabs are released immediately.
static SmallSlab *small_empty[SMALL_BINS];
static SmallSlab *small_index[SMALL_HASH_BUCKETS];
static size_t small_count;
static size_t small_mappings;
static Allocation *deferred_frees;
static int directory_fd = -1;
static size_t page_size;
static size_t disk_bytes;
static size_t peak_disk_bytes;
static size_t disk_count;
static size_t disk_mappings;
static time_t last_report;


static struct sigaction previous_signals[NSIG];

// The crash path uses stack buffers and async-signal-safe syscalls only: no
// malloc, stdio formatting, unwinder, or allocator mutex after a native fault.
static void crash_value(const char *label, uintptr_t value) {
    char line[128];
    size_t n = 0;
    while (*label && n < 100) line[n++] = *label++;
    line[n++] = '='; line[n++] = '0'; line[n++] = 'x';
    for (int shift = (int)(sizeof(value) * 8) - 4; shift >= 0; shift -= 4)
        line[n++] = "0123456789abcdef"[(value >> shift) & 15];
    line[n++] = '\n';
    write(STDERR_FILENO, line, n);
}

// Capture mappings at the fault, rather than relying on the app's five-second
// sample. A fast allocation burst can otherwise disappear before the next poll.
static void crash_mappings(void) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    static const char scudo[] = "scudo:secondary";
    static const char backing[] = ".compiler-heap-";
    size_t scudo_match = 0, backing_match = 0;
    uintptr_t maps = 0, scudo_maps = 0, backing_maps = 0;
    int has_scudo = 0, has_backing = 0;
    char buffer[4096];
    ssize_t bytes;
    while ((bytes = read(fd, buffer, sizeof(buffer))) > 0) {
        for (ssize_t i = 0; i < bytes; ++i) {
            char ch = buffer[i];
            scudo_match = ch == scudo[scudo_match] ? scudo_match + 1 : (ch == scudo[0] ? 1 : 0);
            backing_match = ch == backing[backing_match] ? backing_match + 1 : (ch == backing[0] ? 1 : 0);
            if (scudo_match == sizeof(scudo) - 1) { has_scudo = 1; scudo_match = 0; }
            if (backing_match == sizeof(backing) - 1) { has_backing = 1; backing_match = 0; }
            if (ch == '\n') {
                maps++;
                scudo_maps += has_scudo;
                backing_maps += has_backing;
                has_scudo = has_backing = 0;
                scudo_match = backing_match = 0;
            }
        }
    }
    close(fd);
    crash_value("memory_mappings", maps);
    crash_value("scudo_secondary", scudo_maps);
    crash_value("storage_backed", backing_maps);
}

static void compiler_crash(int signal, siginfo_t *info, void *context) {
    static const char title[] = "\n[compiler crash] native signal; register values below are hexadecimal\n";
    write(STDERR_FILENO, title, sizeof(title) - 1);
    crash_value("signal", (uintptr_t)signal);
    crash_value("si_code", (uintptr_t)(intptr_t)info->si_code);
    crash_value("si_errno", (uintptr_t)info->si_errno);
    crash_value("fault_address", (uintptr_t)info->si_addr);
    crash_value("pid", (uintptr_t)getpid());
    crash_value("tid", (uintptr_t)syscall(SYS_gettid));
#if defined(__aarch64__)
    const ucontext_t *registers = context;
    crash_value("pc", registers->uc_mcontext.pc);
    crash_value("lr", registers->uc_mcontext.regs[30]);
    crash_value("sp", registers->uc_mcontext.sp);
    crash_value("fp", registers->uc_mcontext.regs[29]);
#else
    (void)context;
#endif
    static const char status_path[] = "/proc/self/status";
    write(STDERR_FILENO, status_path, sizeof(status_path) - 1);
    write(STDERR_FILENO, "\n", 1);
    int fd = open(status_path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        char buffer[4096];
        ssize_t bytes;
        while ((bytes = read(fd, buffer, sizeof(buffer))) > 0) {
            ssize_t sent = 0;
            while (sent < bytes) {
                ssize_t count = write(STDERR_FILENO, buffer + sent, (size_t)(bytes - sent));
                if (count <= 0) break;
                sent += count;
            }
        }
        close(fd);
    }
    crash_mappings();
    // Restore Android's original disposition and re-deliver to this thread so
    // debuggerd/tombstones and the actual signal exit status remain intact.
    sigaction(signal, &previous_signals[signal], NULL);
    syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), signal);
}

__attribute__((constructor)) static void install_crash_capture(void) {
    const int signals[] = {SIGABRT, SIGBUS, SIGSEGV, SIGILL, SIGFPE};
    struct sigaction action = {0};
    action.sa_sigaction = compiler_crash;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); ++i)
        sigaction(signals[i], &action, &previous_signals[signals[i]]);
}

// All allocation APIs use this index. File mappings are page-aligned, so mix
// the address before selecting a bucket instead of using its zero low bits.
static Allocation **allocation_bucket(const void *pointer) {
    uintptr_t key = (uintptr_t)pointer >> 12;
    key ^= key >> 33;
    key *= UINT64_C(0xff51afd7ed558ccd);
    key ^= key >> 33;
    return &allocations[key & 4095];
}

static void initialize(void) {
    resolving = 1;
    page_size = (size_t)sysconf(_SC_PAGESIZE);
    // dlsym can allocate while resolving these symbols. Those allocations use
    // mapped_allocate below and remain tracked after initialization finishes.
    libc_free = (void (*)(void *))dlsym(RTLD_NEXT, "free");
    libc_usable_size = (size_t (*)(UsablePointer))dlsym(RTLD_NEXT, "malloc_usable_size");
    if (!libc_free || !libc_usable_size) {
        static const char message[] = "[compiler heap] cannot resolve libc allocator\n";
        write(STDERR_FILENO, message, sizeof(message) - 1);
        _exit(127);
    }
    const char *directory = getenv("QNN_COMPILER_HEAP_DIR");
    directory_fd = open(directory ? directory : "", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (directory_fd < 0) {
        static const char message[] = "[compiler heap] cannot open backing directory\n";
        write(STDERR_FILENO, message, sizeof(message) - 1);
        _exit(127);
    }
    // Complete frees made by the linker before libc_free was resolved.
    pthread_mutex_lock(&allocations_lock);
    Allocation *pending = deferred_frees;
    deferred_frees = NULL;
    pthread_mutex_unlock(&allocations_lock);
    while (pending) {
        Allocation *next = pending->next;
        libc_free(pending->pointer);
        munmap(pending, page_size);
        pending = next;
    }
    atomic_store_explicit(&ready, 1, memory_order_release);
    resolving = 0;
    static const char message[] =
        "[compiler heap] active: C/C++ allocation hooks, small-object pooling from 16 bytes, shared 8 MiB slabs up to 1 MiB (small-pool-test1)\n";
    write(STDERR_FILENO, message, sizeof(message) - 1);
}

static char *append_decimal(char *output, unsigned long value) {
    char digits[32];
    size_t count = 0;
    do {
        digits[count++] = (char)('0' + value % 10);
        value /= 10;
    } while (value);
    while (count) *output++ = digits[--count];
    return output;
}

// No formatting/allocator calls: small-slab growth holds allocations_lock.
static int backing_file(size_t length, const char **operation) {
    char name[80] = ".compiler-heap-";
    char *end = append_decimal(name + sizeof(".compiler-heap-") - 1, (unsigned long)getpid());
    *end++ = '-';
    end = append_decimal(end, atomic_fetch_add_explicit(&sequence, 1, memory_order_relaxed));
    *end = '\0';
    *operation = "openat backing file";
    int fd = openat(directory_fd, name, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    *operation = "unlinkat backing file";
    int result = unlinkat(directory_fd, name, 0);
    if (result == 0) {
        *operation = "fallocate backing file";
        result = fallocate(fd, 0, 0, (off_t)length);
    }
    if (result != 0) {
        int error = errno;
        close(fd);
        errno = error;
        return -1;
    }
    return fd;
}

static size_t small_hash(uintptr_t base) {
    uintptr_t key = base / SLAB_BYTES;
    key ^= key >> 33;
    key *= UINT64_C(0xff51afd7ed558ccd);
    key ^= key >> 33;
    return key & (SMALL_HASH_BUCKETS - 1);
}

// Look up ownership before touching any metadata; libc-owned pointers can be
// passed to free/realloc and must never be interpreted as one of our headers.
static SmallSlab *small_owner(const void *pointer) {
    uintptr_t base = (uintptr_t)pointer & ~(uintptr_t)(SLAB_BYTES - 1);
    SmallSlab *slab = small_index[small_hash(base)];
    while (slab && (uintptr_t)slab->base != base) slab = slab->hash_next;
    return slab;
}

static size_t small_slot(const SmallSlab *slab, const void *pointer) {
    size_t offset = (uintptr_t)pointer - (uintptr_t)slab->base;
    size_t stride = small_sizes[slab->bin];
    size_t index = offset / stride;
    if (offset % stride || index >= slab->used || !slab->sizes[index]) {
        static const char message[] = "[compiler heap] invalid or freed small pointer\n";
        write(STDERR_FILENO, message, sizeof(message) - 1);
        abort();
    }
    return index;
}

static void small_link(SmallSlab *slab) {
    slab->previous = NULL;
    slab->next = small_available[slab->bin];
    if (slab->next) slab->next->previous = slab;
    small_available[slab->bin] = slab;
}

static void small_unlink(SmallSlab *slab) {
    if (slab->previous) slab->previous->next = slab->next;
    else small_available[slab->bin] = slab->next;
    if (slab->next) slab->next->previous = slab->previous;
    slab->previous = slab->next = NULL;
}

// Called under allocations_lock. Only syscalls and scalar operations are used
// during growth, so no allocation can re-enter the locked allocator.
static SmallSlab *small_create(size_t bin, const char **operation) {
    size_t capacity = SLAB_BYTES / small_sizes[bin];
    size_t metadata_size = (sizeof(SmallSlab) + capacity * sizeof(uint16_t) + page_size - 1)
        & ~(page_size - 1);
    *operation = "small metadata mmap";
    SmallSlab *slab = mmap(NULL, metadata_size, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (slab == MAP_FAILED) return NULL;
    void *reservation = MAP_FAILED;
    int fd = backing_file(SLAB_BYTES, operation);
    if (fd < 0) goto failed;
    *operation = "small address reservation";
    reservation = mmap(NULL, 2 * SLAB_BYTES, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (reservation == MAP_FAILED) goto failed;
    uintptr_t aligned = ((uintptr_t)reservation + SLAB_BYTES - 1) & ~(uintptr_t)(SLAB_BYTES - 1);
    *operation = "small backing mmap";
    void *base = mmap((void *)aligned, SLAB_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd, 0);
    if (base == MAP_FAILED) goto failed;
    close(fd);
    // Keep the unused reservation PROT_NONE. Releasing the whole reservation
    // when empty avoids splitting mappings as individual objects are freed.
    slab->reservation = reservation;
    slab->base = base;
    slab->metadata_size = metadata_size;
    slab->capacity = capacity;
    slab->bin = bin;
    size_t hash = small_hash(aligned);
    slab->hash_next = small_index[hash];
    small_index[hash] = slab;
    small_link(slab);
    disk_mappings++;
    small_mappings++;
    return slab;

failed:;
    int error = errno;
    if (reservation != MAP_FAILED) munmap(reservation, 2 * SLAB_BYTES);
    if (fd >= 0) close(fd);
    munmap(slab, metadata_size);
    errno = error;
    return NULL;
}

static void *small_allocate(size_t size, size_t alignment) {
    size_t bytes = size ? size : 1;
    size_t bin = 0;
    while (small_sizes[bin] < bytes || small_sizes[bin] % alignment) ++bin;
    size_t stride = small_sizes[bin];
    char report[384];
    int report_length = 0;
    const char *operation = "small slab";
    pthread_mutex_lock(&allocations_lock);
    SmallSlab *slab = small_available[bin];
    if (!slab) slab = small_create(bin, &operation);
    if (!slab) {
        int error = errno;
        pthread_mutex_unlock(&allocations_lock);
        int count = snprintf(report, sizeof(report),
            "[compiler heap] small allocation failed: operation=%s bytes=%zu alignment=%zu errno=%d\n",
            operation, size, alignment, error);
        write(STDERR_FILENO, report, (size_t)count);
        errno = ENOMEM;
        return NULL;
    }
    if (small_empty[bin] == slab) small_empty[bin] = NULL;
    void *pointer;
    if (slab->recycled) {
        pointer = slab->recycled;
        slab->recycled = *(void **)pointer;
    } else {
        // Do not populate free links in untouched slots: that would fault in
        // every file page just to allocate the first 40-byte graph node.
        pointer = (char *)slab->base + slab->used++ * stride;
    }
    size_t index = ((uintptr_t)pointer - (uintptr_t)slab->base) / stride;
    slab->sizes[index] = (uint16_t)(size + 1);
    if (++slab->live == slab->capacity) small_unlink(slab);
    disk_bytes += stride;
    disk_count++;
    small_count++;
    if (disk_bytes > peak_disk_bytes) peak_disk_bytes = disk_bytes;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (now.tv_sec - last_report >= 5) {
        last_report = now.tv_sec;
        // Format after unlocking: snprintf is not an allocator primitive.
        size_t live = disk_bytes, peak = peak_disk_bytes, count = small_count, maps = small_mappings;
        pthread_mutex_unlock(&allocations_lock);
        report_length = snprintf(report, sizeof(report),
            "[compiler heap] small allocation %zu bytes; storage-backed %zu MiB live, %zu MiB peak; %zu small allocations in %zu small slabs (capacity, not RSS)\n",
            size, live / MIB, peak / MIB, count, maps);
    } else {
        pthread_mutex_unlock(&allocations_lock);
    }
    if (report_length > 0) write(STDERR_FILENO, report, (size_t)report_length);
    return pointer;
}

// Owns the storage and lifetime metadata shared by malloc and aligned allocation.
static void *mapped_allocate(size_t size, size_t alignment, int disk) {
    size_t bytes = size ? size : 1;
    size_t extra = alignment > page_size ? alignment : 0;
    if (bytes > PTRDIFF_MAX || bytes > SIZE_MAX - (page_size - 1) - extra) {
        errno = ENOMEM;
        return NULL;
    }
    size_t length = (bytes + page_size - 1) & ~(page_size - 1);
    int pool_bin = -1;
    if (disk && alignment <= page_size && bytes <= POOL_MAX) {
        pool_bin = 0;
        length = DISK_THRESHOLD;
        while (length < bytes || length < alignment) {
            length *= 2;
            pool_bin++;
        }
    }
    size_t backing_length = pool_bin >= 0 ? SLAB_BYTES : length;
    size_t reservation = backing_length + extra;
    size_t metadata_size = pool_bin >= 0
        ? (sizeof(Slab) + (SLAB_BYTES / length) * sizeof(Allocation) + page_size - 1) & ~(page_size - 1)
        : page_size;
    void *metadata = MAP_FAILED;
    Slab *slab = NULL;
    Allocation *entry = NULL;
    int fd = -1;
    const char *operation = "metadata mmap";
    void *base = MAP_FAILED;
    void *pointer = MAP_FAILED;
    if (pool_bin >= 0) {
        pthread_mutex_lock(&allocations_lock);
        slab = slabs[pool_bin];
        while (slab && !slab->available) slab = slab->next;
        if (slab) {
            entry = slab->available;
            slab->available = entry->next;
            slab->free_count--;
        }
        pthread_mutex_unlock(&allocations_lock);
        if (entry) {
            base = entry->base;
            pointer = entry->pointer;
            goto register_allocation;
        }
    }
    metadata = mmap(NULL, metadata_size, PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (metadata == MAP_FAILED) goto failed;
    if (disk) {
        fd = backing_file(backing_length, &operation);
        if (fd < 0) goto failed;
    }
    operation = "mmap backing file";
    if (extra) {
        base = mmap(NULL, reservation, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (base == MAP_FAILED) goto failed;
        pointer = (void *)(((uintptr_t)base + alignment - 1) & ~(uintptr_t)(alignment - 1));
        // MAP_FIXED replaces only pages in the reservation owned above.
        pointer = mmap(pointer, length, PROT_READ | PROT_WRITE,
                       MAP_FIXED | (disk ? MAP_SHARED : MAP_PRIVATE | MAP_ANONYMOUS), fd, 0);
    } else {
        base = mmap(NULL, backing_length, PROT_READ | PROT_WRITE,
                    disk ? MAP_SHARED : MAP_PRIVATE | MAP_ANONYMOUS, fd, 0);
        pointer = base;
    }
    if (pointer == MAP_FAILED) goto failed;
    if (fd >= 0) close(fd);
    if (pool_bin >= 0) {
        slab = metadata;
        slab->base = base;
        slab->metadata_size = metadata_size;
        slab->capacity = SLAB_BYTES / length;
        slab->free_count = slab->capacity - 1;
        slab->bin = pool_bin;
        for (size_t i = 0; i < slab->capacity; ++i) {
            Allocation *slot = &slab->entries[i];
            slot->pointer = (char *)base + i * length;
            slot->base = base;
            slot->slab = slab;
            if (i > 0) {
                slot->next = slab->available;
                slab->available = slot;
            }
        }
        entry = &slab->entries[0];
        pthread_mutex_lock(&allocations_lock);
        slab->next = slabs[pool_bin];
        slabs[pool_bin] = slab;
        disk_mappings++;
        pthread_mutex_unlock(&allocations_lock);
    } else {
        entry = metadata;
        if (disk) {
            pthread_mutex_lock(&allocations_lock);
            disk_mappings++;
            pthread_mutex_unlock(&allocations_lock);
        }
    }

register_allocation:
    entry->pointer = pointer;
    entry->base = base;
    entry->mapping_size = reservation;
    entry->backing_size = length;
    entry->size = size;
    entry->disk = disk;
    char report[384];
    int report_length = 0;
    size_t report_live = 0, report_peak = 0, report_count = 0, report_maps = 0;
    int should_report = 0;
    pthread_mutex_lock(&allocations_lock);
    Allocation **bucket = allocation_bucket(pointer);
    entry->next = *bucket;
    *bucket = entry;
    if (disk) {
        disk_bytes += length;
        disk_count++;
        if (disk_bytes > peak_disk_bytes) peak_disk_bytes = disk_bytes;
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (size >= 64 * MIB || now.tv_sec - last_report >= 5) {
            last_report = now.tv_sec;
            should_report = 1;
            report_live = disk_bytes;
            report_peak = peak_disk_bytes;
            report_count = disk_count;
            report_maps = disk_mappings;
        }
    }
    pthread_mutex_unlock(&allocations_lock);
    if (should_report)
        report_length = snprintf(report, sizeof(report),
            "[compiler heap] allocation %zu KiB; storage-backed %zu MiB live, %zu MiB peak, %zu allocations in %zu backing mappings (capacity, not RSS)\n",
            size / 1024, report_live / MIB, report_peak / MIB, report_count, report_maps);
    if (report_length > 0) write(STDERR_FILENO, report, (size_t)report_length);
    return pointer;

failed:;
    int error = errno;
    if (base != MAP_FAILED) munmap(base, reservation);
    if (fd >= 0) close(fd);
    if (metadata != MAP_FAILED) munmap(metadata, metadata_size);
    if (disk) {
        struct statvfs storage;
        unsigned long long available = 0;
        int storage_status = fstatvfs(directory_fd, &storage);
        if (storage_status == 0) available = (unsigned long long)storage.f_bavail * storage.f_frsize;
        char report[320];
        int count = snprintf(report, sizeof(report),
            "[compiler heap] backing allocation failed: operation=%s bytes=%zu alignment=%zu page_size=%zu errno=%d (%s) storage_status=%d storage_available=%llu\n",
            operation, size, alignment, page_size, error, strerror(error), storage_status, available);
        write(STDERR_FILENO, report, (size_t)count);
    }
    errno = ENOMEM;
    return NULL;
}

void *malloc(size_t size) {
    if (!atomic_load_explicit(&ready, memory_order_acquire)) {
        if (resolving) return mapped_allocate(size, _Alignof(max_align_t), 0);
        pthread_once(&initialization, initialize);
    }
    return size < DISK_THRESHOLD ? small_allocate(size, _Alignof(max_align_t))
                                 : mapped_allocate(size, _Alignof(max_align_t), 1);
}

void free(void *pointer) {
    if (!pointer) return;
    int saved_errno = errno;
    if (!atomic_load_explicit(&ready, memory_order_acquire) && !resolving)
        pthread_once(&initialization, initialize);
    pthread_mutex_lock(&allocations_lock);
    SmallSlab *small = small_owner(pointer);
    if (small) {
        size_t index = small_slot(small, pointer);
        small->sizes[index] = 0;
        *(void **)pointer = small->recycled;
        small->recycled = pointer;
        if (small->live == small->capacity) small_link(small);
        int release = --small->live == 0;
        disk_bytes -= small_sizes[small->bin];
        disk_count--;
        small_count--;
        if (release && !small_empty[small->bin]) {
            small_empty[small->bin] = small;
            release = 0;
        }
        if (release) {
            small_unlink(small);
            SmallSlab **owner = &small_index[small_hash((uintptr_t)small->base)];
            while (*owner != small) owner = &(*owner)->hash_next;
            *owner = small->hash_next;
            disk_mappings--;
            small_mappings--;
        }
        pthread_mutex_unlock(&allocations_lock);
        if (release) {
            munmap(small->reservation, 2 * SLAB_BYTES);
            munmap(small, small->metadata_size);
        }
        errno = saved_errno;
        return;
    }
    Allocation **link = allocation_bucket(pointer);
    while (*link && (*link)->pointer != pointer) link = &(*link)->next;
    Allocation *entry = *link;
    Slab *release_slab = NULL;
    int pooled = 0;
    if (entry) {
        *link = entry->next;
        if (entry->disk) {
            disk_bytes -= entry->backing_size;
            disk_count--;
            Slab *slab = entry->slab;
            if (slab) {
                pooled = 1;
                entry->next = slab->available;
                slab->available = entry;
                if (++slab->free_count == slab->capacity) {
                    Slab **owner = &slabs[slab->bin];
                    while (*owner != slab) owner = &(*owner)->next;
                    *owner = slab->next;
                    release_slab = slab;
                    disk_mappings--;
                }
            } else {
                disk_mappings--;
            }
        }
    }
    pthread_mutex_unlock(&allocations_lock);
    if (entry) {
        if (release_slab) {
            munmap(release_slab->base, SLAB_BYTES);
            munmap(release_slab, release_slab->metadata_size);
        } else if (!pooled) {
            munmap(entry->base, entry->mapping_size);
            munmap(entry, page_size);
        }
    } else if (libc_free) {
        libc_free(pointer);
    } else {
        // Only reachable during dlsym initialization; no libc ownership guessed.
        Allocation *pending = mmap(NULL, page_size, PROT_READ | PROT_WRITE,
                                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (pending == MAP_FAILED) _exit(127);
        pending->pointer = pointer;
        pthread_mutex_lock(&allocations_lock);
        pending->next = deferred_frees;
        deferred_frees = pending;
        pthread_mutex_unlock(&allocations_lock);
    }
    errno = saved_errno;
}

size_t malloc_usable_size(UsablePointer pointer) {
    if (!pointer) return 0;
    if (!atomic_load_explicit(&ready, memory_order_acquire) && !resolving)
        pthread_once(&initialization, initialize);
    pthread_mutex_lock(&allocations_lock);
    SmallSlab *small = small_owner(pointer);
    if (small) {
        size_t size = small->sizes[small_slot(small, pointer)] - 1;
        pthread_mutex_unlock(&allocations_lock);
        return size;
    }
    Allocation *entry = *allocation_bucket(pointer);
    while (entry && entry->pointer != pointer) entry = entry->next;
    size_t size = entry ? entry->size : 0;
    pthread_mutex_unlock(&allocations_lock);
    return entry ? size : libc_usable_size(pointer);
}

void *calloc(size_t count, size_t size) {
    size_t bytes;
    if (__builtin_mul_overflow(count, size, &bytes)) {
        errno = ENOMEM;
        return NULL;
    }
    void *pointer = malloc(bytes);
    // Reused small and large slabs need explicit zeroing. Larger mappings
    // are always fresh files, already zero-filled without touching every page.
    if (pointer && bytes <= POOL_MAX)
        memset(pointer, 0, bytes);
    return pointer;
}

void *realloc(void *pointer, size_t size) {
    if (!pointer) return malloc(size);
    if (!size) {
        free(pointer);
        return NULL;
    }
    if (!atomic_load_explicit(&ready, memory_order_acquire) && !resolving)
        pthread_once(&initialization, initialize);
    pthread_mutex_lock(&allocations_lock);
    SmallSlab *small = small_owner(pointer);
    size_t old_size;
    int owned;
    if (small) {
        size_t index = small_slot(small, pointer);
        old_size = small->sizes[index] - 1;
        if (size <= small_sizes[small->bin]) {
            small->sizes[index] = (uint16_t)(size + 1);
            pthread_mutex_unlock(&allocations_lock);
            return pointer;
        }
        owned = 1;
    } else {
        Allocation *entry = *allocation_bucket(pointer);
        while (entry && entry->pointer != pointer) entry = entry->next;
        owned = entry != NULL;
        old_size = entry ? entry->size : 0;
    }
    pthread_mutex_unlock(&allocations_lock);
    if (!owned) old_size = libc_usable_size(pointer);
    void *replacement = malloc(size);
    if (replacement) {
        memcpy(replacement, pointer, old_size < size ? old_size : size);
        free(pointer);
    }
    return replacement;
}

void *reallocarray(void *pointer, size_t count, size_t size) {
    size_t bytes;
    if (__builtin_mul_overflow(count, size, &bytes)) {
        errno = ENOMEM;
        return NULL;
    }
    return realloc(pointer, bytes);
}

int posix_memalign(void **result, size_t alignment, size_t size) {
    if (alignment < sizeof(void *) || (alignment & (alignment - 1))) return EINVAL;
    int saved_errno = errno;
    if (!atomic_load_explicit(&ready, memory_order_acquire) && !resolving)
        pthread_once(&initialization, initialize);
    int disk = atomic_load_explicit(&ready, memory_order_acquire);
    void *pointer = disk && size < DISK_THRESHOLD && alignment <= DISK_THRESHOLD
        ? small_allocate(size, alignment) : mapped_allocate(size, alignment, disk);
    int error = pointer ? 0 : ENOMEM;
    if (pointer) *result = pointer;
    errno = saved_errno;
    return error;
}

void *memalign(size_t alignment, size_t size) {
    // Bionic accepts power-of-two alignments smaller than sizeof(void *).
    if (!alignment || (alignment & (alignment - 1))) {
        errno = EINVAL;
        return NULL;
    }
    void *pointer;
    int error = posix_memalign(&pointer, alignment < sizeof(void *) ? sizeof(void *) : alignment, size);
    if (error) {
        errno = error;
        return NULL;
    }
    return pointer;
}

void *aligned_alloc(size_t alignment, size_t size) {
    if (!alignment || size % alignment) {
        errno = EINVAL;
        return NULL;
    }
    return memalign(alignment, size);
}

__attribute__((constructor)) static void start_heap(void) {
    pthread_once(&initialization, initialize);
}
