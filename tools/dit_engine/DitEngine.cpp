// libdit_engine.so: stable-diffusion.cpp behind the narrow DitEngine ABI.
//
// Everything specific to stable-diffusion.cpp and ggml stays on this side of
// the boundary, so the core never includes their headers and never links their
// objects. See include/DitEngine.h for the contract.

#include "DitEngine.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "ggml-backend.h"
#include "stable-diffusion.h"

namespace {

// stable-diffusion.cpp installs progress, preview and log callbacks globally
// rather than per context, so the engine routes them through the generation
// that is currently running. Generations are serialized by g_gen_mutex, which
// makes "currently running" unambiguous.
std::mutex g_gen_mutex;

struct ActiveGeneration {
  dit_progress_cb progress = nullptr;
  dit_preview_cb preview = nullptr;
  void *user_data = nullptr;
  dit_ctx *ctx = nullptr;
  bool cancelled = false;
  bool sampling = true;
  int sampling_steps = 0;
};

ActiveGeneration g_active;

dit_log_cb g_log_cb = nullptr;
void *g_log_user_data = nullptr;

std::string g_create_error;

bool image_pixel_count(const sd_image_t &image, size_t *pixel_count) {
  if (!pixel_count || image.width == 0 || image.height == 0) return false;
  const size_t width = static_cast<size_t>(image.width);
  const size_t height = static_cast<size_t>(image.height);
  if (height > std::numeric_limits<size_t>::max() / width) return false;
  const size_t pixels = width * height;
  if (pixels > std::numeric_limits<size_t>::max() / 4) return false;
  *pixel_count = pixels;
  return true;
}

// Composite an RGBA buffer over an opaque white background into an RGB buffer.
// Qwen Image 2.1 produces 4-channel images whose alpha channel represents
// output transparency; Android/Flutter callers that ask for RGB expect that
// alpha to be blended rather than dropped.
void image_to_rgb(const sd_image_t &image, size_t pixel_count, uint8_t *rgb) {
  if (image.channel == 3) {
    std::memcpy(rgb, image.data, pixel_count * 3);
    return;
  }

  for (size_t i = 0; i < pixel_count; ++i) {
    const uint8_t *rgba = image.data + i * 4;
    uint8_t *dst = rgb + i * 3;
    const unsigned alpha = rgba[3];
    for (size_t channel = 0; channel < 3; ++channel) {
      dst[channel] = static_cast<uint8_t>(
          (static_cast<unsigned>(rgba[channel]) * alpha +
           255u * (255u - alpha) + 127u) /
          255u);
    }
  }
}

}  // namespace

struct dit_ctx {
  sd_ctx_t *sd = nullptr;
  dit_model_kind kind = DIT_MODEL_Z_IMAGE;
  std::string last_error;
  int preview_interval = 0;
  bool has_taesd = false;
};

namespace {

void forward_log(enum sd_log_level_t level, const char *text, void *) {
  if (g_log_cb) g_log_cb(static_cast<int>(level), text ? text : "", g_log_user_data);
}

void forward_progress(int step, int steps, float time, void *) {
  if (!g_active.progress) return;
  const int routed_steps = (steps > 0) ? steps : g_active.sampling_steps;
  if (!g_active.progress(step, routed_steps, time, g_active.user_data)) {
    g_active.cancelled = true;
    if (g_active.ctx && g_active.ctx->sd) sd_cancel_generation(g_active.ctx->sd, SD_CANCEL_ALL);
  }
}

void forward_preview(int step, int frame_count, sd_image_t *frames, bool, void *) {
  if (!g_active.preview || frame_count < 1 || !frames || !frames[0].data) return;
  const sd_image_t &frame = frames[0];
  size_t pixel_count = 0;
  if (!image_pixel_count(frame, &pixel_count)) return;
  if (frame.channel == 3) {
    g_active.preview(step, frame.data, static_cast<int>(frame.width),
                     static_cast<int>(frame.height), g_active.user_data);
    return;
  }
  if (frame.channel != 4) return;

  try {
    std::vector<uint8_t> rgb(pixel_count * 3);
    image_to_rgb(frame, pixel_count, rgb.data());
    g_active.preview(step, rgb.data(), static_cast<int>(frame.width),
                     static_cast<int>(frame.height), g_active.user_data);
  } catch (...) {
    // A preview is optional; allocation failure must not abort generation.
  }
}

dit_ctx *engine_create(const dit_ctx_params *params) {
  g_create_error.clear();
  if (!params) {
    g_create_error = "null params";
    return nullptr;
  }
  const bool is_monolithic = (params->model_path && params->model_path[0]) ||
                             params->kind == DIT_MODEL_SD15 ||
                             params->kind == DIT_MODEL_SDXL;
  if (!is_monolithic &&
      (!params->diffusion_model_path || !params->vae_path ||
       (!params->llm_path && !params->t5xxl_path))) {
    g_create_error = "missing model paths";
    return nullptr;
  }

  sd_ctx_params_t sd_params;
  sd_ctx_params_init(&sd_params);
  if (params->model_path && params->model_path[0]) {
    sd_params.model_path = params->model_path;
  } else if (is_monolithic && params->diffusion_model_path) {
    sd_params.model_path = params->diffusion_model_path;
  }
  if (!is_monolithic) {
    sd_params.diffusion_model_path = params->diffusion_model_path;
  }
  sd_params.llm_path = params->llm_path;
  sd_params.llm_vision_path = params->llm_vision_path;
  sd_params.t5xxl_path = params->t5xxl_path;
  sd_params.clip_l_path = params->clip_l_path;
  sd_params.clip_g_path = params->clip_g_path;
  sd_params.vae_path = params->vae_path;
  sd_params.lora_apply_mode = LORA_APPLY_AUTO;
  if (params->taesd_path && params->taesd_path[0]) {
    sd_params.taesd_path = params->taesd_path;
    sd_params.tae_preview_only = true;
  }
  sd_params.n_threads = params->n_threads > 0 ? params->n_threads : 4;
  sd_params.enable_mmap = true;
  sd_params.flash_attn = params->flash_attn;
  sd_params.diffusion_flash_attn = params->flash_attn;
  sd_params.vae_conv_direct = params->vae_conv_direct;
  sd_params.diffusion_conv_direct = params->vae_conv_direct;
  // Keep segment prefetch enabled so loading/repacking can overlap execution.
  sd_params.disable_prefetch = false;
  if (params->backend && params->backend[0]) sd_params.backend = params->backend;
  if (params->params_backend && params->params_backend[0])
    sd_params.params_backend = params->params_backend;
  if (params->auto_fit_backend && params->auto_fit_backend[0]) {
    sd_params.auto_fit = true;
    sd_params.auto_fit_backend = params->auto_fit_backend;
  }
  if (params->auto_fit_max_ram_gib > 0.f)
    sd_params.auto_fit_max_ram_gib = params->auto_fit_max_ram_gib;
  if (params->max_vram_gib && params->max_vram_gib[0])
    sd_params.max_vram = params->max_vram_gib;

  auto *ctx = new dit_ctx();
  ctx->kind = params->kind;
  ctx->has_taesd = (params->taesd_path && params->taesd_path[0]);
  ctx->sd = new_sd_ctx(&sd_params);
  if (!ctx->sd) {
    g_create_error = "new_sd_ctx failed";
    delete ctx;
    return nullptr;
  }
  return ctx;
}

void engine_destroy(dit_ctx *ctx) {
  if (!ctx) return;
  if (ctx->sd) free_sd_ctx(ctx->sd);
  delete ctx;
}

bool engine_generate(dit_ctx *ctx, const dit_gen_params *params, dit_progress_cb progress,
                     dit_preview_cb preview, void *user_data, uint8_t **out_pixels,
                     int *out_width, int *out_height, int *out_channels) {
  if (!ctx || !ctx->sd || !params || !out_pixels || !out_channels) return false;
  ctx->last_error.clear();

  std::lock_guard<std::mutex> lock(g_gen_mutex);

  sd_img_gen_params_t gen;
  sd_img_gen_params_init(&gen);
  gen.prompt = params->prompt ? params->prompt : "";
  gen.negative_prompt = params->negative_prompt ? params->negative_prompt : "";
  gen.width = params->width;
  gen.height = params->height;
  gen.seed = params->seed;
  gen.batch_count = 1;
  gen.sample_params.sample_steps = params->steps;
  gen.sample_params.guidance.txt_cfg = params->cfg_scale;
  gen.sample_params.guidance.distilled_guidance = params->guidance;
  if (params->clip_skip > 0) {
    gen.clip_skip = params->clip_skip;
  }
  std::vector<sd_lora_t> sd_loras;
  if (params->lora_count > 0 && params->loras) {
    sd_loras.reserve(params->lora_count);
    for (int i = 0; i < params->lora_count; ++i) {
      if (params->loras[i].path && params->loras[i].path[0]) {
        sd_lora_t l{};
        l.path = params->loras[i].path;
        l.multiplier = params->loras[i].multiplier;
        l.is_high_noise = false;
        sd_loras.push_back(l);
      }
    }
    gen.loras = sd_loras.data();
    gen.lora_count = static_cast<uint32_t>(sd_loras.size());
  }
  if (params->sample_method && params->sample_method[0])
    gen.sample_params.sample_method = str_to_sample_method(params->sample_method);
  if (params->scheduler && params->scheduler[0])
    gen.sample_params.scheduler = str_to_scheduler(params->scheduler);

  if (params->init_image_rgb && params->init_width > 0 && params->init_height > 0) {
    gen.init_image.width = static_cast<uint32_t>(params->init_width);
    gen.init_image.height = static_cast<uint32_t>(params->init_height);
    gen.init_image.channel = 3;
    gen.init_image.data = const_cast<uint8_t *>(params->init_image_rgb);
    gen.strength = params->denoise_strength;
  }

  if (params->mask_image && params->mask_width > 0 && params->mask_height > 0) {
    if (!gen.init_image.data) {
      ctx->last_error = "inpaint mask requires an init image";
      return false;
    }
    if (params->mask_width != params->init_width ||
        params->mask_height != params->init_height) {
      ctx->last_error = "inpaint mask dimensions do not match init image";
      return false;
    }
    gen.mask_image.width = static_cast<uint32_t>(params->mask_width);
    gen.mask_image.height = static_cast<uint32_t>(params->mask_height);
    gen.mask_image.channel = 1;
    gen.mask_image.data = const_cast<uint8_t *>(params->mask_image);
  }

  std::vector<sd_image_t> reference_images;
  if (params->reference_image_count > 0) {
    if (!params->reference_images_rgb ||
        !params->reference_widths || !params->reference_heights) {
      ctx->last_error = "invalid reference images";
      return false;
    }
    reference_images.reserve(params->reference_image_count);
    for (int i = 0; i < params->reference_image_count; ++i) {
      if (!params->reference_images_rgb[i] || params->reference_widths[i] <= 0 ||
          params->reference_heights[i] <= 0) {
        ctx->last_error = "invalid reference image";
        return false;
      }
      reference_images.push_back(
          {static_cast<uint32_t>(params->reference_widths[i]),
           static_cast<uint32_t>(params->reference_heights[i]), 3,
           const_cast<uint8_t *>(params->reference_images_rgb[i])});
    }
    gen.ref_images = reference_images.data();
    gen.ref_images_count = static_cast<int>(reference_images.size());
    gen.ref_image_args =
        ctx->kind == DIT_MODEL_QWEN_IMAGE_2_1 ? "preset=qwen" : "preset=flux2";
  }

  if (params->vae_tile_size > 0) {
    gen.vae_tiling_params.enabled = true;
    gen.vae_tiling_params.tile_size_x = params->vae_tile_size;
    gen.vae_tiling_params.tile_size_y = params->vae_tile_size;
    gen.vae_tiling_params.target_overlap = params->vae_tile_overlap;
  }

  std::string scm_mask_storage;
  if (params->cache_mode && params->cache_mode[0]) {
    std::string mode(params->cache_mode);
    std::transform(mode.begin(), mode.end(), mode.begin(), ::tolower);
    if (mode == "easycache" || mode == "easy") {
      gen.cache.mode = SD_CACHE_EASYCACHE;
    } else if (mode == "ucache") {
      gen.cache.mode = SD_CACHE_UCACHE;
    } else if (mode == "dbcache") {
      gen.cache.mode = SD_CACHE_DBCACHE;
    } else if (mode == "taylorseer" || mode == "taylor") {
      gen.cache.mode = SD_CACHE_TAYLORSEER;
    } else if (mode == "cache-dit" || mode == "cachedit" || mode == "cache_dit") {
      gen.cache.mode = SD_CACHE_CACHE_DIT;
    } else if (mode == "spectrum") {
      gen.cache.mode = SD_CACHE_SPECTRUM;
    } else {
      gen.cache.mode = SD_CACHE_DISABLED;
    }

    if (gen.cache.mode != SD_CACHE_DISABLED) {
      gen.cache.reuse_threshold = (gen.cache.mode == SD_CACHE_EASYCACHE) ? 0.2f : 1.0f;
      if (params->cache_option && params->cache_option[0]) {
        const std::string opts(params->cache_option);
        std::string last_key;
        size_t pos = 0;
        while (pos < opts.size()) {
          const size_t comma = opts.find(',', pos);
          const std::string item =
              opts.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
          pos = (comma == std::string::npos) ? opts.size() : comma + 1;
          if (item.empty()) continue;
          const size_t eq = item.find('=');
          if (eq == std::string::npos) {
            if (last_key == "scm_mask") {
              if (!scm_mask_storage.empty()) scm_mask_storage += ',';
              scm_mask_storage += item;
              gen.cache.scm_mask = scm_mask_storage.c_str();
            }
            continue;
          }
          std::string key = item.substr(0, eq);
          const std::string val = item.substr(eq + 1);
          std::transform(key.begin(), key.end(), key.begin(), ::tolower);
          last_key = key;
          if (val.empty()) continue;

          char *end_ptr = nullptr;
          if (key == "threshold") {
            const float v = std::strtof(val.c_str(), &end_ptr);
            if (end_ptr != val.c_str()) {
              gen.cache.reuse_threshold = v;
              gen.cache.residual_diff_threshold = v;
            }
          } else if (key == "start") {
            const float v = std::strtof(val.c_str(), &end_ptr);
            if (end_ptr != val.c_str()) gen.cache.start_percent = v;
          } else if (key == "end") {
            const float v = std::strtof(val.c_str(), &end_ptr);
            if (end_ptr != val.c_str()) gen.cache.end_percent = v;
          } else if (key == "decay") {
            const float v = std::strtof(val.c_str(), &end_ptr);
            if (end_ptr != val.c_str()) gen.cache.error_decay_rate = v;
          } else if (key == "relative") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.use_relative_threshold = (v != 0);
          } else if (key == "reset") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.reset_error_on_compute = (v != 0);
          } else if (key == "fn") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.Fn_compute_blocks = static_cast<int>(v);
          } else if (key == "bn") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.Bn_compute_blocks = static_cast<int>(v);
          } else if (key == "warmup") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) {
              gen.cache.max_warmup_steps = static_cast<int>(v);
              gen.cache.spectrum_warmup_steps = static_cast<int>(v);
            }
          } else if (key == "max_cached") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.max_cached_steps = static_cast<int>(v);
          } else if (key == "max_continuous") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.max_continuous_cached_steps = static_cast<int>(v);
          } else if (key == "derivatives" || key == "n_derivatives") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.taylorseer_n_derivatives = static_cast<int>(v);
          } else if (key == "interval" || key == "skip_interval") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.taylorseer_skip_interval = static_cast<int>(v);
          } else if (key == "scm_dynamic") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.scm_policy_dynamic = (v != 0);
          } else if (key == "scm_mask") {
            scm_mask_storage = val;
            gen.cache.scm_mask = scm_mask_storage.c_str();
          } else if (key == "w") {
            const float v = std::strtof(val.c_str(), &end_ptr);
            if (end_ptr != val.c_str()) gen.cache.spectrum_w = v;
          } else if (key == "m") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.spectrum_m = static_cast<int>(v);
          } else if (key == "lam") {
            const float v = std::strtof(val.c_str(), &end_ptr);
            if (end_ptr != val.c_str()) gen.cache.spectrum_lam = v;
          } else if (key == "window") {
            const long v = std::strtol(val.c_str(), &end_ptr, 10);
            if (end_ptr != val.c_str()) gen.cache.spectrum_window_size = static_cast<int>(v);
          } else if (key == "flex") {
            const float v = std::strtof(val.c_str(), &end_ptr);
            if (end_ptr != val.c_str()) gen.cache.spectrum_flex_window = v;
          } else if (key == "stop") {
            const float v = std::strtof(val.c_str(), &end_ptr);
            if (end_ptr != val.c_str()) gen.cache.spectrum_stop_percent = v;
          }
        }
      }
    }
  }

  int sampling_steps = std::max(1, params->steps);
  if (gen.init_image.data && gen.strength < 1.0f) {
    // stable-diffusion.cpp retains t_enc + 1 intervals after trimming.
    const int t_enc = static_cast<int>(params->steps * gen.strength);
    sampling_steps = std::clamp(t_enc + 1, 1, std::max(1, params->steps));
  }
  g_active = ActiveGeneration{progress, preview, user_data, ctx, false, true,
                              sampling_steps};
  sd_set_progress_callback(progress ? forward_progress : nullptr, nullptr);
  if (preview && ctx->preview_interval > 0) {
    sd_set_preview_callback(forward_preview,
                            ctx->has_taesd ? PREVIEW_TAE : PREVIEW_PROJ,
                            ctx->preview_interval,
                            /*denoised=*/true, /*noisy=*/false, nullptr);
  } else {
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
  }

  sd_image_t *images = nullptr;
  int image_count = 0;
  const bool ok = generate_image(ctx->sd, &gen, &images, &image_count);

  sd_set_progress_callback(nullptr, nullptr);
  sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
  const bool cancelled = g_active.cancelled;
  g_active = ActiveGeneration{};
  // Clear the sticky cancel flag so the context stays usable afterwards.
  if (cancelled) sd_cancel_generation(ctx->sd, SD_CANCEL_RESET);

  if (!ok || image_count < 1 || !images || !images[0].data) {
    ctx->last_error = cancelled ? "cancelled" : "generate_image failed";
    if (images) free_sd_images(images, std::max(0, image_count));
    return false;
  }

  sd_image_t &image = images[0];
  size_t pixel_count = 0;
  if (!image_pixel_count(image, &pixel_count) ||
      (image.channel != 3 && image.channel != 4)) {
    ctx->last_error = "unsupported generated image dimensions/channels: " +
                      std::to_string(image.width) + "x" +
                      std::to_string(image.height) + "x" +
                      std::to_string(image.channel);
    free_sd_images(images, image_count);
    return false;
  }

  // Transfer the original interleaved allocation. In particular, preserve
  // Qwen Image 2.1's native alpha channel for the core/Kotlin receiver.
  *out_pixels = image.data;
  *out_channels = static_cast<int>(image.channel);
  image.data = nullptr;
  if (out_width) *out_width = static_cast<int>(image.width);
  if (out_height) *out_height = static_cast<int>(image.height);
  free_sd_images(images, image_count);
  return true;
}

void engine_free_image(uint8_t *pixels) { free(pixels); }

const char *engine_last_error(const dit_ctx *ctx) {
  if (!ctx) return g_create_error.c_str();
  return ctx->last_error.c_str();
}

void engine_set_log_callback(dit_log_cb cb, void *user_data) {
  g_log_cb = cb;
  g_log_user_data = user_data;
  sd_set_log_callback(cb ? forward_log : nullptr, nullptr);
}

void engine_set_preview_interval(dit_ctx *ctx, int interval) {
  if (ctx) ctx->preview_interval = interval;
}

bool engine_has_device(const char *name) {
  if (!name || !name[0]) return false;
  ggml_backend_load_all();
  for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
    const char *device_name = ggml_backend_dev_name(ggml_backend_dev_get(i));
    if (device_name && std::strcmp(device_name, name) == 0) return true;
  }
  return false;
}

bool engine_convert_model(const char *input_path, const char *vae_path,
                          const char *output_path, int output_type) {
  if (!input_path || !output_path) return false;
  return convert(input_path, vae_path, output_path,
                 static_cast<sd_type_t>(output_type), "", false);
}

bool engine_upscale(const char *esrgan_path, const uint8_t *in_rgb, int in_width, int in_height,
                    uint32_t scale_factor, int tile_size, int n_threads, const char *backend,
                    uint8_t **out_rgb, int *out_width, int *out_height) {
  if (!esrgan_path || !in_rgb || in_width <= 0 || in_height <= 0 || !out_rgb) return false;
  std::lock_guard<std::mutex> lock(g_gen_mutex);

  if (n_threads <= 0) {
    n_threads = std::max(1, static_cast<int>(std::thread::hardware_concurrency()));
  }
  if (tile_size <= 0) {
    tile_size = 256;
  }
  const char *effective_backend = backend;
  if (!effective_backend || !effective_backend[0] ||
      strcasecmp(effective_backend, "default") == 0 ||
      strcasecmp(effective_backend, "auto") == 0 ||
      strcasecmp(effective_backend, "automatic") == 0) {
    if (engine_has_device("HTP0")) {
      effective_backend = "HTP0";
    } else if (engine_has_device("GPUOpenCL")) {
      effective_backend = "GPUOpenCL";
    } else {
      effective_backend = "CPU";
    }
  }
  upscaler_ctx_t *upscaler_ctx = new_upscaler_ctx(
      esrgan_path, true, n_threads, tile_size, effective_backend, "all=disk");
  if (!upscaler_ctx) {
    g_create_error = "failed to create upscaler context from " + std::string(esrgan_path);
    return false;
  }

  sd_image_t in_img;
  in_img.width = static_cast<uint32_t>(in_width);
  in_img.height = static_cast<uint32_t>(in_height);
  in_img.channel = 3;
  in_img.data = const_cast<uint8_t *>(in_rgb);

  sd_image_t *res_images = nullptr;
  int num_res = 0;
  bool ok = upscale(upscaler_ctx, in_img, scale_factor, &res_images, &num_res);
  free_upscaler_ctx(upscaler_ctx);

  if (!ok || !res_images || num_res <= 0 || !res_images[0].data) {
    if (res_images) free_sd_images(res_images, num_res);
    g_create_error = "upscale execution failed";
    return false;
  }

  *out_rgb = res_images[0].data;
  if (out_width) *out_width = static_cast<int>(res_images[0].width);
  if (out_height) *out_height = static_cast<int>(res_images[0].height);
  res_images[0].data = nullptr;
  free_sd_images(res_images, num_res);
  return true;
}

const dit_engine_api g_api = {
    DIT_ENGINE_ABI_VERSION,
    engine_create,
    engine_destroy,
    engine_generate,
    engine_free_image,
    engine_last_error,
    engine_set_log_callback,
    engine_set_preview_interval,
    engine_has_device,
    engine_convert_model,
    engine_upscale,
};

}  // namespace

extern "C" __attribute__((visibility("default"))) const dit_engine_api *dit_engine_get_api(
    int requested_abi_version) {
  if (requested_abi_version != DIT_ENGINE_ABI_VERSION) return nullptr;
  return &g_api;
}
