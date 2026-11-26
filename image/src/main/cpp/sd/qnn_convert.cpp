#include <android/log.h>
#include <fcntl.h>
#include <malloc.h>
#include <sys/mman.h>
#include <unistd.h>

#include <atomic>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "file_backed_buffer.h"
#include "qnn_sd_graphs.h"
#include "safetensor2mnn/SafeTensor2MNN.hpp"
#include "zstd.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "fancyqnnconv", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "fancyqnnconv", __VA_ARGS__)

namespace aura {

std::vector<float> applyQnnLora(
    const std::vector<float>& originalWeights,
    const std::string& weightName,
    const std::vector<SafeTensorReader*>& loras,
    const std::vector<float>& loraStrengths,
    const std::unordered_map<std::string, std::string>& mapping) {
    std::vector<float> out = applyLoRA(originalWeights, weightName, loras, loraStrengths, mapping);
    for (SafeTensorReader* lora : loras) {
        if (lora) lora->clearBuffers();
    }
    return out;
}

}  // namespace aura

namespace {

std::atomic<bool> g_cancelled{false};

extern "C" void handleCancelSignal(int) {
    g_cancelled.store(true);
}

std::unordered_map<std::string, std::string> buildSdxlClipLoraMap(const SafeTensorReader* firstLora) {
    std::unordered_map<std::string, std::string> m;
    const bool useTe1Prefix = firstLora &&
        firstLora->has_tensor("lora_te1_text_model_encoder_layers_0_self_attn_q_proj.lora_down.weight");
    const std::string te1Prefix = useTe1Prefix ? "lora_te1_" : "lora_te_";
    const char* subPairs[][2] = {
        {"self_attn.q_proj", "self_attn_q_proj"},
        {"self_attn.k_proj", "self_attn_k_proj"},
        {"self_attn.v_proj", "self_attn_v_proj"},
        {"self_attn.out_proj", "self_attn_out_proj"},
        {"mlp.fc1", "mlp_fc1"},
        {"mlp.fc2", "mlp_fc2"},
    };
    for (int l = 0; l < 12; ++l) {
        const std::string ls = std::to_string(l);
        for (const auto& pair : subPairs) {
            m["conditioner.embedders.0.transformer.text_model.encoder.layers." + ls + "." + pair[0] + ".weight"] =
                te1Prefix + "text_model_encoder_layers_" + ls + "_" + pair[1];
        }
    }
    const char* clip2Pairs[][2] = {
        {"attn.q_proj", "self_attn_q_proj"},
        {"attn.k_proj", "self_attn_k_proj"},
        {"attn.v_proj", "self_attn_v_proj"},
        {"attn.out_proj", "self_attn_out_proj"},
        {"mlp.c_fc", "mlp_fc1"},
        {"mlp.c_proj", "mlp_fc2"},
    };
    for (int l = 0; l < 32; ++l) {
        const std::string ls = std::to_string(l);
        for (const auto& pair : clip2Pairs) {
            m["conditioner.embedders.1.model.transformer.resblocks." + ls + "." + pair[0] + ".weight"] =
                "lora_te2_text_model_encoder_layers_" + ls + "_" + pair[1];
        }
    }
    return m;
}

bool writeZstdPatchFromDictFile(
    const std::string& dictPath,
    const void* srcPtr,
    size_t srcSize,
    const std::string& patchPath,
    const std::string& backingDir,
    std::string& errorOut) {
    const int fd = open(dictPath.c_str(), O_RDONLY);
    if (fd < 0) {
        errorOut = "Cannot open base unet.bin for patch diff";
        return false;
    }
    const off_t dsz = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    if (dsz <= 0) {
        close(fd);
        errorOut = "Empty base unet.bin for patch diff";
        return false;
    }
    void* dictMap = mmap(nullptr, static_cast<size_t>(dsz), PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (dictMap == MAP_FAILED) {
        errorOut = "mmap failed for base unet.bin";
        return false;
    }
    madvise(dictMap, static_cast<size_t>(dsz), MADV_SEQUENTIAL);

    ZSTD_CCtx* cctx = ZSTD_createCCtx();
    if (!cctx) {
        munmap(dictMap, static_cast<size_t>(dsz));
        errorOut = "Failed allocating ZSTD_CCtx";
        return false;
    }
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_compressionLevel, 1);
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_enableLongDistanceMatching, 1);
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_windowLog, 31);
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_ldmHashLog, 23);
    ZSTD_CCtx_setPledgedSrcSize(cctx, static_cast<unsigned long long>(srcSize));

    const size_t rc = ZSTD_CCtx_refPrefix(cctx, dictMap, static_cast<size_t>(dsz));
    if (ZSTD_isError(rc)) {
        ZSTD_freeCCtx(cctx);
        munmap(dictMap, static_cast<size_t>(dsz));
        errorOut = std::string("ZSTD_CCtx_refPrefix failed: ") + ZSTD_getErrorName(rc);
        return false;
    }

    const size_t maxDstSize = ZSTD_compressBound(srcSize);
    aura::FileBackedBuffer outBuf;
    if (!outBuf.resize(maxDstSize, backingDir)) {
        ZSTD_freeCCtx(cctx);
        munmap(dictMap, static_cast<size_t>(dsz));
        errorOut = "Failed allocating ZSTD patch buffer";
        return false;
    }

    const size_t compressed = ZSTD_compress2(cctx, outBuf.data(), maxDstSize, srcPtr, srcSize);
    ZSTD_freeCCtx(cctx);
    munmap(dictMap, static_cast<size_t>(dsz));
    if (ZSTD_isError(compressed)) {
        errorOut = std::string("ZSTD_compress2 failed: ") + ZSTD_getErrorName(compressed);
        return false;
    }

    std::ofstream pf(patchPath, std::ios::binary | std::ios::trunc);
    if (!pf) {
        errorOut = "Cannot open patch output file: " + patchPath;
        return false;
    }
    pf.write(reinterpret_cast<const char*>(outBuf.data()), static_cast<std::streamsize>(compressed));
    pf.flush();
    if (!pf) {
        errorOut = "Failed writing patch file: " + patchPath;
        return false;
    }
    LOGI("Wrote %s (%zu bytes from %zu bytes)", patchPath.c_str(), compressed, srcSize);
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    std::signal(SIGTERM, handleCancelSignal);
    std::signal(SIGINT, handleCancelSignal);

    auto errStr = [](const std::string& msg) -> int {
        LOGE("QNN conversion failed: %s", msg.c_str());
        std::printf("ERROR|%s\n", msg.c_str());
        std::fflush(stdout);
        return 1;
    };

    if (argc < 6) {
        return errStr("Invalid QNN compiler arguments.");
    }

    const std::string dir = argv[1];
    const std::string checkpoint = argv[2];
    const std::string skelDir = argv[3];
    const bool isSdxl = std::string(argv[4]) == "1";
    const bool clipSkip2 = std::string(argv[5]) == "1";

    auto notify = [](int pct, const std::string& msg) {
        LOGI("[%d%%] %s", pct, msg.c_str());
        std::printf("PROGRESS|%d|%s\n", pct, msg.c_str());
        std::fflush(stdout);
    };

    std::vector<std::pair<std::string, float>> loraEntries;
    for (int i = 6; i + 1 < argc; i += 2) {
        const std::string loraName = argv[i];
        const float strength = std::strtof(argv[i + 1], nullptr);
        if (!loraName.empty() && strength != 0.0f) {
            loraEntries.emplace_back(loraName, strength);
        }
    }

    try {
        notify(2, "Reading checkpoint & LoRAs…");
        SafeTensorReader reader(dir + "/" + checkpoint);
        std::vector<std::unique_ptr<SafeTensorReader>> ownedLoras;
        std::vector<SafeTensorReader*> loraPtrs;
        std::vector<float> strengths;
        for (const auto& entry : loraEntries) {
            ownedLoras.push_back(std::make_unique<SafeTensorReader>(dir + "/" + entry.first));
            loraPtrs.push_back(ownedLoras.back().get());
            strengths.push_back(entry.second);
        }

        if (g_cancelled.load()) return errStr("Conversion cancelled.");

        if (reader.has_tensor("v_pred")) {
            std::ofstream vp(dir + "/V_PRED", std::ios::trunc);
        }

        if (isSdxl) {
            notify(6, "Converting SDXL CLIP-1 & CLIP-2 (MNN)…");
            const auto clipLoraMap = buildSdxlClipLoraMap(loraPtrs.empty() ? nullptr : loraPtrs.front());
            if (!aura::generateSdxlModel(dir, checkpoint, "clip", sdxl_clip1_structure, loraPtrs, strengths, clipLoraMap) ||
                !aura::generateSdxlModel(dir, checkpoint, "clip_2", sdxl_clip2_structure, loraPtrs, strengths, clipLoraMap)) {
                return errStr("Failed converting SDXL CLIP encoders.");
            }
            if (reader.has_tensor("conditioner.embedders.0.transformer.text_model.embeddings.position_embedding.weight")) {
                reader.read("conditioner.embedders.0.transformer.text_model.embeddings.position_embedding.weight", true);
                std::ofstream f(dir + "/pos_emb.bin", std::ios::binary | std::ios::trunc);
                f.write(reinterpret_cast<const char*>(reader.data.data()),
                        static_cast<std::streamsize>(reader.data.size() * sizeof(float)));
                if (!f) return errStr("Failed writing pos_emb.bin");
            }
            if (reader.has_tensor("conditioner.embedders.0.transformer.text_model.embeddings.token_embedding.weight")) {
                reader.read("conditioner.embedders.0.transformer.text_model.embeddings.token_embedding.weight", true);
                std::ofstream f(dir + "/token_emb.bin", std::ios::binary | std::ios::trunc);
                f.write(reinterpret_cast<const char*>(reader.fp16_data.data()),
                        static_cast<std::streamsize>(reader.fp16_data.size() * sizeof(uint16_t)));
                if (!f) return errStr("Failed writing token_emb.bin");
            }
            if (reader.has_tensor("conditioner.embedders.1.model.positional_embedding")) {
                reader.read("conditioner.embedders.1.model.positional_embedding", true);
                std::ofstream f(dir + "/pos_emb_2.bin", std::ios::binary | std::ios::trunc);
                f.write(reinterpret_cast<const char*>(reader.data.data()),
                        static_cast<std::streamsize>(reader.data.size() * sizeof(float)));
                if (!f) return errStr("Failed writing pos_emb_2.bin");
            }
            if (reader.has_tensor("conditioner.embedders.1.model.token_embedding.weight")) {
                reader.read("conditioner.embedders.1.model.token_embedding.weight", true);
                std::ofstream f(dir + "/token_emb_2.bin", std::ios::binary | std::ios::trunc);
                f.write(reinterpret_cast<const char*>(reader.fp16_data.data()),
                        static_cast<std::streamsize>(reader.fp16_data.size() * sizeof(uint16_t)));
                if (!f) return errStr("Failed writing token_emb_2.bin");
            }
        } else {
            notify(6, "Converting SD 1.5 CLIP (MNN)…");
            if (!aura::generateClipModel(dir, checkpoint, clipSkip2, loraPtrs, strengths)) {
                return errStr("Failed converting SD 1.5 CLIP encoder.");
            }
        }
        reader.clearBuffers();
        mallopt(M_PURGE_ALL, 0);

        if (g_cancelled.load()) return errStr("Conversion cancelled.");

        notify(12, "Initializing QNN HTP compiler…");
        aura::QnnBuilderBackend backend;
        std::string errorOut;
        if (!backend.init(skelDir, errorOut)) {
            return errStr(errorOut);
        }

        if (isSdxl) {
            notify(16, "Compiling SDXL UNet (231 tokens masked, W8A16 HTP)…");
            if (!aura::buildAndSaveSdxlUnet(
                    backend, reader, loraPtrs, strengths, dir + "/unet.bin", g_cancelled, errorOut)) {
                return errStr(errorOut);
            }

            { std::ofstream m(dir + "/SDXL", std::ios::trunc); }
            { std::ofstream c(dir + "/qnn_context.txt", std::ios::trunc); c << "231_masked_v1\n"; }
            { std::ofstream t(dir + "/vae_tile_size.txt", std::ios::trunc); t << 1024 << "\n"; }
        } else {
            notify(16, "Compiling SD 1.5 UNet (512x512, 231 tokens masked, W8A16 HTP)…");
            if (!aura::buildAndSaveSd15Unet(
                    backend, reader, loraPtrs, strengths, 512, 512, dir, nullptr, dir + "/unet.bin", g_cancelled, errorOut)) {
                return errStr(errorOut);
            }

            { std::ofstream c(dir + "/qnn_context.txt", std::ios::trunc); c << "231_masked_v1\n"; }

            notify(42, "Compiling SD 1.5 UNet portrait patch (512x768)…");
            {
                aura::FileBackedBuffer portraitBin;
                if (!aura::buildAndSaveSd15Unet(
                        backend, reader, loraPtrs, strengths, 512, 768, dir, &portraitBin, "", g_cancelled, errorOut) ||
                    !writeZstdPatchFromDictFile(
                        dir + "/unet.bin", portraitBin.data(), portraitBin.size(), dir + "/512x768.patch", dir, errorOut)) {
                    return errStr(errorOut);
                }
            }

            notify(68, "Compiling SD 1.5 VAE Decoder (512x512 W8A16 HTP)…");
            if (!aura::buildAndSaveVaeDecoder(backend, reader, 64, 64, false, dir + "/vae_decoder.bin", g_cancelled, errorOut)) {
                return errStr(errorOut);
            }

            notify(84, "Compiling SD 1.5 VAE Encoder (512x512 W8A16 HTP)…");
            if (!aura::buildAndSaveVaeEncoder(backend, reader, 512, 512, false, dir + "/vae_encoder.bin", g_cancelled, errorOut)) {
                return errStr(errorOut);
            }
        }

        notify(100, "QNN compilation complete.");
        std::printf("DONE\n");
        std::fflush(stdout);
        return 0;
    } catch (const std::exception& failure) {
        return errStr(failure.what());
    } catch (...) {
        return errStr("Unknown native QNN conversion error.");
    }
}
