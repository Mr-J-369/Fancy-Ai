// Reconstruct checkpoint-owned SD1.5/SDXL MNN CLIPs from a weight-free graph recipe.
// Usage: componentconv <components-directory> <checkpoint> <output-directory>
// Rows are decoded/written independently; even token embeddings never expand in RAM.
// Dense INT8 encoding follows MNN 3.6.1 IDSTEncoder.hpp and WeightQuantAndCoding.cpp.
// See docs/CLIP-COMPONENTS.md for format, provenance and verification.
#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
[[noreturn]] void fail(const std::string& message) { throw std::runtime_error(message); }
void systemError(const std::string& operation) { fail(operation + ": " + std::strerror(errno)); }

struct Mapping {
    int fd = -1;
    const uint8_t* data = nullptr;
    size_t size = 0;
    explicit Mapping(const std::string& path) {
        fd = ::open(path.c_str(), O_RDONLY);
        if (fd < 0) systemError("open " + path);
        struct stat st{};
        if (fstat(fd, &st)) { ::close(fd); systemError("stat " + path); }
        if (st.st_size <= 0) { ::close(fd); fail("empty file " + path); }
        size = static_cast<size_t>(st.st_size);
        void* mapped = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (mapped == MAP_FAILED) { ::close(fd); systemError("mmap " + path); }
        data = static_cast<const uint8_t*>(mapped);
    }
    ~Mapping() { if (data) munmap(const_cast<uint8_t*>(data), size); if (fd >= 0) ::close(fd); }
    Mapping(const Mapping&) = delete;
    Mapping& operator=(const Mapping&) = delete;
};

struct Reader {
    const uint8_t* data;
    size_t size, position = 0;
    const uint8_t* bytes(size_t count) {
        if (count > size - position) fail("truncated CLIP recipe");
        const auto* result = data + position;
        position += count;
        return result;
    }
    template<class T> T number() { T result; std::memcpy(&result, bytes(sizeof(T)), sizeof(T)); return result; }
    std::string text() {
        const auto length = number<uint16_t>();
        return std::string(reinterpret_cast<const char*>(bytes(length)), length);
    }
};

// Safetensors has one JSON header followed by directly addressable tensor bytes.
// Parse its structural fields; metadata and unknown fields are skipped as JSON.
struct Json {
    std::string text;
    size_t position = 0;
    void whitespace() { while (position < text.size() && std::strchr(" \r\n\t", text[position])) ++position; }
    char peek() { whitespace(); if (position == text.size()) fail("truncated safetensors JSON"); return text[position]; }
    bool consume(char value) { if (peek() != value) return false; ++position; return true; }
    void expect(char value) { if (!consume(value)) fail("invalid safetensors JSON"); }
    std::string string() {
        expect('"');
        std::string result;
        while (position < text.size()) {
            unsigned char value = text[position++];
            if (value == '"') return result;
            if (value < 0x20) fail("invalid JSON string");
            if (value != '\\') { result.push_back(static_cast<char>(value)); continue; }
            if (position == text.size()) fail("truncated JSON escape");
            const char escaped = text[position++];
            if (escaped == '"' || escaped == '\\' || escaped == '/') result.push_back(escaped);
            else if (escaped == 'b') result.push_back('\b');
            else if (escaped == 'f') result.push_back('\f');
            else if (escaped == 'n') result.push_back('\n');
            else if (escaped == 'r') result.push_back('\r');
            else if (escaped == 't') result.push_back('\t');
            else if (escaped == 'u') {
                if (text.size() - position < 4) fail("truncated Unicode escape");
                unsigned code = 0;
                for (int i = 0; i < 4; ++i) {
                    const char digit = text[position++];
                    const int number = digit >= '0' && digit <= '9' ? digit - '0' :
                        digit >= 'a' && digit <= 'f' ? digit - 'a' + 10 :
                        digit >= 'A' && digit <= 'F' ? digit - 'A' + 10 : -1;
                    if (number < 0) fail("invalid Unicode escape");
                    code = code * 16 + static_cast<unsigned>(number);
                }
                if (code < 128) result.push_back(static_cast<char>(code));
                else if (code < 2048) { result.push_back(static_cast<char>(0xc0 | (code >> 6))); result.push_back(static_cast<char>(0x80 | (code & 63))); }
                else { result.push_back(static_cast<char>(0xe0 | (code >> 12))); result.push_back(static_cast<char>(0x80 | ((code >> 6) & 63))); result.push_back(static_cast<char>(0x80 | (code & 63))); }
            } else fail("invalid JSON escape");
        }
        fail("unterminated JSON string");
    }
    uint64_t integer() {
        whitespace();
        if (position == text.size() || text[position] < '0' || text[position] > '9') fail("invalid safetensors integer");
        uint64_t value = 0;
        while (position < text.size() && text[position] >= '0' && text[position] <= '9') {
            const unsigned digit = static_cast<unsigned>(text[position++] - '0');
            if (value > (UINT64_MAX - digit) / 10) fail("safetensors integer overflow");
            value = value * 10 + digit;
        }
        return value;
    }
    std::vector<uint64_t> integers() {
        expect('[');
        std::vector<uint64_t> result;
        if (consume(']')) return result;
        do { result.push_back(integer()); } while (consume(','));
        expect(']');
        return result;
    }
    void skip(unsigned depth = 0) {
        if (depth > 128) fail("safetensors JSON nesting exceeds parser limit");
        const char value = peek();
        if (value == '"') { string(); return; }
        if (value == '{') {
            expect('{'); if (consume('}')) return;
            do { string(); expect(':'); skip(depth + 1); } while (consume(','));
            expect('}'); return;
        }
        if (value == '[') {
            expect('['); if (consume(']')) return;
            do { skip(depth + 1); } while (consume(','));
            expect(']'); return;
        }
        const size_t start = position;
        while (position < text.size() && !std::strchr(",]} \r\n\t", text[position])) ++position;
        if (position == start) fail("invalid JSON value");
    }
};

struct Tensor { std::string dtype; std::vector<uint64_t> shape, offsets; };
struct Checkpoint {
    Mapping mapping;
    size_t base;
    std::unordered_map<std::string, Tensor> tensors;
    explicit Checkpoint(const std::string& path) : mapping(path), base(0) {
        if (mapping.size < 8) fail("checkpoint header is truncated");
        uint64_t length; std::memcpy(&length, mapping.data, 8);
        if (length > mapping.size - 8) fail("checkpoint header exceeds file");
        base = 8 + static_cast<size_t>(length);
        Json json{std::string(reinterpret_cast<const char*>(mapping.data + 8), static_cast<size_t>(length))};
        json.expect('{');
        if (!json.consume('}')) {
            do {
                const std::string name = json.string(); json.expect(':');
                if (name == "__metadata__") { json.skip(); continue; }
                Tensor tensor; json.expect('{');
                if (!json.consume('}')) {
                    do {
                        const std::string field = json.string(); json.expect(':');
                        if (field == "dtype") tensor.dtype = json.string();
                        else if (field == "shape") tensor.shape = json.integers();
                        else if (field == "data_offsets") tensor.offsets = json.integers();
                        else json.skip();
                    } while (json.consume(','));
                    json.expect('}');
                }
                if (!tensors.emplace(name, std::move(tensor)).second) fail("duplicate checkpoint tensor " + name);
            } while (json.consume(','));
            json.expect('}');
        }
        json.whitespace();
        if (json.position != json.text.size()) fail("unexpected data after checkpoint header");
    }
};

float fromHalf(uint16_t half) {
    uint32_t sign = static_cast<uint32_t>(half & 0x8000) << 16;
    uint32_t exponent = (half >> 10) & 31, mantissa = half & 1023, bits;
    if (!exponent) {
        if (!mantissa) bits = sign;
        else { exponent = 1; while (!(mantissa & 1024)) { mantissa <<= 1; --exponent; }
            bits = sign | ((exponent + 112) << 23) | ((mantissa & 1023) << 13); }
    } else if (exponent == 31) bits = sign | 0x7f800000 | (mantissa << 13);
    else bits = sign | ((exponent + 112) << 23) | (mantissa << 13);
    float result; std::memcpy(&result, &bits, 4); return result;
}
float fromBfloat16(uint16_t bfloat) {
    const uint32_t bits = static_cast<uint32_t>(bfloat) << 16;
    float result; std::memcpy(&result, &bits, 4); return result;
}
uint16_t toHalf(float value) {
    uint32_t bits; std::memcpy(&bits, &value, 4);
    const uint32_t sign = (bits >> 16) & 0x8000, mantissa = bits & 0x7fffff;
    const int exponent = static_cast<int>((bits >> 23) & 255) - 112;
    if (exponent >= 31) return static_cast<uint16_t>(sign | 0x7c00);
    if (exponent <= 0) {
        if (exponent < -10) return static_cast<uint16_t>(sign);
        const uint32_t full = mantissa | 0x800000, shift = 14 - exponent;
        uint32_t rounded = full >> shift, remainder = full & ((1u << shift) - 1), halfway = 1u << (shift - 1);
        if (remainder > halfway || (remainder == halfway && (rounded & 1))) ++rounded;
        return static_cast<uint16_t>(sign | rounded);
    }
    uint32_t rounded = (static_cast<uint32_t>(exponent) << 10) | (mantissa >> 13);
    const uint32_t remainder = mantissa & 8191;
    if (remainder > 4096 || (remainder == 4096 && (rounded & 1))) ++rounded;
    return static_cast<uint16_t>(sign | rounded);
}

struct Literal { uint64_t offset; uint32_t size; const uint8_t* bytes; };
struct Output { std::string name; uint64_t size; std::vector<Literal> literals; int fd = -1; };
struct Rule {
    uint8_t file, encoding, flags, rank;
    uint64_t offset, alpha;
    uint32_t rows, cols, sourceRows, sourceCols, sourceRow;
    float multiplier;
    std::string source;
    const Tensor* tensor = nullptr;
};
void writeAt(int fd, const void* bytes, size_t count, uint64_t offset) {
    const auto* data = static_cast<const uint8_t*>(bytes);
    while (count) {
        const auto written = pwrite(fd, data, count, static_cast<off_t>(offset));
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) systemError("write CLIP component");
        data += written; count -= static_cast<size_t>(written); offset += static_cast<uint64_t>(written);
    }
}
void bounds(uint64_t offset, uint64_t count, uint64_t size) {
    if (offset > size || count > size - offset) fail("CLIP recipe output range exceeds file");
}

void convert(const char* directory, const char* checkpointPath, const char* destination) {
    Mapping recipe(std::string(directory) + "/clip_recipe.bin");
    Reader reader{recipe.data, recipe.size};
    if (std::memcmp(reader.bytes(8), "CLIPRCP1", 8)) fail("unsupported CLIP recipe");
    const auto fileCount = reader.number<uint32_t>();
    std::vector<Output> outputs;
    for (uint32_t i = 0; i < fileCount; ++i) {
        Output output; output.name = reader.text(); output.size = reader.number<uint64_t>();
        if (output.name.empty() || output.name.find_first_of("/\\") != std::string::npos || output.name == "." || output.name == "..") fail("invalid CLIP output filename");
        const auto count = reader.number<uint32_t>();
        for (uint32_t j = 0; j < count; ++j) {
            Literal literal; literal.offset = reader.number<uint64_t>(); literal.size = reader.number<uint32_t>();
            literal.bytes = reader.bytes(literal.size); bounds(literal.offset, literal.size, output.size);
            output.literals.push_back(literal);
        }
        outputs.push_back(std::move(output));
    }
    Checkpoint checkpoint(checkpointPath);
    const auto count = reader.number<uint32_t>();
    std::vector<Rule> rules;
    for (uint32_t i = 0; i < count; ++i) {
        Rule rule;
        rule.file = reader.number<uint8_t>(); rule.encoding = reader.number<uint8_t>();
        rule.flags = reader.number<uint8_t>(); rule.rank = reader.number<uint8_t>();
        rule.offset = reader.number<uint64_t>(); rule.alpha = reader.number<uint64_t>();
        rule.rows = reader.number<uint32_t>(); rule.cols = reader.number<uint32_t>();
        rule.sourceRows = reader.number<uint32_t>(); rule.sourceCols = reader.number<uint32_t>();
        rule.sourceRow = reader.number<uint32_t>(); rule.multiplier = reader.number<float>(); rule.source = reader.text();
        if (rule.file >= outputs.size() || rule.encoding > 3 || (rule.flags & ~7) || !rule.rows || !rule.cols || !std::isfinite(rule.multiplier)) fail("invalid CLIP tensor rule");
        auto found = checkpoint.tensors.find(rule.source);
        if (found == checkpoint.tensors.end()) fail("checkpoint has no CLIP tensor " + rule.source);
        rule.tensor = &found->second;
        const auto& tensor = *rule.tensor;
        const std::vector<uint64_t> expected = rule.rank == 1 ? std::vector<uint64_t>{rule.sourceRows} : std::vector<uint64_t>{rule.sourceRows, rule.sourceCols};
        if ((rule.rank != 1 && rule.rank != 2) || tensor.shape != expected || (rule.rank == 1 && rule.sourceCols != 1)) fail("CLIP tensor shape mismatch: " + rule.source);
        if (tensor.dtype != "F16" && tensor.dtype != "F32" && tensor.dtype != "BF16") fail("unsupported CLIP tensor dtype " + tensor.dtype + ": " + rule.source);
        const uint64_t tensorBytes = static_cast<uint64_t>(rule.sourceRows) * rule.sourceCols * (tensor.dtype == "F32" ? 4 : 2);
        if (tensor.offsets.size() != 2 || tensor.offsets[1] < tensor.offsets[0] || tensor.offsets[1] - tensor.offsets[0] != tensorBytes || tensor.offsets[1] > checkpoint.mapping.size - checkpoint.base) fail("invalid CLIP tensor data range: " + rule.source);
        const uint64_t inputRows = (rule.flags & 1) ? rule.cols : rule.rows;
        const uint64_t inputCols = (rule.flags & 1) ? rule.rows : rule.cols;
        if (inputCols != rule.sourceCols || rule.sourceRow > rule.sourceRows || inputRows > rule.sourceRows - rule.sourceRow) fail("invalid CLIP tensor slice: " + rule.source);
        const uint64_t bytes = static_cast<uint64_t>(rule.rows) * rule.cols * (rule.encoding == 0 ? 4 : rule.encoding == 1 ? 2 : 1);
        bounds(rule.offset, bytes, outputs[rule.file].size);
        if (rule.encoding >= 2) bounds(rule.alpha, static_cast<uint64_t>(rule.rows) * (rule.encoding == 2 ? 4 : 8), outputs[rule.file].size);
        rules.push_back(std::move(rule));
    }
    if (reader.position != reader.size) fail("unexpected bytes after CLIP recipe");
    if (mkdir(destination, 0700) && errno != EEXIST) systemError("create CLIP output directory");
    for (auto& output : outputs) {
        const std::string path = std::string(destination) + "/" + output.name;
        output.fd = ::open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600);
        if (output.fd < 0) systemError("create " + path);
        if (ftruncate(output.fd, static_cast<off_t>(output.size))) systemError("size " + path);
        for (const auto& literal : output.literals) writeAt(output.fd, literal.bytes, literal.size, literal.offset);
    }
    std::printf("CLIP weights: %zu mapped tensors; writing %zu checkpoint-owned files\n", rules.size(), outputs.size());
    std::fflush(stdout);
    size_t completed = 0;
    const size_t pageSize = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    for (const auto& rule : rules) {
        const auto& tensor = *rule.tensor;
        const auto* source = checkpoint.mapping.data + checkpoint.base + tensor.offsets[0];
        const int fd = outputs[rule.file].fd;
        std::vector<float> row(rule.cols);
        std::vector<uint16_t> half(rule.encoding == 1 ? rule.cols : 0);
        std::vector<uint8_t> quantized(rule.encoding >= 2 ? rule.cols : 0);
        for (uint32_t r = 0; r < rule.rows; ++r) {
            for (uint32_t c = 0; c < rule.cols; ++c) {
                const uint64_t index = (rule.flags & 1) ? static_cast<uint64_t>(rule.sourceRow + c) * rule.sourceCols + r : static_cast<uint64_t>(rule.sourceRow + r) * rule.sourceCols + c;
                float value;
                if (tensor.dtype == "F16") { uint16_t bits; std::memcpy(&bits, source + index * 2, 2); value = fromHalf(bits); }
                else if (tensor.dtype == "BF16") { uint16_t bits; std::memcpy(&bits, source + index * 2, 2); value = fromBfloat16(bits); }
                else std::memcpy(&value, source + index * 4, 4);
                value *= rule.multiplier;
                if (!std::isfinite(value)) fail("nonfinite CLIP weight: " + rule.source);
                if ((rule.flags & 4) && std::fabs(value) < std::numeric_limits<float>::min()) value = 0;
                row[c] = value;
            }
            const uint64_t offset = rule.offset + static_cast<uint64_t>(r) * rule.cols * (rule.encoding == 0 ? 4 : rule.encoding == 1 ? 2 : 1);
            if (rule.encoding == 0) writeAt(fd, row.data(), row.size() * 4, offset);
            else if (rule.encoding == 1) {
                for (size_t c = 0; c < row.size(); ++c) {
                    half[c] = toHalf((rule.flags & 2) ? std::clamp(row[c], -65504.f, 65504.f) : row[c]);
                    if ((half[c] & 0x7c00) == 0x7c00) fail("CLIP weight exceeds FP16 range: " + rule.source);
                }
                writeAt(fd, half.data(), half.size() * 2, offset);
            } else {
                float scale, minimum = 0;
                if (rule.encoding == 2) {
                    float maximum = 0; for (float value : row) maximum = std::max(maximum, std::fabs(value));
                    scale = maximum / 127.f;
                    writeAt(fd, &scale, 4, rule.alpha + static_cast<uint64_t>(r) * 4);
                } else {
                    const auto extrema = std::minmax_element(row.begin(), row.end());
                    minimum = *extrema.first; scale = (*extrema.second - minimum) / 255.f;
                    const float scales[]{minimum, scale};
                    if (!std::isfinite(scale)) fail("CLIP quantization scale overflow: " + rule.source);
                    writeAt(fd, scales, 8, rule.alpha + static_cast<uint64_t>(r) * 8);
                }
                for (size_t c = 0; c < row.size(); ++c) {
                    const float quotient = scale > 1e-6f ? (row[c] - minimum) / scale : 0.f;
                    const float index = std::round(quotient) + (rule.encoding == 2 ? 128.f : 0.f);
                    quantized[c] = static_cast<uint8_t>(std::clamp(index, 0.f, 255.f));
                }
                writeAt(fd, quantized.data(), quantized.size(), offset);
            }
        }
        // Release consumed file pages; the next matrix need not retain earlier weights in RSS.
        const uintptr_t begin = reinterpret_cast<uintptr_t>(source) & ~(pageSize - 1);
        const uintptr_t end = (reinterpret_cast<uintptr_t>(source) + tensor.offsets[1] - tensor.offsets[0] + pageSize - 1) & ~(pageSize - 1);
        madvise(reinterpret_cast<void*>(begin), end - begin, MADV_DONTNEED);
        if (++completed % 32 == 0 || completed == rules.size()) { std::printf("CLIP weights: %zu/%zu tensors\n", completed, rules.size()); std::fflush(stdout); }
    }
    for (auto& output : outputs) { if (::close(output.fd)) systemError("close " + output.name); output.fd = -1; }
    std::puts("Checkpoint CLIP conversion complete");
}
}  // namespace

int main(int argc, char** argv) {
    if (argc != 4) { std::fprintf(stderr, "usage: componentconv <components-directory> <checkpoint.safetensors> <output-directory>\n"); return 2; }
    try { convert(argv[1], argv[2], argv[3]); return 0; }
    catch (const std::exception& error) { std::fprintf(stderr, "%s\n", error.what()); return 1; }
}
