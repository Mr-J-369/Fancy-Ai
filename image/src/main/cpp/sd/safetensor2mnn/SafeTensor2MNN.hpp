
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "FloatConversion.hpp"
#include "LoraMapping.hpp"
#include "SDStructure.hpp"
#include "SafeTensorReader.hpp"

namespace aura {

struct Shape {
  std::vector<int> dims;
  Shape(const std::string &shape_str) {
    if (shape_str.empty()) return;
    size_t pos = 0, next = 0;
    while ((next = shape_str.find('x', pos)) != std::string::npos) {
      dims.push_back(std::stoi(shape_str.substr(pos, next - pos)));
      pos = next + 1;
    }
    dims.push_back(std::stoi(shape_str.substr(pos)));
  }
};

inline std::vector<uint8_t> fillBuffer(const std::vector<uint32_t> &values, int need_bits) {
  if (need_bits == 8) {
    std::vector<uint8_t> result(values.size());
    for (size_t i = 0; i < values.size(); ++i) result[i] = static_cast<uint8_t>(values[i]);
    return result;
  }
  int total_bits = (int) values.size() * need_bits;
  int buf_len = (total_bits + 7) / 8;
  std::vector<uint8_t> buffer(buf_len, 0);
  uint32_t mask = (1U << need_bits) - 1;
  int bit_offset = 0;
  for (uint32_t value : values) {
    value &= mask;
    int byte_pos = bit_offset / 8;
    int bit_pos_in_byte = bit_offset % 8;
    int bits_in_current_byte = 8 - bit_pos_in_byte;
    if (need_bits <= bits_in_current_byte) {
      int shift = bits_in_current_byte - need_bits;
      buffer[byte_pos] |= static_cast<uint8_t>(value << shift);
    } else {
      uint32_t high_bits = value >> (need_bits - bits_in_current_byte);
      buffer[byte_pos] |= static_cast<uint8_t>(high_bits);
      int remaining_bits = need_bits - bits_in_current_byte;
      uint32_t low_bits = value & ((1U << remaining_bits) - 1);
      int shift = 8 - remaining_bits;
      buffer[byte_pos + 1] |= static_cast<uint8_t>(low_bits << shift);
    }
    bit_offset += need_bits;
  }
  return buffer;
}

inline std::vector<uint8_t> quantizeWeights(const std::vector<float> &weights, const Shape &shape) {
  if (shape.dims.size() != 4) return {};
  int oc = shape.dims[0], ic = shape.dims[1], h = shape.dims[2], w = shape.dims[3];
  int kxky = h * w;
  int kernel_size = ic * kxky;
  int block_size = 32;
  float threshold = 127.0f;
  int block_num = 1;
  int actual_block_size = kernel_size;
  if (block_size > 0 && (ic % block_size == 0) && block_size >= 16 && (block_size % 16 == 0)) {
    block_num = ic / block_size;
    actual_block_size = block_size * kxky;
  }
  std::vector<float> scales(oc * block_num);
  for (int k = 0; k < oc; ++k) {
    for (int b = 0; b < block_num; ++b) {
      int begin_index = b * actual_block_size;
      int end_index = begin_index + actual_block_size;
      float abs_max = 0.0f;
      for (int idx = begin_index; idx < end_index; ++idx)
        abs_max = std::max(abs_max, std::abs(weights[k * kernel_size + idx]));
      scales[k * block_num + b] = abs_max / threshold;
    }
  }
  int offset = 128, min_value = -128, max_value = 127, value_count = 256, need_bits = 8;
  std::vector<uint32_t> indices;
  indices.reserve(weights.size());
  for (int k = 0; k < oc; ++k) {
    for (int b = 0; b < block_num; ++b) {
      int begin_index = b * actual_block_size;
      int end_index = begin_index + actual_block_size;
      float scale = scales[k * block_num + b];
      for (int idx = begin_index; idx < end_index; ++idx) {
        float ratio = (scale > 1e-6f) ? weights[k * kernel_size + idx] / scale : 0.0f;
        int value = static_cast<int>(std::round(ratio));
        value = std::max(min_value, std::min(max_value, value));
        indices.push_back(static_cast<uint32_t>(value + offset));
      }
    }
  }
  std::vector<uint8_t> result;
  std::vector<int> blob_dims = {oc * block_num, actual_block_size};
  result.push_back(static_cast<uint8_t>(blob_dims.size()));
  bool use_int32 = std::any_of(blob_dims.begin(), blob_dims.end(), [](int d) { return d > 65535; });
  if (use_int32) {
    for (int dim : blob_dims) {
      auto d = static_cast<uint32_t>(dim);
      const auto *bytes = reinterpret_cast<const uint8_t *>(&d);
      result.insert(result.end(), bytes, bytes + 4);
    }
  } else {
    for (int dim : blob_dims) {
      auto d = static_cast<uint16_t>(dim);
      const auto *bytes = reinterpret_cast<const uint8_t *>(&d);
      result.insert(result.end(), bytes, bytes + 2);
    }
  }
  result.push_back(static_cast<uint8_t>(value_count));
  for (int value = min_value; value <= max_value; ++value) result.push_back(static_cast<uint8_t>(value));
  std::vector<uint8_t> compressed = fillBuffer(indices, need_bits);
  result.insert(result.end(), compressed.begin(), compressed.end());
  const auto *scale_bytes = reinterpret_cast<const uint8_t *>(scales.data());
  result.insert(result.end(), scale_bytes, scale_bytes + scales.size() * sizeof(float));
  return result;
}

inline std::vector<float> applyLoRA(const std::vector<float> &original_weights,
                                    const std::string &weight_name,
                                    const std::vector<SafeTensorReader *> &lora_readers,
                                    const std::vector<float> &lora_weights = {}) {
  if (lora_readers.empty()) return original_weights;
  auto it = lora_mapping.find(weight_name);
  if (it == lora_mapping.end()) return original_weights;
  const std::string &lora_key = it->second;
  std::vector<float> weights = original_weights;
  for (size_t idx = 0; idx < lora_readers.size(); ++idx) {
    SafeTensorReader *lora = lora_readers[idx];
    const std::string down_key = lora_key + ".lora_down.weight";
    const std::string up_key = lora_key + ".lora_up.weight";
    if (!lora->has_tensor(down_key) || !lora->has_tensor(up_key)) continue;
    lora->read(down_key);
    std::vector<float> down = lora->data;
    lora->read(up_key);
    std::vector<float> up = lora->data;
    if (down.empty() || up.empty()) continue;
    const size_t total = weights.size();
    const auto rank = static_cast<size_t>(std::lround(std::sqrt(
        static_cast<double>(down.size()) / (static_cast<double>(total) / static_cast<double>(up.size())))));
    if (rank == 0 || up.size() % rank != 0 || down.size() % rank != 0) continue;
    const size_t out_features = up.size() / rank;
    const size_t in_features = down.size() / rank;
    if (out_features * in_features != total) continue;
    // kohya convention: absent alpha means alpha == rank, i.e. the delta lands unscaled.
    auto alpha = static_cast<float>(rank);
    if (lora->has_tensor(lora_key + ".alpha")) {
      lora->read(lora_key + ".alpha");
      if (!lora->data.empty()) alpha = lora->data[0];
    }
    const float strength = (idx < lora_weights.size()) ? lora_weights[idx] : 1.0f;
    const float scale = strength * alpha / static_cast<float>(rank);
    for (size_t o = 0; o < out_features; ++o) {
      float *w = weights.data() + o * in_features;
      for (size_t r = 0; r < rank; ++r) {
        const float u = up[o * rank + r] * scale;
        if (u == 0.0f) continue;
        const float *d = down.data() + r * in_features;
        for (size_t i = 0; i < in_features; ++i) w[i] += u * d[i];
      }
    }
  }
  return weights;
}

inline bool generateModel(const std::string &dir, const std::string &safetensor_file,
                          const std::string &model_name,
                          const std::vector<std::vector<std::string>> &structure,
                          const std::vector<SafeTensorReader *> &loras = {},
                          const std::vector<float> &lora_strengths = {}) {
  SafeTensorReader reader(dir + "/" + safetensor_file);
  std::ofstream weight_file(dir + "/model.mnn.weight", std::ios::binary);
  if (!weight_file) return false;
  for (const auto &weight_info : structure) {
    const std::string &weight_name = weight_info[0];
    const std::string &data_type = weight_info[1];
    if (data_type == "fp32") {
      reader.read(weight_name);
      std::vector<float> w = applyLoRA(reader.data, weight_name, loras, lora_strengths);
      weight_file.write(reinterpret_cast<const char *>(w.data()),
                        static_cast<std::streamsize>(w.size() * sizeof(float)));
    } else if (data_type == "fp16") {
      reader.read(weight_name);
      std::vector<float> w = applyLoRA(reader.data, weight_name, loras, lora_strengths);
      std::vector<uint16_t> fp16(w.size());
      for (size_t i = 0; i < w.size(); ++i) fp16[i] = fp32_to_fp16(w[i]);
      weight_file.write(reinterpret_cast<const char *>(fp16.data()),
                        static_cast<std::streamsize>(fp16.size() * sizeof(uint16_t)));
    } else if (data_type == "const") {
      int zero_length = std::stoi(weight_info[2]);
      std::vector<float> w(zero_length, 0.0f);
      weight_file.write(reinterpret_cast<const char *>(w.data()),
                        static_cast<std::streamsize>(w.size() * sizeof(float)));
    } else if (data_type == "block_quant") {
      reader.read(weight_name);
      std::vector<float> w = applyLoRA(reader.data, weight_name, loras, lora_strengths);
      Shape shape(weight_info[2]);
      auto quantized = quantizeWeights(w, shape);
      weight_file.write(reinterpret_cast<const char *>(quantized.data()),
                        static_cast<std::streamsize>(quantized.size()));
    }
    if (!weight_file) return false;
  }
  weight_file.flush();
  if (!weight_file) return false;
  weight_file.close();
  return std::rename(
      (dir + "/model.mnn.weight").c_str(),
      (dir + "/" + model_name + ".mnn.weight").c_str()) == 0;
}

inline bool patchModel(const std::string &dir, const std::string &safetensor_file,
                       const std::string &model_name,
                       const std::unordered_map<std::string, int> &small_weights, bool fp16 = false) {
  std::fstream mnn_file(dir + "/" + model_name + ".mnn",
                        std::ios::in | std::ios::out | std::ios::binary);
  if (!mnn_file) return false;
  mnn_file.seekg(0, std::ios::end);
  const std::streamoff model_size = mnn_file.tellg();
  if (model_size <= 0) return false;
  SafeTensorReader reader(dir + "/" + safetensor_file);
  for (const auto &pair : small_weights) {
    const std::string &weight_name = pair.first;
    int offset = pair.second;
    reader.read(weight_name, !fp16);
    int data_size_bytes = fp16 ? (int) reader.fp16_data.size() * (int) sizeof(uint16_t)
                               : (int) reader.data.size() * (int) sizeof(float);
    if (offset < 0 || data_size_bytes < 0 ||
        static_cast<std::streamoff>(offset) + data_size_bytes > model_size) {
      return false;
    }
    mnn_file.clear();
    mnn_file.seekp(offset, std::ios::beg);
    if (!mnn_file) return false;
    if (fp16)
      mnn_file.write(reinterpret_cast<const char *>(reader.fp16_data.data()), data_size_bytes);
    else
      mnn_file.write(reinterpret_cast<const char *>(reader.data.data()), data_size_bytes);
    if (!mnn_file) return false;
  }
  mnn_file.flush();
  const bool written = static_cast<bool>(mnn_file);
  mnn_file.close();
  return written;
}

inline bool generateClipModel(const std::string &dir, const std::string &safetensor_file,
                              bool clip_skip_2,
                              const std::vector<SafeTensorReader *> &loras = {},
                              const std::vector<float> &lora_strengths = {}) {
  if (!generateModel(dir, safetensor_file, "clip_v2",
                     clip_skip_2 ? clip_skip_2_structure : clip_structure, loras, lora_strengths))
    return false;
  SafeTensorReader reader(dir + "/" + safetensor_file);
  reader.read("cond_stage_model.transformer.text_model.embeddings.position_embedding.weight", true);
  { std::ofstream f(dir + "/pos_emb.bin", std::ios::binary | std::ios::trunc);
    if (!f) return false;
    f.write(reinterpret_cast<const char *>(reader.data.data()),
            static_cast<std::streamsize>(reader.data.size() * sizeof(float)));
    if (!f) return false; }
  reader.read("cond_stage_model.transformer.text_model.embeddings.token_embedding.weight", true);
  { std::ofstream f(dir + "/token_emb.bin", std::ios::binary | std::ios::trunc);
    if (!f) return false;
    f.write(reinterpret_cast<const char *>(reader.fp16_data.data()),
            static_cast<std::streamsize>(reader.fp16_data.size() * sizeof(uint16_t)));
    if (!f) return false; }
  return true;
}

inline bool generateMNNModels(const std::string &dir, const std::string &safetensor_file,
                              bool clip_skip_2,
                              const std::vector<std::pair<std::string, float>> &lora_files = {}) {
  std::vector<std::unique_ptr<SafeTensorReader>> owned;
  std::vector<SafeTensorReader *> loras;
  std::vector<float> lora_strengths;
  for (const auto &lora : lora_files) {
    owned.push_back(std::make_unique<SafeTensorReader>(dir + "/" + lora.first));
    loras.push_back(owned.back().get());
    lora_strengths.push_back(lora.second);
  }
  if (!generateClipModel(dir, safetensor_file, clip_skip_2, loras, lora_strengths)) return false;
  if (!generateModel(dir, safetensor_file, "unet", unet_structure, loras, lora_strengths)) return false;
  if (!patchModel(dir, safetensor_file, "unet", unet_small_weights)) return false;
  if (!generateModel(dir, safetensor_file, "vae_decoder", vae_decoder_structure)) return false;
  if (!patchModel(dir, safetensor_file, "vae_decoder", vae_decoder_small_weights, true)) return false;
  if (!generateModel(dir, safetensor_file, "vae_encoder", vae_encoder_structure)) return false;
  return patchModel(dir, safetensor_file, "vae_encoder", vae_encoder_small_weights, true);
}

}  // namespace aura
