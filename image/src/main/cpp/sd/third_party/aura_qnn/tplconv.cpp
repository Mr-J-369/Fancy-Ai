// tplconv -- the on-device converter's weight stage, in C++.
//
// Phase 4 of docs/ON-DEVICE-CONVERT.md. Reads a checkpoint (.safetensors), a
// binary recipe (tpl_recipe_bin.py) and the template's own pack, and writes a
// TPLPACK1 pack that qnn-context-binary-generator turns into unet.bin.
//
// It is a port of tpl_apply.py's `apply`, and the bar is not "close": the PC's
// ds_applied.pack and ar_applied.pack already exist, so this must reproduce them
// BYTE FOR BYTE. Nothing here needs a device or a render to be judged.
//
// ⚠ ARITHMETIC ORDER IS THE SPEC. Every one of these was measured against the
// converter's own output (tpl_round*.py, Phase 1) and the obvious-looking
// alternative is wrong:
//   i8   q = rha(w * 127 / max)          -- NOT w / (max/127); a weight at
//                                           exactly max/2 must land on 63.5
//   u8   x = f32(w*255/(hi-lo) - o); q = floor(f64(x + f32(0.5)))
//                                        -- the +0.5 happens in FLOAT32
//   i32  q = rha(f64(b) / f64(f32 scale)) -- divide in float64 by the float32
//                                           scale, never in float32 (128-unit steps)
// where rha is round-half-AWAY-from-zero on a float64, then clipped.
//
// Memory: two passes. The first computes every scale (a few MB) and the byte
// layout, which is derivable from dims alone. The second writes the header and
// then streams each entry's bytes, so peak RSS is one tensor rather than the
// whole 863 MB pack -- the PC's Python peaks at ~5 GB, which a phone cannot do.
//
// Build (host):    c++ -O2 -std=c++17 -o tplconv tplconv.cpp
// Build (android): see t4_prep.sh

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <list>
#include <limits>
#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {

[[noreturn]] void die(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    exit(1);
}

// ---------------------------------------------------------------- mmap ----

struct Mapped {
    const uint8_t* p = nullptr;
    size_t len = 0;
    int fd = -1;
    void open_(const char* path) {
        fd = ::open(path, O_RDONLY);
        if (fd < 0) die("cannot open %s", path);
        struct stat st;
        if (fstat(fd, &st) != 0) die("cannot stat %s", path);
        len = (size_t)st.st_size;
        void* m = mmap(nullptr, len, PROT_READ, MAP_PRIVATE, fd, 0);
        if (m == MAP_FAILED) die("cannot mmap %s (%zu bytes)", path, len);
        p = (const uint8_t*)m;
    }
    ~Mapped() {
        if (p) munmap((void*)p, len);
        if (fd >= 0) close(fd);
    }
};

// ------------------------------------------------------- safetensors ------
//
// The header is one JSON object: {"name": {"dtype": "F16", "shape": [...],
// "data_offsets": [a, b]}, ...} plus an optional "__metadata__". It is machine
// written and flat, so a focused scanner is enough -- and is far less risk than
// vendoring a general JSON parser into a converter whose whole point is exact
// bytes.

struct Tensor {
    std::string dtype;
    std::vector<int64_t> shape;
    uint64_t begin = 0, end = 0;
};

struct Safetensors {
    Mapped m;
    const uint8_t* data = nullptr;  // start of the tensor blob
    std::unordered_map<std::string, Tensor> tensors;

    void load(const char* path) {
        m.open_(path);
        if (m.len < 8) die("%s: too small", path);
        uint64_t hlen;
        memcpy(&hlen, m.p, 8);
        if (hlen > m.len - 8) die("%s: bad header length", path);
        std::string h((const char*)m.p + 8, hlen);
        data = m.p + 8 + hlen;
        parse(h);
    }

    static void skipws(const std::string& s, size_t& i) {
        while (i < s.size() && (s[i] == ' ' || s[i] == '\n' || s[i] == '\t' || s[i] == '\r')) i++;
    }
    static std::string str(const std::string& s, size_t& i) {
        skipws(s, i);
        if (s[i] != '"') die("json: expected string at %zu", i);
        i++;
        std::string out;
        while (i < s.size() && s[i] != '"') {
            if (s[i] == '\\') i++;  // keys here never need real unescaping
            out.push_back(s[i++]);
        }
        i++;
        return out;
    }
    static std::vector<int64_t> ints(const std::string& s, size_t& i) {
        skipws(s, i);
        if (s[i] != '[') die("json: expected array at %zu", i);
        i++;
        std::vector<int64_t> v;
        for (;;) {
            skipws(s, i);
            if (s[i] == ']') { i++; break; }
            if (s[i] == ',') { i++; continue; }
            v.push_back(strtoll(s.c_str() + i, nullptr, 10));
            while (i < s.size() && s[i] != ',' && s[i] != ']') i++;
        }
        return v;
    }
    // Skips a value of any shape -- used only for "__metadata__".
    static void skipval(const std::string& s, size_t& i) {
        skipws(s, i);
        int depth = 0;
        bool instr = false;
        for (; i < s.size(); i++) {
            char c = s[i];
            if (instr) {
                if (c == '\\') i++;
                else if (c == '"') instr = false;
                continue;
            }
            if (c == '"') instr = true;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) { i++; return; }
            } else if (depth == 0 && (c == ',' || c == '}')) return;
        }
    }

    void parse(const std::string& s) {
        size_t i = 0;
        skipws(s, i);
        if (s[i] != '{') die("json: header is not an object");
        i++;
        for (;;) {
            skipws(s, i);
            if (i >= s.size() || s[i] == '}') break;
            if (s[i] == ',') { i++; continue; }
            std::string name = str(s, i);
            skipws(s, i);
            if (s[i] != ':') die("json: expected ':' after %s", name.c_str());
            i++;
            if (name == "__metadata__") { skipval(s, i); continue; }
            skipws(s, i);
            if (s[i] != '{') die("json: %s is not an object", name.c_str());
            i++;
            Tensor t;
            for (;;) {
                skipws(s, i);
                if (s[i] == '}') { i++; break; }
                if (s[i] == ',') { i++; continue; }
                std::string k = str(s, i);
                skipws(s, i);
                i++;  // ':'
                if (k == "dtype") t.dtype = str(s, i);
                else if (k == "shape") t.shape = ints(s, i);
                else if (k == "data_offsets") {
                    auto v = ints(s, i);
                    if (v.size() != 2 || v[0] < 0 || v[1] < v[0])
                        die("%s: invalid tensor data offsets", name.c_str());
                    t.begin = (uint64_t)v[0];
                    t.end = (uint64_t)v[1];
                } else skipval(s, i);
            }
            tensors.emplace(std::move(name), std::move(t));
        }
    }

    // Expand checkpoint storage types to the float32 working representation.
    std::vector<float> get_f32(const std::string& name, std::vector<int64_t>& shape) const {
        auto it = tensors.find(name);
        if (it == tensors.end()) die("checkpoint has no tensor '%s'", name.c_str());
        const Tensor& t = it->second;
        shape = t.shape;
        size_t n = 1;
        for (int64_t d : t.shape) {
            if (d <= 0 || (uint64_t)d > std::numeric_limits<size_t>::max() / n)
                die("%s: invalid tensor shape", name.c_str());
            n *= (size_t)d;
        }
        if (t.end < t.begin || t.end > m.len - (size_t)(data - m.p))
            die("%s: tensor data exceeds checkpoint", name.c_str());
        const size_t width = (t.dtype == "F16" || t.dtype == "BF16") ? 2 : t.dtype == "F32" ? 4 : 0;
        if (!width) die("%s: unsupported dtype %s", name.c_str(), t.dtype.c_str());
        if (n > std::numeric_limits<size_t>::max() / width || t.end - t.begin != n * width)
            die("%s: tensor byte count does not match shape", name.c_str());
        std::vector<float> out(n);
        const uint8_t* src = data + t.begin;
        if (t.dtype == "F16") {
            if (t.end - t.begin != n * 2) die("%s: F16 size mismatch", name.c_str());
            for (size_t k = 0; k < n; k++) {
                uint16_t h;
                memcpy(&h, src + 2 * k, 2);
                out[k] = half_to_float(h);
            }
        } else if (t.dtype == "BF16") {
            for (size_t k = 0; k < n; k++) {
                uint16_t b;
                memcpy(&b, src + 2 * k, 2);
                const uint32_t bits = static_cast<uint32_t>(b) << 16;
                memcpy(&out[k], &bits, sizeof(bits));
            }
        } else if (t.dtype == "F32") {
            if (t.end - t.begin != n * 4) die("%s: F32 size mismatch", name.c_str());
            memcpy(out.data(), src, n * 4);
        } else {
            die("%s: unsupported dtype %s", name.c_str(), t.dtype.c_str());
        }
        return out;
    }

    // float -> IEEE half, round-to-nearest-even.
    //
    // ⚠ Why round at all: merging LoRA in memory would otherwise be MORE
    // precise than merging with a tool and saving a checkpoint, and the two
    // would produce different packs. Rounding here makes
    // `--lora X:0.8` byte-identical to `lora_merge.py` + convert, which is what
    // makes the Python path usable as the reference.
    static uint16_t float_to_half(float f) {
        uint32_t x;
        memcpy(&x, &f, 4);
        uint32_t sign = (x >> 16) & 0x8000;
        int32_t exp = (int32_t)((x >> 23) & 0xff) - 127 + 15;
        uint32_t man = x & 0x7fffff;
        if (((x >> 23) & 0xff) == 0xff) return (uint16_t)(sign | 0x7c00 | (man ? 0x200 : 0));
        if (exp >= 0x1f) return (uint16_t)(sign | 0x7c00);            // overflow -> inf
        if (exp <= 0) {                                                // subnormal / zero
            if (exp < -10) return (uint16_t)sign;
            man |= 0x800000;
            uint32_t shift = (uint32_t)(14 - exp);
            uint32_t h = man >> shift;
            uint32_t rem = man & ((1u << shift) - 1);
            uint32_t half_ = 1u << (shift - 1);
            if (rem > half_ || (rem == half_ && (h & 1))) h++;
            return (uint16_t)(sign | h);
        }
        uint32_t h = ((uint32_t)exp << 10) | (man >> 13);
        uint32_t rem = man & 0x1fff;
        if (rem > 0x1000 || (rem == 0x1000 && (h & 1))) h++;
        return (uint16_t)(sign | h);
    }

    // IEEE half -> float, exact for every input including subnormals and NaN.
    static float half_to_float(uint16_t h) {
        uint32_t sign = (uint32_t)(h & 0x8000) << 16;
        uint32_t exp = (h >> 10) & 0x1f;
        uint32_t man = h & 0x3ff;
        uint32_t bits;
        if (exp == 0) {
            if (man == 0) {
                bits = sign;
            } else {  // subnormal: normalise
                exp = 1;
                while ((man & 0x400) == 0) { man <<= 1; exp--; }
                man &= 0x3ff;
                bits = sign | ((exp + 112) << 23) | (man << 13);
            }
        } else if (exp == 0x1f) {
            bits = sign | 0x7f800000 | (man << 13);
        } else {
            bits = sign | ((exp + 112) << 23) | (man << 13);
        }
        float f;
        memcpy(&f, &bits, 4);
        return f;
    }
};

// ------------------------------------------------------------- recipe ----

enum Rule { R_TEMPLATE = 0, R_I8_AXIS = 1, R_U8_ASYM = 2, R_I32_SCALAR = 3,
            R_I32_AXIS_BIAS = 4, R_I32_AXIS_ZERO = 5, R_FLOAT16 = 6, R_FLOAT32 = 7 };

struct Entry {
    uint8_t rule = 0, ndims = 0, nperm = 0;
    int8_t axis = -1, head = -1;
    uint8_t perm[4] = {0, 0, 0, 0};
    uint32_t dims[4] = {0, 0, 0, 0};
    float in_scale = 0.f;
    std::string binvar, source, weight;

    size_t count() const {
        size_t n = 1;
        for (int i = 0; i < ndims; i++) n *= dims[i];
        return n;
    }
};

struct Reader {
    const uint8_t* p;
    const uint8_t* end;
    template <class T> T get() {
        if (p + sizeof(T) > end) die("recipe: truncated");
        T v;
        memcpy(&v, p, sizeof(T));
        p += sizeof(T);
        return v;
    }
    std::string s() {
        uint16_t n = get<uint16_t>();
        if (p + n > end) die("recipe: truncated string");
        std::string v((const char*)p, n);
        p += n;
        return v;
    }
};

std::vector<Entry> read_recipe(const char* path) {
    Mapped m;
    m.open_(path);
    Reader r{m.p, m.p + m.len};
    char magic[8];
    memcpy(magic, r.p, 8);
    r.p += 8;
    if (memcmp(magic, "TPLRCP1", 7) != 0) die("%s: not a TPLRCP1 recipe", path);
    uint32_t n = r.get<uint32_t>();
    std::vector<Entry> out(n);
    for (uint32_t k = 0; k < n; k++) {
        Entry& e = out[k];
        e.rule = r.get<uint8_t>();
        e.ndims = r.get<uint8_t>();
        e.axis = r.get<int8_t>();
        e.head = r.get<int8_t>();
        e.nperm = r.get<uint8_t>();
        for (int i = 0; i < 4; i++) e.perm[i] = r.get<uint8_t>();
        for (int i = 0; i < 4; i++) e.dims[i] = r.get<uint32_t>();
        e.in_scale = r.get<float>();
        e.binvar = r.s();
        e.source = r.s();
        e.weight = r.s();
    }
    return out;
}

// --------------------------------------------------------------- pack ----

constexpr size_t ALIGN = 64;

struct PackEntry {
    std::string name;
    std::vector<std::pair<float, int32_t>> pairs;
    uint64_t off = 0, len = 0;
};

// Reads only the directory; template payloads are copied straight from the
// mapped file when writing, never held in memory.
std::vector<PackEntry> read_pack_dir(const Mapped& m) {
    if (m.len < 12 || memcmp(m.p, "TPLPACK1", 8) != 0) die("not a TPLPACK1 pack");
    Reader r{m.p + 8, m.p + m.len};
    uint32_t n = r.get<uint32_t>();
    std::vector<PackEntry> out(n);
    for (uint32_t k = 0; k < n; k++) {
        PackEntry& e = out[k];
        e.name = r.s();
        uint32_t np = r.get<uint32_t>();
        e.pairs.resize(np);
        for (uint32_t i = 0; i < np; i++) {
            float s = r.get<float>();
            int32_t z = r.get<int32_t>();
            e.pairs[i] = {s, z};
        }
        e.off = r.get<uint64_t>();
        e.len = r.get<uint64_t>();
    }
    return out;
}

size_t header_size(const std::vector<PackEntry>& es) {
    size_t h = 12;
    for (const auto& e : es) h += 2 + e.name.size() + 4 + 8 * e.pairs.size() + 16;
    return h;
}

// ---------------------------------------------------------- transforms ----

// ---------------------------------------------------------------- LoRA ----
//
// Merged into the checkpoint weights before quantizing:
//     W' = W + strength * (alpha/rank) * (up @ down)
//
// Runtime LoRA is not an option on this hardware -- marking tensors
// UPDATEABLE_STATIC costs 2.8x inference, and one marked tensor costs as much
// as 768 (LORA-PROBE.md). A merged checkpoint is an ordinary model.
//
// Merging in CHECKPOINT space also sidesteps the per-head attention split: the
// recipe then slices merged to_q/to_k/to_v exactly as it slices a base weight.
//
// ⚠ kohya names SD1.5 LoRAs with **diffusers** block names while the checkpoint
// uses **LDM** ones. The map is built FORWARDS from each checkpoint key, since
// reversing is ambiguous -- "." -> "_" makes `to_out_0`, `ff_net_0_proj` and
// `transformer_blocks_0` indistinguishable from their dotted forms.

struct LoraMod {
    const Safetensors* st;
    std::string down, up;
    float scale;  // strength * alpha / rank
};

std::string underscored(std::string v) {
    for (char& c : v) if (c == '.') c = '_';
    return v;
}

std::string resnet_member(const std::string& member) {
    static const std::pair<const char*, const char*> names[] = {
        {"in_layers.0", "norm1"}, {"in_layers.2", "conv1"},
        {"out_layers.0", "norm2"}, {"out_layers.3", "conv2"},
        {"emb_layers.1", "time_emb_proj"}, {"skip_connection", "conv_shortcut"},
    };
    for (const auto& name : names) if (member == name.first) return name.second;
    return "";
}

std::string attention_member(const std::string& member) {
    if (member == "norm" || member == "proj_in" || member == "proj_out" ||
        member.compare(0, 19, "transformer_blocks.") == 0)
        return underscored(member);
    return "";
}

// SD1.5 and SDXL share two down-path ResNets / three up-path ResNets per
// block. Convert complete paths so a sampler convolution cannot accidentally
// acquire an attention alias. DMD2 adapts ResNets and samplers as well as attention.
std::string diffusers_name(const std::string& body) {
    static const std::pair<const char*, const char*> direct[] = {
        {"input_blocks.0.0", "conv_in"}, {"out.0", "conv_norm_out"},
        {"out.2", "conv_out"}, {"time_embed.0", "time_embedding_linear_1"},
        {"time_embed.2", "time_embedding_linear_2"},
        {"label_emb.0.0", "add_embedding_linear_1"},
        {"label_emb.0.2", "add_embedding_linear_2"},
    };
    for (const auto& name : direct) if (body == name.first) return name.second;

    int block = -1, consumed = 0;
    if (sscanf(body.c_str(), "input_blocks.%d.%n", &block, &consumed) == 1 &&
        consumed > 0 && block > 0 && block <= 11) {
        const std::string rest = body.substr((size_t)consumed);
        const std::string prefix = "down_blocks_" + std::to_string((block - 1) / 3);
        if (block % 3 == 0)
            return rest == "0.op" ? prefix + "_downsamplers_0_conv" : "";
        if (rest.compare(0, 2, "0.") == 0) {
            const std::string member = resnet_member(rest.substr(2));
            if (!member.empty()) return prefix + "_resnets_" + std::to_string((block - 1) % 3) + "_" + member;
        }
        if (rest.compare(0, 2, "1.") == 0) {
            const std::string member = attention_member(rest.substr(2));
            if (!member.empty()) return prefix + "_attentions_" + std::to_string((block - 1) % 3) + "_" + member;
        }
    }
    consumed = 0;
    if (sscanf(body.c_str(), "output_blocks.%d.%n", &block, &consumed) == 1 &&
        consumed > 0 && block >= 0 && block <= 11) {
        const std::string rest = body.substr((size_t)consumed);
        const std::string prefix = "up_blocks_" + std::to_string(block / 3);
        // SD1.5's first upsampler is .1.conv; SDXL's is .2.conv.
        if ((block == 2 || block == 5 || block == 8) && (rest == "1.conv" || rest == "2.conv"))
            return prefix + "_upsamplers_0_conv";
        if (rest.compare(0, 2, "0.") == 0) {
            const std::string member = resnet_member(rest.substr(2));
            if (!member.empty()) return prefix + "_resnets_" + std::to_string(block % 3) + "_" + member;
        }
        if (rest.compare(0, 2, "1.") == 0) {
            const std::string member = attention_member(rest.substr(2));
            if (!member.empty()) return prefix + "_attentions_" + std::to_string(block % 3) + "_" + member;
        }
    }
    consumed = 0;
    if (sscanf(body.c_str(), "middle_block.%d.%n", &block, &consumed) == 1 && consumed > 0) {
        const std::string rest = body.substr((size_t)consumed);
        if (block == 0 || block == 2) {
            const std::string member = resnet_member(rest);
            if (!member.empty()) return "mid_block_resnets_" + std::to_string(block / 2) + "_" + member;
        }
        if (block == 1) {
            const std::string member = attention_member(rest);
            if (!member.empty()) return "mid_block_attentions_0_" + member;
        }
    }
    return "";
}

// For one checkpoint key, every kohya module name that could refer to it.
std::vector<std::string> kohya_names(const std::string& ldm_key) {
    std::vector<std::string> out;
    const std::string P = "model.diffusion_model.", S = ".weight";
    if (ldm_key.compare(0, P.size(), P) != 0) return out;
    if (ldm_key.size() < S.size() || ldm_key.compare(ldm_key.size() - S.size(), S.size(), S) != 0) return out;
    std::string body = ldm_key.substr(P.size(), ldm_key.size() - P.size() - S.size());
    out.push_back("lora_unet_" + underscored(body));
    std::string name = diffusers_name(body);
    if (!name.empty()) out.push_back("lora_unet_" + name);
    return out;
}

struct LoraSet {
    // SDXL's full transformer weights occupy over 8 GiB in float32. Keep only
    // recent merged tensors: adjacent attention heads reuse q/k/v, while a
    // tensor encountered again in pass 2 can be recomputed with identical math.
    explicit LoraSet(size_t cache_limit_bytes = 128 * 1024 * 1024)
        : cache_limit_bytes(cache_limit_bytes) {}

    std::vector<std::unique_ptr<Safetensors>> files;
    std::unordered_map<std::string, std::vector<LoraMod>> byKey;  // LDM key -> mods

    // `spec` is "path:strength" (strength optional, default 1.0).
    void add(const std::string& spec, const std::vector<std::string>& all_sources) {
        size_t colon = spec.rfind(':');
        float strength = 1.0f;
        std::string path = spec;
        // A Windows-style "C:\..." is not a strength separator.
        if (colon != std::string::npos && colon > 1) {
            try {
                strength = std::stof(spec.substr(colon + 1));
                path = spec.substr(0, colon);
            } catch (...) { path = spec; }
        }
        if (!std::isfinite(strength)) die("%s: LoRA strength must be finite", path.c_str());
        auto st = std::make_unique<Safetensors>();
        st->load(path.c_str());
        int matched = 0;
        std::unordered_set<std::string> used;
        for (const std::string& key : all_sources) {
            for (const std::string& kn : kohya_names(key)) {
                auto d = st->tensors.find(kn + ".lora_down.weight");
                auto u = st->tensors.find(kn + ".lora_up.weight");
                if (d == st->tensors.end() || u == st->tensors.end()) continue;
                const auto& ds = d->second.shape;
                const auto& us = u->second.shape;
                if ((ds.size() != 2 && ds.size() != 4) || (us.size() != 2 && us.size() != 4) ||
                    std::any_of(ds.begin(), ds.end(), [](int64_t n) { return n <= 0; }) ||
                    std::any_of(us.begin(), us.end(), [](int64_t n) { return n <= 0; }) || ds[0] != us[1])
                    die("%s: invalid LoRA down/up shapes or rank", kn.c_str());
                if (us.size() == 4 && (us[2] != 1 || us[3] != 1))
                    die("%s: spatial LoRA up kernels are unsupported", kn.c_str());
                size_t rank = (size_t)d->second.shape[0];
                float alpha = (float)rank;
                auto a = st->tensors.find(kn + ".alpha");
                if (a != st->tensors.end()) {
                    std::vector<int64_t> sh;
                    std::vector<float> av = st->get_f32(kn + ".alpha", sh);
                    if (av.size() != 1 || !std::isfinite(av[0]))
                        die("%s: LoRA alpha must be one finite value", kn.c_str());
                    alpha = av[0];
                    used.insert(kn + ".alpha");
                }
                byKey[key].push_back({st.get(), kn + ".lora_down.weight",
                                      kn + ".lora_up.weight", strength * alpha / (float)rank});
                used.insert(kn + ".lora_down.weight");
                used.insert(kn + ".lora_up.weight");
                matched++;
                break;
            }
        }
        int text_modules = 0;
        std::vector<std::string> unmatched;
        for (const auto& tensor : st->tensors) {
            const std::string& name = tensor.first;
            if (name.compare(0, 7, "lora_te") == 0 || name.compare(0, 13, "text_encoder.") == 0 ||
                name.compare(0, 15, "text_encoder_2.") == 0) {
                if (name.find(".lora_down.weight") != std::string::npos ||
                    name.find(".lora_A.weight") != std::string::npos) text_modules++;
                continue;
            }
            if (!used.count(name)) unmatched.push_back(name);
        }
        fprintf(stderr, "lora %s strength %.3f: %d UNet modules matched; "
                        "%d text-encoder modules DROPPED (text-encoder LoRA merging unsupported); %zu unmatched tensors\n",
                path.c_str(), strength, matched, text_modules, unmatched.size());
        if (!unmatched.empty()) {
            std::sort(unmatched.begin(), unmatched.end());
            fprintf(stderr, "warning: %s: ignoring %zu unsupported or unmatched adapter tensors "
                            "(first: '%s'); merging the matched UNet layers\n",
                    path.c_str(), unmatched.size(), unmatched.front().c_str());
        }
        if (matched == 0) die("%s matched no tensors -- wrong LoRA format?", path.c_str());
        files.push_back(std::move(st));
    }

    bool empty() const { return byKey.empty(); }

    /** Adds every adapter's delta to `w` (checkpoint space), then rounds to fp16. */
    void apply(const std::string& key, std::vector<float>& w) const {
        auto it = byKey.find(key);
        if (it == byKey.end()) return;
        // ⚠ Cached: the recipe reads an attention weight once per HEAD, so each
        // q/k/v tensor is fetched repeatedly. Recomputing the rank-R product each
        // time made the merge cost more than the whole rest of the conversion.
        auto c = cache.find(key);
        if (c != cache.end()) {
            cache_order.splice(cache_order.begin(), cache_order, c->second.recent);
            w = c->second.weight;
            return;
        }

        for (const LoraMod& m : it->second) {
            std::vector<int64_t> ds, us;
            std::vector<float> down = m.st->get_f32(m.down, ds);
            std::vector<float> up = m.st->get_f32(m.up, us);
            size_t rank = (size_t)ds[0];
            size_t in = down.size() / rank;      // in * kh * kw, flattened
            size_t out = up.size() / rank;
            if (out * in != w.size())
                die("%s: lora delta %zux%zu does not fit weight %zu", key.c_str(), out, in, w.size());
            // Sum the rank-R product into a ZERO buffer, scale once, then add --
            // the same association as `W + scale * (up @ down)`. Folding the
            // scale into each term changes the float32 rounding.
            std::vector<float> delta(w.size(), 0.0f);
            for (size_t o = 0; o < out; o++) {
                for (size_t r = 0; r < rank; r++) {
                    float a = up[o * rank + r];
                    if (a == 0.0f) continue;
                    const float* drow = &down[r * in];
                    float* dst = &delta[o * in];
                    for (size_t i = 0; i < in; i++) dst[i] += a * drow[i];
                }
            }
            for (size_t k = 0; k < w.size(); k++) w[k] += m.scale * delta[k];
        }
        // Match what a merged checkpoint saved as fp16 would hold.
        for (float& x : w) x = Safetensors::half_to_float(Safetensors::float_to_half(x));
        size_t bytes = w.size() * sizeof(float);
        if (bytes <= cache_limit_bytes) {
            while (cache_bytes > cache_limit_bytes - bytes) {
                auto old = cache.find(cache_order.back());
                cache_bytes -= old->second.weight.size() * sizeof(float);
                cache.erase(old);
                cache_order.pop_back();
            }
            cache_order.push_front(key);
            cache.emplace(key, CachedWeight{w, cache_order.begin()});
            cache_bytes += bytes;
        }
    }

    struct CachedWeight {
        std::vector<float> weight;
        std::list<std::string>::iterator recent;
    };
    const size_t cache_limit_bytes;
    mutable size_t cache_bytes = 0;
    mutable std::list<std::string> cache_order;
    mutable std::unordered_map<std::string, CachedWeight> cache;
};

// tpl_apply.src_of: fp16 -> f32, optional recipe-sized head slice, then a permute with
// trailing singleton padding. Returns data laid out as `dims`.
// Set once in main. A global rather than a parameter only because src_of has
// several call sites and this is a single-threaded CLI.
const LoraSet* g_loras = nullptr;

std::vector<float> src_of(const Safetensors& st, const Entry& e) {
    std::vector<int64_t> shape;
    std::vector<float> v = st.get_f32(e.source, shape);
    // ⚠ BEFORE the head slice and the permute: LoRA is defined in checkpoint
    // space, so merging here lets the recipe split merged q/k/v exactly as
    // it splits any base weight.
    if (g_loras) g_loras->apply(e.source, v);
    if (e.head >= 0) {
        size_t stride = v.size() / (size_t)shape[0];
        size_t d = e.count() / stride;
        std::vector<float> cut(d * stride);
        memcpy(cut.data(), v.data() + (size_t)e.head * d * stride, d * stride * sizeof(float));
        v.swap(cut);
        shape[0] = (int64_t)d;
    }
    // QNN represents VAE 1x1 attention convolutions as matrices. Only trailing
    // singleton axes may be removed; all other shape differences are errors.
    std::vector<int64_t> s = shape;
    while ((int)s.size() > e.ndims && s.back() == 1) s.pop_back();
    while ((int)s.size() < e.ndims) s.push_back(1);
    if ((int)s.size() != e.ndims) die("%s: rank %zu cannot map to %d dims",
                                      e.binvar.c_str(), s.size(), (int)e.ndims);
    if (e.nperm != e.ndims || v.size() != e.count())
        die("%s: source size or permutation rank mismatch", e.binvar.c_str());
    for (int k = 0; k < e.ndims; k++) {
        if (e.perm[k] >= e.ndims || s[e.perm[k]] != e.dims[k])
            die("%s: source shape mismatch on axis %d", e.binvar.c_str(), k);
    }
    // out axis k reads source axis perm[k]  (numpy transpose semantics)
    int64_t sstride[4] = {1, 1, 1, 1};
    for (int i = e.ndims - 1, acc = 1; i >= 0; i--) { sstride[i] = acc; acc *= (int)s[i]; }
    size_t n = e.count();
    std::vector<float> out(n);
    int64_t ostride[4] = {1, 1, 1, 1};
    for (int i = e.ndims - 1, acc = 1; i >= 0; i--) { ostride[i] = acc; acc *= (int)e.dims[i]; }
    for (size_t idx = 0; idx < n; idx++) {
        size_t rem = idx, soff = 0;
        for (int k = 0; k < e.ndims; k++) {
            size_t ik = rem / (size_t)ostride[k];
            rem -= ik * (size_t)ostride[k];
            soff += ik * (size_t)sstride[e.perm[k]];
        }
        out[idx] = v[soff];
    }
    return out;
}


// round half AWAY from zero on a double, then clip
inline double rha(double x, double lo, double hi) {
    double r = (x < 0 ? -1.0 : 1.0) * std::floor(std::fabs(x) + 0.5);
    return r < lo ? lo : (r > hi ? hi : r);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 5) {
        fprintf(stderr,
                "usage: tplconv <recipe.bin> <template.pack> <checkpoint.safetensors> <out.pack>\n"
                "                [--lora <file.safetensors>[:<strength>]] ...\n");
        return 2;
    }
    const char* recipe_path = argv[1];
    const char* tpl_path = argv[2];
    const char* ckpt_path = argv[3];
    const char* out_path = argv[4];
    std::vector<std::string> lora_specs;
    for (int i = 5; i < argc; i++) {
        if (strcmp(argv[i], "--lora") == 0 && i + 1 < argc) lora_specs.push_back(argv[++i]);
        else die("unexpected argument '%s'", argv[i]);
    }

    std::vector<Entry> rec = read_recipe(recipe_path);
    Mapped tplm;
    tplm.open_(tpl_path);
    std::vector<PackEntry> tpl = read_pack_dir(tplm);
    std::unordered_map<std::string, size_t> tpl_idx;
    for (size_t i = 0; i < tpl.size(); i++) tpl_idx[tpl[i].name] = i;
    Safetensors st;
    st.load(ckpt_path);
    fprintf(stderr, "recipe %zu entries, template %zu entries, checkpoint %zu tensors\n",
            rec.size(), tpl.size(), st.tensors.size());

    LoraSet loras;
    if (!lora_specs.empty()) {
        std::vector<std::string> sources;
        for (const Entry& e : rec) if (!e.source.empty()) sources.push_back(e.source);
        std::sort(sources.begin(), sources.end());
        sources.erase(std::unique(sources.begin(), sources.end()), sources.end());
        for (const std::string& spec : lora_specs) loras.add(spec, sources);
        g_loras = &loras;
    }

    // ---- pass 1: every scale, and the payload length of every entry --------
    std::vector<PackEntry> out(rec.size());
    std::unordered_map<std::string, std::vector<float>> wscale;
    // i32_axis_* consume a weight's scales, so they must run after the weights.
    std::vector<size_t> order(rec.size());
    for (size_t i = 0; i < rec.size(); i++) order[i] = i;
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        auto late = [&](size_t i) {
            return rec[i].rule == R_I32_AXIS_BIAS || rec[i].rule == R_I32_AXIS_ZERO;
        };
        return late(a) < late(b);
    });

    for (size_t oi : order) {
        const Entry& e = rec[oi];
        PackEntry& pe = out[oi];
        pe.name = e.binvar;
        size_t n = e.count();
        switch (e.rule) {
            case R_TEMPLATE: {
                auto it = tpl_idx.find(e.binvar);
                if (it == tpl_idx.end()) die("template pack lacks '%s'", e.binvar.c_str());
                pe.pairs = tpl[it->second].pairs;
                pe.len = tpl[it->second].len;
                break;
            }
            case R_FLOAT16:
            case R_FLOAT32:
                pe.len = n * (e.rule == R_FLOAT16 ? 2 : 4);
                break;
            case R_I8_AXIS: {
                std::vector<float> W = src_of(st, e);
                int ax = e.axis;
                size_t chans = e.dims[ax];
                int64_t ostride[4] = {1, 1, 1, 1};
                for (int i = e.ndims - 1, acc = 1; i >= 0; i--) { ostride[i] = acc; acc *= (int)e.dims[i]; }
                std::vector<double> mx(chans, 0.0);
                for (size_t idx = 0; idx < n; idx++) {
                    size_t c = (idx / (size_t)ostride[ax]) % chans;
                    double a = std::fabs((double)W[idx]);
                    if (a > mx[c]) mx[c] = a;
                }
                pe.pairs.resize(chans);
                std::vector<float> s(chans);
                for (size_t c = 0; c < chans; c++) {
                    s[c] = (float)(mx[c] / 127.0);
                    pe.pairs[c] = {s[c], 0};
                }
                wscale[e.binvar] = s;
                pe.len = n;  // int8
                break;
            }
            case R_U8_ASYM: {
                std::vector<float> W = src_of(st, e);
                double lo = 0.0, hi = 0.0;
                for (size_t i = 0; i < n; i++) {
                    double v = (double)W[i];
                    if (v < lo) lo = v;
                    if (v > hi) hi = v;
                }
                float s = (float)((hi - lo) / 255.0);
                // numpy's np.round is half-to-even; nearbyint in the default
                // rounding mode is the same, and lo/s is never exactly .5 in
                // practice -- but match it rather than rely on that.
                int32_t o = (int32_t)std::nearbyint(lo / (double)s);
                pe.pairs = {{s, o}};
                wscale[e.binvar] = {s};
                pe.len = n;  // uint8
                break;
            }
            case R_I32_SCALAR: {
                std::vector<float> B = src_of(st, e);
                double mx = 0.0;
                for (size_t i = 0; i < n; i++) mx = std::max(mx, std::fabs((double)B[i]));
                float s = (float)(mx / 2147483648.0);
                pe.pairs = {{s, 0}};
                pe.len = n * 4;
                break;
            }
            case R_I32_AXIS_BIAS:
            case R_I32_AXIS_ZERO: {
                auto it = wscale.find(e.weight);
                if (it == wscale.end()) die("%s: weight '%s' has no scales",
                                            e.binvar.c_str(), e.weight.c_str());
                const std::vector<float>& ws = it->second;
                pe.pairs.resize(ws.size());
                for (size_t c = 0; c < ws.size(); c++) {
                    float s = (float)((double)e.in_scale * (double)ws[c]);
                    pe.pairs[c] = {s, 0};
                }
                pe.len = n * 4;
                break;
            }
            default:
                die("%s: unknown rule %d", e.binvar.c_str(), (int)e.rule);
        }
    }

    // ---- layout, identical to tpl_pack.write_pack --------------------------
    size_t hsize = header_size(out);
    uint64_t off = (hsize + ALIGN - 1) / ALIGN * ALIGN;
    for (auto& e : out) {
        e.off = off;
        off = (off + e.len + ALIGN - 1) / ALIGN * ALIGN;
    }

    FILE* f = fopen(out_path, "wb");
    if (!f) die("cannot write %s", out_path);
    // header
    {
        std::vector<uint8_t> h;
        auto put = [&](const void* p, size_t n) {
            const uint8_t* b = (const uint8_t*)p;
            h.insert(h.end(), b, b + n);
        };
        put("TPLPACK1", 8);
        uint32_t cnt = (uint32_t)out.size();
        put(&cnt, 4);
        for (const auto& e : out) {
            uint16_t nl = (uint16_t)e.name.size();
            put(&nl, 2);
            put(e.name.data(), nl);
            uint32_t np = (uint32_t)e.pairs.size();
            put(&np, 4);
            for (const auto& pr : e.pairs) {
                put(&pr.first, 4);
                put(&pr.second, 4);
            }
            put(&e.off, 8);
            put(&e.len, 8);
        }
        if (h.size() != hsize) die("header size mismatch: %zu vs %zu", h.size(), hsize);
        fwrite(h.data(), 1, h.size(), f);
    }

    // ---- pass 2: stream the payloads ---------------------------------------
    for (size_t i = 0; i < rec.size(); i++) {
        const Entry& e = rec[i];
        const PackEntry& pe = out[i];
        if (fseeko(f, (off_t)pe.off, SEEK_SET) != 0) die("seek failed");
        size_t n = e.count();
        switch (e.rule) {
            case R_TEMPLATE: {
                const PackEntry& t = tpl[tpl_idx[e.binvar]];
                fwrite(tplm.p + t.off, 1, (size_t)t.len, f);
                break;
            }
            case R_FLOAT16: {
                const std::vector<float> values = src_of(st, e);
                std::vector<uint16_t> halves(n);
                for (size_t k = 0; k < n; k++) {
                    halves[k] = Safetensors::float_to_half(values[k]);
                    if ((halves[k] & 0x7c00) == 0x7c00)
                        die("%s: non-finite or out-of-range FP16 weight", e.source.c_str());
                }
                fwrite(halves.data(), sizeof(uint16_t), n, f);
                break;
            }
            case R_FLOAT32: {
                const std::vector<float> values = src_of(st, e);
                for (float value : values)
                    if (!std::isfinite(value)) die("%s: non-finite FP32 weight", e.source.c_str());
                fwrite(values.data(), sizeof(float), n, f);
                break;
            }
            case R_I8_AXIS: {
                std::vector<float> W = src_of(st, e);
                int ax = e.axis;
                size_t chans = e.dims[ax];
                int64_t ostride[4] = {1, 1, 1, 1};
                for (int k = e.ndims - 1, acc = 1; k >= 0; k--) { ostride[k] = acc; acc *= (int)e.dims[k]; }
                std::vector<double> mx(chans, 0.0);
                for (size_t idx = 0; idx < n; idx++) {
                    size_t c = (idx / (size_t)ostride[ax]) % chans;
                    double a = std::fabs((double)W[idx]);
                    if (a > mx[c]) mx[c] = a;
                }
                std::vector<int8_t> q(n);
                for (size_t idx = 0; idx < n; idx++) {
                    size_t c = (idx / (size_t)ostride[ax]) % chans;
                    q[idx] = (int8_t)rha((double)W[idx] * 127.0 / mx[c], -128.0, 127.0);
                }
                fwrite(q.data(), 1, n, f);
                break;
            }
            case R_U8_ASYM: {
                std::vector<float> W = src_of(st, e);
                double lo = 0.0, hi = 0.0;
                for (size_t k = 0; k < n; k++) {
                    double v = (double)W[k];
                    if (v < lo) lo = v;
                    if (v > hi) hi = v;
                }
                int32_t o = pe.pairs[0].second;
                std::vector<uint8_t> q(n);
                for (size_t k = 0; k < n; k++) {
                    float x = (float)((double)W[k] * 255.0 / (hi - lo) - (double)o);
                    double y = std::floor((double)(x + 0.5f));
                    q[k] = (uint8_t)(y < 0.0 ? 0.0 : (y > 255.0 ? 255.0 : y));
                }
                fwrite(q.data(), 1, n, f);
                break;
            }
            case R_I32_SCALAR: {
                std::vector<float> B = src_of(st, e);
                double s = (double)pe.pairs[0].first;
                std::vector<int32_t> q(n);
                for (size_t k = 0; k < n; k++)
                    q[k] = (int32_t)rha((double)B[k] / s, -2147483648.0, 2147483647.0);
                fwrite(q.data(), 1, n * 4, f);
                break;
            }
            case R_I32_AXIS_ZERO: {
                std::vector<int32_t> q(n, 0);
                fwrite(q.data(), 1, n * 4, f);
                break;
            }
            case R_I32_AXIS_BIAS: {
                std::vector<float> B = src_of(st, e);
                int ax = e.axis;
                size_t chans = e.dims[ax];
                int64_t ostride[4] = {1, 1, 1, 1};
                for (int k = e.ndims - 1, acc = 1; k >= 0; k--) { ostride[k] = acc; acc *= (int)e.dims[k]; }
                std::vector<int32_t> q(n);
                for (size_t idx = 0; idx < n; idx++) {
                    size_t c = (idx / (size_t)ostride[ax]) % chans;
                    double s = (double)pe.pairs[c].first;
                    q[idx] = (int32_t)rha((double)B[idx] / s, -2147483648.0, 2147483647.0);
                }
                fwrite(q.data(), 1, n * 4, f);
                break;
            }
        }
    }
    // write_pack truncates to the aligned end of the last entry
    if (fflush(f) != 0) die("flush failed");
    if (ftruncate(fileno(f), (off_t)off) != 0) die("truncate failed");
    fclose(f);
    fprintf(stderr, "wrote %s (%llu bytes, %zu entries)\n", out_path,
            (unsigned long long)off, out.size());
    return 0;
}
