#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <algorithm>
#include <cmath>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <MNN/MNNForwardType.h>

#define TAG "fancyswap"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace MNN;

// Aura Swap native — runs each stage's MNN model (SCRFD detect / ArcFace embed / inswapper
// swap / CodeFormer restore). Alignment, cropping and blend-back live in Kotlin; this file
// only feeds tensors and (for SCRFD) decodes the multi-stride detection output. Models are
// loaded → run → released per call so at most one is resident — the job is already serialized
// by ImageService's queue, so there's no concurrency to guard. Mirrors mnn_segmenter.cpp.

namespace {

// Aura Swap is CPU-only on purpose: the OpenCL/Vulkan backends miscompute every stage of this
// pipeline — SCRFD's fine-stride score planes collapse to ~0 (faces missed), and the inswapper
// emap + CodeFormer VQ paths produce garbage. So all four models run on CPU; there is no GPU path.

// Stage model paths, set once in nativeLoad.
struct Cfg {
    std::string detect, embed, swap, restore;
};
std::unique_ptr<Cfg> g;

// A model loaded for the duration of a single stage call.
struct Engine {
    std::shared_ptr<Interpreter> interp;
    Session* session = nullptr;

    // CPU-only (see the Aura-Swap note above). Loads the model, builds a 4-thread CPU session.
    bool create(const std::string& path) {
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) { LOGE("open failed: %s", path.c_str()); return false; }
        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size <= 0) { close(fd); return false; }
        size_t sz = (size_t) st.st_size;
        void* map = mmap(nullptr, sz, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (map == MAP_FAILED) { LOGE("mmap failed"); return false; }
        madvise(map, sz, MADV_SEQUENTIAL);
        interp.reset(Interpreter::createFromBuffer(map, sz));
        munmap(map, sz);
        if (!interp) { LOGE("createFromBuffer failed: %s", path.c_str()); return false; }
        interp->setExternalFile((path + ".weight").c_str());

        ScheduleConfig cfg; BackendConfig bk;
        cfg.type = MNN_FORWARD_CPU;
        cfg.numThread = 4;
        bk.power = BackendConfig::Power_High;
        cfg.backendConfig = &bk;

        session = interp->createSession(cfg);
        if (!session) { LOGE("createSession failed: %s", path.c_str()); return false; }
        return true;
    }
    ~Engine() { if (interp && session) interp->releaseSession(session); }
};

bool hostOk(const Tensor& t) { return t.host<float>() != nullptr; }

// Copy a CHW float buffer into a named (or sole) input tensor.
bool setInput(Engine& e, const char* name, const float* data, int count) {
    Tensor* in = name ? e.interp->getSessionInput(e.session, name)
                      : e.interp->getSessionInput(e.session, nullptr);
    if (!in) { LOGE("missing input %s", name ? name : "<sole>"); return false; }
    Tensor host(in, Tensor::CAFFE);
    if (!hostOk(host) || host.elementSize() < count) { LOGE("input staging bad"); return false; }
    memcpy(host.host<float>(), data, count * sizeof(float));
    in->copyFromHostTensor(&host);
    return true;
}

// Read a named (or sole) output into out (resized to its element count).
bool readOutput(Engine& e, const char* name, std::vector<float>& out) {
    Tensor* o = name ? e.interp->getSessionOutput(e.session, name)
                     : e.interp->getSessionOutput(e.session, nullptr);
    if (!o) { LOGE("missing output %s", name ? name : "<sole>"); return false; }
    Tensor host(o, Tensor::CAFFE);
    if (!hostOk(host)) return false;
    o->copyToHostTensor(&host);
    out.assign(host.host<float>(), host.host<float>() + host.elementSize());
    return true;
}

jfloatArray toJava(JNIEnv* env, const std::vector<float>& v) {
    jfloatArray a = env->NewFloatArray((jsize) v.size());
    if (a) env->SetFloatArrayRegion(a, 0, (jsize) v.size(), v.data());
    return a;
}

// ── SCRFD decode ────────────────────────────────────────────────────────────────────────────
constexpr int DET_SIDE = 640;
constexpr int NUM_ANCHORS = 2;
constexpr float SCORE_THR = 0.45f;
constexpr float NMS_IOU = 0.4f;

struct Det { float score, x1, y1, x2, y2, kps[10]; };

float iou(const Det& a, const Det& b) {
    float xx1 = std::max(a.x1, b.x1), yy1 = std::max(a.y1, b.y1);
    float xx2 = std::min(a.x2, b.x2), yy2 = std::min(a.y2, b.y2);
    float w = std::max(0.f, xx2 - xx1), h = std::max(0.f, yy2 - yy1);
    float inter = w * h;
    float ua = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter;
    return ua > 0 ? inter / ua : 0.f;
}

// Decode one stride's score/bbox/kps planes (each row-major over [cell*anchors]).
void decodeStride(int stride, const std::vector<float>& score,
                  const std::vector<float>& bbox, const std::vector<float>& kps,
                  std::vector<Det>& out) {
    const int w = DET_SIDE / stride;
    const int n = (int) score.size();  // = w*w*NUM_ANCHORS
    for (int i = 0; i < n; ++i) {
        if (score[i] < SCORE_THR) continue;
        const int cell = i / NUM_ANCHORS;
        const float cx = (float) (cell % w) * stride;
        const float cy = (float) (cell / w) * stride;
        Det d;
        d.score = score[i];
        d.x1 = cx - bbox[i * 4 + 0] * stride;
        d.y1 = cy - bbox[i * 4 + 1] * stride;
        d.x2 = cx + bbox[i * 4 + 2] * stride;
        d.y2 = cy + bbox[i * 4 + 3] * stride;
        for (int k = 0; k < 5; ++k) {
            d.kps[2 * k]     = cx + kps[i * 10 + 2 * k]     * stride;
            d.kps[2 * k + 1] = cy + kps[i * 10 + 2 * k + 1] * stride;
        }
        out.push_back(d);
    }
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_face_swap_MnnFaceSwap_nativeLoad(
        JNIEnv* env, jobject, jstring jdetect, jstring jembed, jstring jswap, jstring jrestore,
        jboolean useGpu) {
    auto str = [&](jstring s) {
        const char* c = env->GetStringUTFChars(s, nullptr);
        std::string r(c ? c : ""); if (c) env->ReleaseStringUTFChars(s, c); return r;
    };
    (void) useGpu;   // Aura Swap is CPU-only; the GPU backends miscompute this pipeline.
    g.reset(new Cfg());
    g->detect = str(jdetect); g->embed = str(jembed);
    g->swap = str(jswap); g->restore = str(jrestore);
    for (const std::string* p : {&g->detect, &g->embed, &g->swap, &g->restore}) {
        struct stat st{};
        if (stat(p->c_str(), &st) != 0 || st.st_size <= 0) { LOGE("missing model %s", p->c_str()); g.reset(); return JNI_FALSE; }
    }
    LOGI("Aura Swap configured (CPU)");
    return JNI_TRUE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_mrj_fancyai_sd_face_swap_MnnFaceSwap_nativeDetect(JNIEnv* env, jobject, jfloatArray jchw) {
    if (!g) return env->NewFloatArray(0);
    const int inN = 3 * DET_SIDE * DET_SIDE;
    if (env->GetArrayLength(jchw) < inN) return env->NewFloatArray(0);
    std::vector<float> in(inN);
    env->GetFloatArrayRegion(jchw, 0, inN, in.data());

    Engine e;
    if (!e.create(g->detect)) return env->NewFloatArray(0);
    if (!setInput(e, nullptr, in.data(), inN)) return env->NewFloatArray(0);
    if (e.interp->runSession(e.session) != 0) { LOGE("detect run failed"); return env->NewFloatArray(0); }

    // Classify the 9 outputs by shape: last-dim 1=score, 4=bbox, 10=kps; row count → stride.
    auto outs = e.interp->getSessionOutputAll(e.session);
    std::map<int, std::vector<float>> score, bbox, kps;   // keyed by stride
    for (auto& kv : outs) {
        Tensor host(kv.second, Tensor::CAFFE);
        if (!hostOk(host)) continue;
        kv.second->copyToHostTensor(&host);
        const int total = host.elementSize();
        const int last = host.dimensions() > 0 ? host.length(host.dimensions() - 1) : 0;
        const int rows = last > 0 ? total / last : 0;
        if (rows <= 0) continue;
        const int cells = rows / NUM_ANCHORS;            // w*w
        int side = (int) std::lround(std::sqrt((double) cells));
        if (side <= 0) continue;
        const int stride = DET_SIDE / side;
        std::vector<float> v(host.host<float>(), host.host<float>() + total);
        if (last == 1) score[stride] = std::move(v);
        else if (last == 4) bbox[stride] = std::move(v);
        else if (last == 10) kps[stride] = std::move(v);
    }

    std::vector<Det> dets;
    for (int stride : {8, 16, 32}) {
        if (score.count(stride) && bbox.count(stride) && kps.count(stride))
            decodeStride(stride, score[stride], bbox[stride], kps[stride], dets);
    }
    // NMS (score desc).
    std::sort(dets.begin(), dets.end(), [](const Det& a, const Det& b) { return a.score > b.score; });
    std::vector<Det> keep;
    for (const auto& d : dets) {
        bool ok = true;
        for (const auto& k : keep) if (iou(d, k) > NMS_IOU) { ok = false; break; }
        if (ok) keep.push_back(d);
        if (keep.size() >= 32) break;
    }
    std::vector<float> flat;
    flat.reserve(keep.size() * 15);
    for (const auto& d : keep) {
        flat.push_back(d.score);
        flat.push_back(d.x1); flat.push_back(d.y1); flat.push_back(d.x2); flat.push_back(d.y2);
        for (float f : d.kps) flat.push_back(f);
    }
    return toJava(env, flat);
}

JNIEXPORT jfloatArray JNICALL
Java_com_mrj_fancyai_sd_face_swap_MnnFaceSwap_nativeEmbed(JNIEnv* env, jobject, jfloatArray jchw) {
    if (!g) return env->NewFloatArray(0);
    const int inN = 3 * 112 * 112;
    if (env->GetArrayLength(jchw) < inN) return env->NewFloatArray(0);
    std::vector<float> in(inN);
    env->GetFloatArrayRegion(jchw, 0, inN, in.data());
    Engine e;
    if (!e.create(g->embed)) return env->NewFloatArray(0);
    if (!setInput(e, nullptr, in.data(), inN)) return env->NewFloatArray(0);
    if (e.interp->runSession(e.session) != 0) { LOGE("embed run failed"); return env->NewFloatArray(0); }
    std::vector<float> out;
    if (!readOutput(e, nullptr, out)) return env->NewFloatArray(0);
    return toJava(env, out);
}

JNIEXPORT jfloatArray JNICALL
Java_com_mrj_fancyai_sd_face_swap_MnnFaceSwap_nativeSwap(
        JNIEnv* env, jobject, jfloatArray jtarget, jfloatArray jemb) {
    if (!g) return env->NewFloatArray(0);
    const int tN = 3 * 128 * 128, eN = 512;
    if (env->GetArrayLength(jtarget) < tN || env->GetArrayLength(jemb) < eN) return env->NewFloatArray(0);
    std::vector<float> target(tN), emb(eN);
    env->GetFloatArrayRegion(jtarget, 0, tN, target.data());
    env->GetFloatArrayRegion(jemb, 0, eN, emb.data());
    Engine e;
    if (!e.create(g->swap)) return env->NewFloatArray(0);
    if (!setInput(e, "target", target.data(), tN)) return env->NewFloatArray(0);
    if (!setInput(e, "source_emb", emb.data(), eN)) return env->NewFloatArray(0);
    if (e.interp->runSession(e.session) != 0) { LOGE("swap run failed"); return env->NewFloatArray(0); }
    std::vector<float> out;
    if (!readOutput(e, "output", out)) return env->NewFloatArray(0);
    return toJava(env, out);
}

JNIEXPORT jfloatArray JNICALL
Java_com_mrj_fancyai_sd_face_swap_MnnFaceSwap_nativeRestore(
        JNIEnv* env, jobject, jfloatArray jchw, jfloat fidelity) {
    if (!g) return env->NewFloatArray(0);
    const int inN = 3 * 512 * 512;
    if (env->GetArrayLength(jchw) < inN) return env->NewFloatArray(0);
    std::vector<float> in(inN);
    env->GetFloatArrayRegion(jchw, 0, inN, in.data());
    Engine e;
    if (!e.create(g->restore)) return env->NewFloatArray(0);
    if (!setInput(e, "input", in.data(), inN)) return env->NewFloatArray(0);
    // Scalar fidelity weight. It's a 0-dim input: building a fresh host copy of it can
    // report elementSize 0 and silently drop the write, leaving CodeFormer stuck at the
    // default (so the slider does nothing). On the CPU backend the session input is a
    // properly-sized, host-resident 1-element buffer, so write it directly.
    Tensor* w = e.interp->getSessionInput(e.session, "weight");
    if (w && w->host<float>()) {
        w->host<float>()[0] = fidelity;
    }
    if (e.interp->runSession(e.session) != 0) { LOGE("restore run failed"); return env->NewFloatArray(0); }
    std::vector<float> out;
    if (!readOutput(e, "output", out)) return env->NewFloatArray(0);
    return toJava(env, out);
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_sd_face_swap_MnnFaceSwap_nativeUnload(JNIEnv*, jobject) { g.reset(); }

}
