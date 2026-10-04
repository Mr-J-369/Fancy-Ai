#include "native_diffusion_pipeline.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <limits>
#include <tuple>
#include <utility>

namespace aura::diffusion {
namespace {

constexpr int kTrainingSteps = 1000;
constexpr int kLcmOriginalSteps = 50;
constexpr double kBetaStart = 0.00085;
constexpr double kBetaEnd = 0.012;
constexpr double kPi = 3.14159265358979323846;

class KotlinRandom {
public:
    explicit KotlinRandom(int64_t seed) {
        const auto bits = static_cast<uint64_t>(seed);
        x_ = static_cast<uint32_t>(bits);
        y_ = static_cast<uint32_t>(bits >> 32U);
        z_ = 0;
        w_ = 0;
        v_ = ~x_;
        addend_ = (x_ << 10U) ^ (y_ >> 4U);
        for (int i = 0; i < 64; ++i) nextInt();
    }

    double nextDouble() {
        const auto hi = static_cast<uint64_t>(nextBits(26));
        const auto lo = static_cast<uint64_t>(nextBits(27));
        return static_cast<double>((hi << 27U) + lo) / 9007199254740992.0;
    }

    float gaussian() {
        const double u1 = std::max(nextDouble(), 1e-9);
        const double u2 = nextDouble();
        return static_cast<float>(std::sqrt(-2.0 * std::log(u1)) * std::cos(2.0 * kPi * u2));
    }

private:
    uint32_t nextInt() {
        uint32_t t = x_;
        t ^= t >> 2U;
        x_ = y_;
        y_ = z_;
        z_ = w_;
        w_ = v_;
        v_ = (v_ ^ (v_ << 4U)) ^ (t ^ (t << 1U));
        addend_ += 362437U;
        return v_ + addend_;
    }

    uint32_t nextBits(int count) { return nextInt() >> (32 - count); }

    uint32_t x_ = 0;
    uint32_t y_ = 0;
    uint32_t z_ = 0;
    uint32_t w_ = 0;
    uint32_t v_ = 0;
    uint32_t addend_ = 0;
};

const std::vector<float>& trainingSigmas() {
    static const std::vector<float> result = [] {
        std::vector<float> sigmas(kTrainingSteps);
        const double start = std::sqrt(kBetaStart);
        const double end = std::sqrt(kBetaEnd);
        double alphaProduct = 1.0;
        for (int i = 0; i < kTrainingSteps; ++i) {
            const double t = start + (end - start) * i / (kTrainingSteps - 1);
            alphaProduct *= 1.0 - t * t;
            sigmas[i] = static_cast<float>(std::sqrt((1.0 - alphaProduct) / alphaProduct));
        }
        return sigmas;
    }();
    return result;
}

float interpolate(const std::vector<float>& values, double position) {
    const int low = std::clamp(static_cast<int>(position), 0, static_cast<int>(values.size()) - 1);
    const int high = std::min(low + 1, static_cast<int>(values.size()) - 1);
    const auto fraction = static_cast<float>(position - low);
    return values[low] * (1.0f - fraction) + values[high] * fraction;
}

double logGamma(double x) {
    constexpr double g = 7.0;
    constexpr double coefficients[] = {
        0.99999999999981, 676.5203681218851, -1259.1392167224028,
        771.3234287776531, -176.6150291621406, 12.507343278686905,
        -0.1385710952657201, 9.984369578019572e-6, 1.5056327351493116e-7,
    };
    if (x < 0.5) return std::log(kPi / std::sin(kPi * x)) - logGamma(1.0 - x);
    const double shifted = x - 1.0;
    double sum = coefficients[0];
    for (int i = 1; i < 9; ++i) sum += coefficients[i] / (shifted + i);
    const double t = shifted + g + 0.5;
    return 0.5 * std::log(2.0 * kPi) + (shifted + 0.5) * std::log(t) - t + std::log(sum);
}

double betaContinuedFraction(double x, double a, double b) {
    const double qab = a + b;
    const double qap = a + 1.0;
    const double qam = a - 1.0;
    double c = 1.0;
    double d = 1.0 - qab * x / qap;
    if (std::abs(d) < 1e-30) d = 1e-30;
    d = 1.0 / d;
    double h = d;
    for (int m = 1; m <= 200; ++m) {
        const int m2 = 2 * m;
        double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
        d = 1.0 + aa * d;
        if (std::abs(d) < 1e-30) d = 1e-30;
        c = 1.0 + aa / c;
        if (std::abs(c) < 1e-30) c = 1e-30;
        d = 1.0 / d;
        h *= d * c;
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
        d = 1.0 + aa * d;
        if (std::abs(d) < 1e-30) d = 1e-30;
        c = 1.0 + aa / c;
        if (std::abs(c) < 1e-30) c = 1e-30;
        d = 1.0 / d;
        const double delta = d * c;
        h *= delta;
        if (std::abs(delta - 1.0) < 1e-10) break;
    }
    return h;
}

double regularizedIncompleteBeta(double x, double a, double b) {
    if (x <= 0.0) return 0.0;
    if (x >= 1.0) return 1.0;
    const double front = std::exp(
        logGamma(a + b) - logGamma(a) - logGamma(b) + a * std::log(x) + b * std::log(1.0 - x));
    if (x < (a + 1.0) / (a + b + 2.0)) return front * betaContinuedFraction(x, a, b) / a;
    return 1.0 - front * betaContinuedFraction(1.0 - x, b, a) / b;
}

double betaPpf(double probability, double a, double b) {
    if (probability <= 0.0) return 0.0;
    if (probability >= 1.0) return 1.0;
    double low = 0.0;
    double high = 1.0;
    for (int i = 0; i < 60; ++i) {
        const double middle = (low + high) * 0.5;
        if (regularizedIncompleteBeta(middle, a, b) < probability) low = middle;
        else high = middle;
    }
    return (low + high) * 0.5;
}

std::vector<float> scheduleFor(const Request& request) {
    const auto& train = trainingSigmas();
    const int steps = request.steps;
    std::vector<float> result(static_cast<size_t>(steps) + 1, 0.0f);
    if (request.sampler == Sampler::Lcm && !request.localDreamSd15) {
        if (steps > kLcmOriginalSteps) return {};
        const int stride = kTrainingSteps / kLcmOriginalSteps;
        for (int i = 0; i < steps; ++i) {
            // Diffusers LCMScheduler: reverse the 50-step distillation lattice and
            // select floor(i * 50 / steps). Img2img strength is applied later by
            // slicing this completed inference schedule, not by rebuilding it.
            const int selected = i * kLcmOriginalSteps / steps;
            const int timestep = (kLcmOriginalSteps - selected) * stride - 1;
            result[i] = train[timestep];
        }
        return result;
    }
    for (int i = 0; i < steps; ++i) {
        const double fraction = steps == 1 ? 0.0 : static_cast<double>(i) / (steps - 1);
        switch (request.schedule) {
            case Schedule::Normal: {
                const double position = (kTrainingSteps - 1) * (1.0 - fraction);
                result[i] = interpolate(train, position);
                break;
            }
            case Schedule::Karras: {
                constexpr double rho = 7.0;
                const double low = std::pow(train.front(), 1.0 / rho);
                const double high = std::pow(train.back(), 1.0 / rho);
                result[i] = static_cast<float>(std::pow(high + fraction * (low - high), rho));
                break;
            }
            case Schedule::Exponential:
                result[i] = static_cast<float>(std::exp(
                    std::log(train.back()) + fraction * (std::log(train.front()) - std::log(train.back()))));
                break;
            case Schedule::SgmUniform: {
                const double position = (kTrainingSteps - 1) * (1.0 - static_cast<double>(i) / steps);
                result[i] = interpolate(train, position);
                break;
            }
            case Schedule::Simple: {
                const double stride = static_cast<double>(kTrainingSteps) / steps;
                const int index = std::clamp(kTrainingSteps - 1 - static_cast<int>(i * stride), 0, kTrainingSteps - 1);
                result[i] = train[index];
                break;
            }
            case Schedule::DdimUniform: {
                const int stride = std::max(kTrainingSteps / steps, 1);
                result[i] = train[std::clamp(1 + (steps - 1 - i) * stride, 0, kTrainingSteps - 1)];
                break;
            }
            case Schedule::Beta: {
                const double probability = steps == 1 ? 0.5 : fraction;
                result[i] = interpolate(train, (kTrainingSteps - 1) * (1.0 - betaPpf(probability, 0.6, 0.6)));
                break;
            }
        }
    }
    return result;
}

int sigmaToTimestep(float sigma, bool localDreamSd15) {
    const auto& train = trainingSigmas();
    if (sigma <= train.front()) return 0;
    if (sigma >= train.back()) return static_cast<int>(train.size()) - 1;
    int low = 0;
    int high = static_cast<int>(train.size()) - 1;
    while (high - low > 1) {
        const int middle = (low + high) / 2;
        if (train[middle] < sigma) low = middle;
        else high = middle;
    }
    if (localDreamSd15) {
        const float fraction = (sigma - train[low]) / (train[high] - train[low]);
        return std::clamp(static_cast<int>(std::lround(static_cast<float>(low) + fraction)), 0, static_cast<int>(train.size()) - 1);
    }
    const float logSigma = std::log(std::max(sigma, 1e-10f));
    const float logLow = std::log(train[low]);
    const float logHigh = std::log(train[high]);
    const float fraction = (logSigma - logLow) / (logHigh - logLow);
    return std::clamp(static_cast<int>(std::lround(static_cast<float>(low) + fraction)), 0, static_cast<int>(train.size()) - 1);
}

std::vector<float> addScaled(const std::vector<float>& a, const std::vector<float>& b, float scale) {
    if (a.size() != b.size()) return {};
    std::vector<float> result(a.size());
    for (size_t i = 0; i < a.size(); ++i) result[i] = a[i] + b[i] * scale;
    return result;
}

std::vector<float> derivative(const std::vector<float>& x, float sigma, const std::vector<float>& denoised) {
    if (sigma == 0.0f || x.size() != denoised.size()) return {};
    std::vector<float> result(x.size());
    for (size_t i = 0; i < x.size(); ++i) result[i] = (x[i] - denoised[i]) / sigma;
    return result;
}

std::pair<float, float> ancestralStep(float from, float to) {
    const float up = std::min(to, std::sqrt(to * to * (from * from - to * to) / (from * from)));
    return {std::sqrt(std::max(0.0f, to * to - up * up)), up};
}

void addNoise(std::vector<float>& values, float scale, KotlinRandom& random) {
    for (float& value : values) value += random.gaussian() * scale;
}

using Denoise = std::function<std::vector<float>(const std::vector<float>&, float)>;

bool stopped(const Cancelled& cancelled) { return cancelled && cancelled(); }

std::vector<float> sample(
    Sampler sampler,
    std::vector<float> current,
    const std::vector<float>& sigmas,
    KotlinRandom& random,
    const Denoise& denoise,
    const Cancelled& cancelled) {
    std::vector<float> previous;
    std::vector<float> previous2;
    double previousH = 0.0;
    double previousH2 = 0.0;
    std::vector<std::vector<float>> derivatives;
    for (size_t i = 0; i + 1 < sigmas.size(); ++i) {
        if (stopped(cancelled)) return {};
        const float sigma = sigmas[i];
        const float nextSigma = sigmas[i + 1];
        auto denoised = denoise(current, sigma);
        if (denoised.size() != current.size()) return {};
        if (nextSigma == 0.0f) return denoised;

        if (sampler == Sampler::Euler) {
            current = addScaled(current, derivative(current, sigma, denoised), nextSigma - sigma);
        } else if (sampler == Sampler::Ddim) {
            // Deterministic DDIM (eta=0) in the sigma-normalized state used by
            // this pipeline: x_t / sqrt(alpha_t) = x_0 + sigma_t * epsilon.
            // Written explicitly instead of aliasing Euler even though the two
            // first-order equations are algebraically identical in this state.
            const auto epsilon = derivative(current, sigma, denoised);
            for (size_t k = 0; k < current.size(); ++k) {
                current[k] = denoised[k] + nextSigma * epsilon[k];
            }
        } else if (sampler == Sampler::Lcm) {
            current = std::move(denoised);
            addNoise(current, nextSigma, random);
        } else if (sampler == Sampler::EulerAncestral) {
            const auto [down, up] = ancestralStep(sigma, nextSigma);
            current = addScaled(current, derivative(current, sigma, denoised), down - sigma);
            addNoise(current, up, random);
        } else if (sampler == Sampler::Heun) {
            const auto d = derivative(current, sigma, denoised);
            const float dt = nextSigma - sigma;
            const auto predicted = addScaled(current, d, dt);
            const auto denoised2 = denoise(predicted, nextSigma);
            const auto d2 = derivative(predicted, nextSigma, denoised2);
            if (d2.size() != current.size()) return {};
            for (size_t k = 0; k < current.size(); ++k) current[k] += 0.5f * (d[k] + d2[k]) * dt;
        } else if (sampler == Sampler::Dpm2 || sampler == Sampler::Dpm2Ancestral) {
            float target = nextSigma;
            float up = 0.0f;
            if (sampler == Sampler::Dpm2Ancestral) std::tie(target, up) = ancestralStep(sigma, nextSigma);
            const auto d = derivative(current, sigma, denoised);
            if (target == 0.0f) {
                current = addScaled(current, d, -sigma);
            } else {
                const float middleSigma = std::exp(0.5f * (std::log(sigma) + std::log(target)));
                const auto middle = addScaled(current, d, middleSigma - sigma);
                const auto middleDenoised = denoise(middle, middleSigma);
                const auto middleD = derivative(middle, middleSigma, middleDenoised);
                current = addScaled(current, middleD, target - sigma);
                if (sampler == Sampler::Dpm2Ancestral) addNoise(current, up, random);
            }
        } else if (sampler == Sampler::Lms) {
            derivatives.push_back(derivative(current, sigma, denoised));
            if (derivatives.size() > 4) derivatives.erase(derivatives.begin());
            const int order = std::min<int>(static_cast<int>(i) + 1, 4);
            auto coefficient = [&](int j) {
                auto basis = [&](double tau) {
                    double product = 1.0;
                    for (int k = 0; k < order; ++k) {
                        if (k == j) continue;
                        product *= (tau - sigmas[i - k]) / (sigmas[i - j] - sigmas[i - k]);
                    }
                    return product;
                };
                const double a = sigma;
                const double b = nextSigma;
                constexpr int intervals = 100;
                const double h = (b - a) / intervals;
                double sum = basis(a) + basis(b);
                for (int m = 1; m < intervals; ++m) sum += (m % 2 == 0 ? 2.0 : 4.0) * basis(a + m * h);
                return static_cast<float>(sum * h / 3.0);
            };
            auto next = current;
            for (int j = 0; j < order; ++j) {
                const float c = coefficient(j);
                const auto& d = derivatives[derivatives.size() - 1 - j];
                for (size_t k = 0; k < next.size(); ++k) next[k] += c * d[k];
            }
            current = std::move(next);
        } else if (sampler == Sampler::DpmPp2M) {
            const double t = -std::log(static_cast<double>(sigma));
            const double nextT = -std::log(static_cast<double>(nextSigma));
            const double h = nextT - t;
            const auto denoisedFactor = static_cast<float>(-std::expm1(-h));
            const auto currentFactor = static_cast<float>(std::exp(-h));
            auto mixed = denoised;
            if (!previous.empty()) {
                const double lastH = t + std::log(static_cast<double>(sigmas[i - 1]));
                const auto ratio = static_cast<float>(lastH / h);
                for (size_t k = 0; k < mixed.size(); ++k) {
                    mixed[k] = (1.0f + 1.0f / (2.0f * ratio)) * denoised[k] - previous[k] / (2.0f * ratio);
                }
            }
            for (size_t k = 0; k < current.size(); ++k) current[k] = currentFactor * current[k] + denoisedFactor * mixed[k];
            previous = std::move(denoised);
        } else if (sampler == Sampler::DpmPp2SAncestral) {
            const auto [down, up] = ancestralStep(sigma, nextSigma);
            if (down == 0.0f) {
                current = addScaled(current, derivative(current, sigma, denoised), -sigma);
            } else {
                const double t = -std::log(static_cast<double>(sigma));
                const double downT = -std::log(static_cast<double>(down));
                const double h = downT - t;
                const auto middleSigma = static_cast<float>(std::exp(-(t + h / 2.0)));
                std::vector<float> middle(current.size());
                const auto middleCurrent = static_cast<float>(std::exp(-h / 2.0));
                const auto middleDenoised = static_cast<float>(-std::expm1(-h / 2.0));
                for (size_t k = 0; k < current.size(); ++k) middle[k] = middleCurrent * current[k] + middleDenoised * denoised[k];
                const auto denoisedMiddle = denoise(middle, middleSigma);
                const auto finalCurrent = static_cast<float>(std::exp(-h));
                const auto finalDenoised = static_cast<float>(-std::expm1(-h));
                for (size_t k = 0; k < current.size(); ++k) current[k] = finalCurrent * current[k] + finalDenoised * denoisedMiddle[k];
                addNoise(current, up, random);
            }
        } else if (sampler == Sampler::DpmPpSde) {
            const double t = -std::log(static_cast<double>(sigma));
            const double nextT = -std::log(static_cast<double>(nextSigma));
            const double h = nextT - t;
            const double middleT = t + h * 0.5;
            const auto middleSigma = static_cast<float>(std::exp(-middleT));
            const auto [down1, up1] = ancestralStep(sigma, middleSigma);
            const double downT1 = -std::log(static_cast<double>(down1));
            std::vector<float> middle(current.size());
            const auto a1 = static_cast<float>(std::exp(t - downT1));
            const auto b1 = static_cast<float>(-std::expm1(t - downT1));
            for (size_t k = 0; k < current.size(); ++k) middle[k] = a1 * current[k] + b1 * denoised[k];
            addNoise(middle, up1, random);
            const auto denoisedMiddle = denoise(middle, middleSigma);
            const auto [down2, up2] = ancestralStep(sigma, nextSigma);
            const double downT2 = -std::log(static_cast<double>(down2));
            const auto a2 = static_cast<float>(std::exp(t - downT2));
            const auto b2 = static_cast<float>(-std::expm1(t - downT2));
            for (size_t k = 0; k < current.size(); ++k) current[k] = a2 * current[k] + b2 * denoisedMiddle[k];
            addNoise(current, up2, random);
        } else if (sampler == Sampler::DpmPp2MSde || sampler == Sampler::DpmPp3MSde) {
            const double t = -std::log(static_cast<double>(sigma));
            const double nextT = -std::log(static_cast<double>(nextSigma));
            const double h = nextT - t;
            const double etaH = h;
            std::vector<float> next(current.size());
            if (sampler == Sampler::DpmPp2MSde) {
                const auto currentFactor = static_cast<float>((nextSigma / sigma) * std::exp(-etaH));
                const auto denoisedFactor = static_cast<float>(-std::expm1(-h - etaH));
                for (size_t k = 0; k < current.size(); ++k) next[k] = currentFactor * current[k] + denoisedFactor * denoised[k];
                if (!previous.empty()) {
                    const double ratio = previousH / h;
                    const auto correction = static_cast<float>(0.5 * (-std::expm1(-h - etaH)) / ratio);
                    for (size_t k = 0; k < next.size(); ++k) next[k] += correction * (denoised[k] - previous[k]);
                }
            } else {
                const double combinedH = h * 2.0;
                const auto currentFactor = static_cast<float>(std::exp(-combinedH));
                const auto denoisedFactor = static_cast<float>(-std::expm1(-combinedH));
                for (size_t k = 0; k < current.size(); ++k) next[k] = currentFactor * current[k] + denoisedFactor * denoised[k];
                const double phi2 = (-std::expm1(-combinedH)) / combinedH + 1.0;
                if (!previous.empty() && !previous2.empty()) {
                    const double r0 = previousH / h;
                    const double r1 = previousH2 / h;
                    const double phi3 = phi2 / combinedH - 0.5;
                    for (size_t k = 0; k < next.size(); ++k) {
                        const double a = (denoised[k] - previous[k]) / r0;
                        const double b = (previous[k] - previous2[k]) / r1;
                        const double d1 = a + (a - b) * r0 / (r0 + r1);
                        const double d2 = (a - b) / (r0 + r1);
                        next[k] = static_cast<float>(next[k] + phi2 * d1 - phi3 * d2);
                    }
                } else if (!previous.empty()) {
                    const double ratio = previousH / h;
                    for (size_t k = 0; k < next.size(); ++k) next[k] = static_cast<float>(next[k] + phi2 * (denoised[k] - previous[k]) / ratio);
                }
            }
            const auto noiseScale = static_cast<float>(nextSigma * std::sqrt(-std::expm1(-2.0 * h)));
            addNoise(next, noiseScale, random);
            current = std::move(next);
            previous2 = previous;
            previous = std::move(denoised);
            previousH2 = previousH;
            previousH = h;
        }
        if (current.empty()) return {};
    }
    return current;
}

std::vector<int> offsets(int length, int tile, int overlap) {
    if (length <= tile) return {0};
    if (tile <= 0 || overlap < 0 || overlap >= tile) return {};
    const int span = length - tile;
    const int stride = tile - overlap;
    const int count = (span + stride - 1) / stride + 1;
    std::vector<int> result(count);
    for (int i = 0; i < count; ++i) result[i] = span * i / (count - 1);
    return result;
}

float feather(int position, int tile) { return static_cast<float>(std::min(position + 1, tile - position)); }

std::vector<float> window(
    const std::vector<float>& source, int channels, int height, int width,
    int tile, int offsetY, int offsetX) {
    std::vector<float> result(static_cast<size_t>(channels) * tile * tile);
    for (int channel = 0; channel < channels; ++channel) {
        for (int y = 0; y < tile; ++y) {
            const auto* from = source.data() + static_cast<size_t>(channel) * height * width +
                static_cast<size_t>(offsetY + y) * width + offsetX;
            auto* to = result.data() + static_cast<size_t>(channel) * tile * tile + static_cast<size_t>(y) * tile;
            std::copy(from, from + tile, to);
        }
    }
    return result;
}

class Canvas {
public:
    Canvas(int channels, int height, int width, int tile)
        : channels_(channels), height_(height), width_(width), tile_(tile),
          values_(static_cast<size_t>(channels) * height * width), weights_(static_cast<size_t>(height) * width) {}

    bool add(const std::vector<float>& patch, int offsetY, int offsetX) {
        if (patch.size() != static_cast<size_t>(channels_) * tile_ * tile_) return false;
        for (int y = 0; y < tile_; ++y) {
            const float yWeight = feather(y, tile_);
            const int row = (offsetY + y) * width_;
            for (int x = 0; x < tile_; ++x) {
                const float weight = yWeight * feather(x, tile_);
                const int pixel = row + offsetX + x;
                weights_[pixel] += weight;
                for (int channel = 0; channel < channels_; ++channel) {
                    values_[static_cast<size_t>(channel) * height_ * width_ + pixel] +=
                        patch[static_cast<size_t>(channel) * tile_ * tile_ + y * tile_ + x] * weight;
                }
            }
        }
        return true;
    }

    std::vector<float> finish() {
        for (int channel = 0; channel < channels_; ++channel) {
            const size_t base = static_cast<size_t>(channel) * height_ * width_;
            for (size_t i = 0; i < weights_.size(); ++i) values_[base + i] /= weights_[i];
        }
        return std::move(values_);
    }

private:
    int channels_;
    int height_;
    int width_;
    int tile_;
    std::vector<float> values_;
    std::vector<float> weights_;
};

std::vector<float> rgbaToChw(
    const uint8_t* rgba, int sourceWidth, int sourceHeight, int stride, int width, int height) {
    if (!rgba || sourceWidth <= 0 || sourceHeight <= 0 || stride < sourceWidth * 4) return {};
    const size_t plane = static_cast<size_t>(width) * height;
    std::vector<float> result(3 * plane);
    for (int y = 0; y < height; ++y) {
        const float sourceY = (static_cast<float>(y) + 0.5f) * static_cast<float>(sourceHeight) / static_cast<float>(height) - 0.5f;
        const int y0 = std::clamp(static_cast<int>(std::floor(sourceY)), 0, sourceHeight - 1);
        const int y1 = std::min(y0 + 1, sourceHeight - 1);
        const float fy = std::clamp(sourceY - std::floor(sourceY), 0.0f, 1.0f);
        for (int x = 0; x < width; ++x) {
            const float sourceX = (static_cast<float>(x) + 0.5f) * static_cast<float>(sourceWidth) / static_cast<float>(width) - 0.5f;
            const int x0 = std::clamp(static_cast<int>(std::floor(sourceX)), 0, sourceWidth - 1);
            const int x1 = std::min(x0 + 1, sourceWidth - 1);
            const float fx = std::clamp(sourceX - std::floor(sourceX), 0.0f, 1.0f);
            const auto* p00 = rgba + static_cast<size_t>(y0) * stride + x0 * 4;
            const auto* p01 = rgba + static_cast<size_t>(y0) * stride + x1 * 4;
            const auto* p10 = rgba + static_cast<size_t>(y1) * stride + x0 * 4;
            const auto* p11 = rgba + static_cast<size_t>(y1) * stride + x1 * 4;
            const size_t index = static_cast<size_t>(y) * width + x;
            for (int channel = 0; channel < 3; ++channel) {
                const float top = static_cast<float>(p00[channel]) + static_cast<float>(p01[channel] - p00[channel]) * fx;
                const float bottom = static_cast<float>(p10[channel]) + static_cast<float>(p11[channel] - p10[channel]) * fx;
                result[static_cast<size_t>(channel) * plane + index] =
                    ((top + (bottom - top) * fy) / 255.0f) * 2.0f - 1.0f;
            }
        }
    }
    return result;
}

}  // namespace

std::vector<float> encodeTiled(
    const uint8_t* rgba,
    int sourceWidth,
    int sourceHeight,
    int sourceStride,
    int outputWidth,
    int outputHeight,
    const std::function<std::vector<float>(const std::vector<float>&)>& encodeTile) {
    return encodeTiled(
        rgba,
        sourceWidth,
        sourceHeight,
        sourceStride,
        outputWidth,
        outputHeight,
        512,
        256,
        encodeTile);
}

std::vector<float> encodeTiled(
    const uint8_t* rgba,
    int sourceWidth,
    int sourceHeight,
    int sourceStride,
    int outputWidth,
    int outputHeight,
    int tilePixels,
    int overlapPixels,
    const std::function<std::vector<float>(const std::vector<float>&)>& encodeTile) {
    if (tilePixels <= 0 || tilePixels % 8 != 0 || overlapPixels < 0 ||
        overlapPixels >= tilePixels || overlapPixels % 8 != 0) {
        return {};
    }
    const int latentTile = tilePixels / 8;
    const int latentOverlap = overlapPixels / 8;
    auto image = rgbaToChw(rgba, sourceWidth, sourceHeight, sourceStride, outputWidth, outputHeight);
    if (image.empty()) return {};
    const int latentWidth = outputWidth / 8;
    const int latentHeight = outputHeight / 8;
    if (latentWidth <= latentTile && latentHeight <= latentTile) return encodeTile(image);
    Canvas mean(4, latentHeight, latentWidth, latentTile);
    Canvas deviation(4, latentHeight, latentWidth, latentTile);
    const auto half = static_cast<size_t>(4) * latentTile * latentTile;
    bool outputKindKnown = false;
    bool hasDeviation = false;
    for (int y : offsets(latentHeight, latentTile, latentOverlap)) {
        for (int x : offsets(latentWidth, latentTile, latentOverlap)) {
            const auto tile = window(image, 3, outputHeight, outputWidth, tilePixels, y * 8, x * 8);
            const auto encoded = encodeTile(tile);
            const bool tileHasDeviation = encoded.size() == 2 * half;
            if (encoded.size() != half && !tileHasDeviation) return {};
            if (!outputKindKnown) {
                outputKindKnown = true;
                hasDeviation = tileHasDeviation;
            } else if (hasDeviation != tileHasDeviation) {
                return {};
            }
            if (!mean.add(std::vector<float>(encoded.begin(), encoded.begin() + static_cast<std::ptrdiff_t>(half)), y, x)) return {};
            if (hasDeviation &&
                !deviation.add(std::vector<float>(encoded.begin() + static_cast<std::ptrdiff_t>(half), encoded.end()), y, x)) {
                return {};
            }
        }
    }
    auto result = mean.finish();
    if (hasDeviation) {
        auto stddev = deviation.finish();
        result.insert(result.end(), stddev.begin(), stddev.end());
    }
    return result;
}

std::vector<float> imageToChw(
    const uint8_t* rgba,
    int sourceWidth,
    int sourceHeight,
    int sourceStride,
    int outputWidth,
    int outputHeight) {
    return rgbaToChw(rgba, sourceWidth, sourceHeight, sourceStride, outputWidth, outputHeight);
}

std::vector<float> decodeTiled(
    const std::vector<float>& latent,
    int width,
    int height,
    const std::function<std::vector<float>(const std::vector<float>&)>& decodeTile) {
    return decodeTiled(latent, width, height, 512, 256, decodeTile);
}

std::vector<float> decodeTiled(
    const std::vector<float>& latent,
    int width,
    int height,
    int tilePixels,
    int overlapPixels,
    const std::function<std::vector<float>(const std::vector<float>&)>& decodeTile) {
    if (tilePixels <= 0 || tilePixels % 8 != 0 || overlapPixels < 0 ||
        overlapPixels >= tilePixels || overlapPixels % 8 != 0) {
        return {};
    }
    const int latentTile = tilePixels / 8;
    const int latentOverlap = overlapPixels / 8;
    const int latentWidth = width / 8;
    const int latentHeight = height / 8;
    if (latentWidth <= latentTile && latentHeight <= latentTile) return decodeTile(latent);
    Canvas canvas(3, height, width, tilePixels);
    for (int y : offsets(latentHeight, latentTile, latentOverlap)) {
        for (int x : offsets(latentWidth, latentTile, latentOverlap)) {
            const auto tile = window(latent, 4, latentHeight, latentWidth, latentTile, y, x);
            if (!canvas.add(decodeTile(tile), y * 8, x * 8)) return {};
        }
    }
    return canvas.finish();
}

std::vector<uint8_t> generate(
    const Request& request,
    float vaeScale,
    const EncodeSource& encodeSource,
    const PredictNoise& predictNoise,
    const DecodeLatent& decodeLatent,
    const Progress& progress,
    const Cancelled& cancelled) {
    if (request.width <= 0 || request.height <= 0 || request.width % 8 != 0 ||
        request.height % 8 != 0 || request.steps <= 0 || !predictNoise || !decodeLatent) return {};
    auto sigmas = scheduleFor(request);
    if (sigmas.size() != static_cast<size_t>(request.steps) + 1) return {};
    const size_t latentElements = static_cast<size_t>(4) * (request.width / 8) * (request.height / 8);
    const int64_t seed = request.seed < 0
        ? std::chrono::steady_clock::now().time_since_epoch().count()
        : request.seed;
    KotlinRandom random(seed);
    std::vector<float> current(latentElements);
    size_t firstSigma = 0;
    if (request.sourceRgba) {
        if (!encodeSource) return {};
        const auto encoded = encodeSource(
            request.sourceRgba, request.sourceWidth, request.sourceHeight, request.sourceStride);
        if (encoded.size() != latentElements && encoded.size() != latentElements * 2) return {};
        for (size_t i = 0; i < latentElements; ++i) {
            current[i] = vaeScale * (encoded[i] +
                (encoded.size() == latentElements * 2 ? encoded[latentElements + i] * random.gaussian() : 0.0f));
        }
        const int total = std::clamp(
            static_cast<int>(std::lround(std::clamp(request.strength, 0.05f, 1.0f) * static_cast<float>(request.steps))),
            1,
            request.steps);
        firstSigma = static_cast<size_t>(request.steps - total);
        for (float& value : current) value += random.gaussian() * sigmas[firstSigma];
        sigmas.erase(sigmas.begin(), sigmas.begin() + static_cast<std::ptrdiff_t>(firstSigma));
    } else {
        const float initialScale = !request.localDreamSd15 &&
            (request.sampler == Sampler::Ddim || request.sampler == Sampler::Lcm)
            ? std::sqrt(1.0f + sigmas.front() * sigmas.front())
            : sigmas.front();
        for (float& value : current) value = random.gaussian() * initialScale;
    }

    int evaluations = 0;
    auto denoise = [&](const std::vector<float>& x, float sigma) {
        if (stopped(cancelled)) return std::vector<float>{};
        const float inputScale = 1.0f / std::sqrt(sigma * sigma + 1.0f);
        std::vector<float> input(x.size());
        for (size_t i = 0; i < x.size(); ++i) input[i] = x[i] * inputScale;
        auto result = predictNoise(input, sigmaToTimestep(sigma, request.localDreamSd15));
        if (result.size() != x.size()) return std::vector<float>{};
        if (request.vPrediction) {
            const float denominator = sigma * sigma + 1.0f;
            const float a = 1.0f / denominator;
            const float b = -sigma / std::sqrt(denominator);
            for (size_t i = 0; i < x.size(); ++i) result[i] = x[i] * a + result[i] * b;
        } else {
            for (size_t i = 0; i < x.size(); ++i) result[i] = x[i] - sigma * result[i];
        }
        if (request.sampler == Sampler::Lcm && !request.localDreamSd15) {
            // LCMScheduler boundary-condition blend. `x` is sigma-normalized,
            // while c_skip multiplies the diffusion-space sample x_t.
            const int timestep = sigmaToTimestep(sigma, false);
            const auto scaledTimestep = static_cast<float>(timestep) * 10.0f;
            constexpr float sigmaDataSquared = 0.25f;
            const float denominator = scaledTimestep * scaledTimestep + sigmaDataSquared;
            const float cSkip = sigmaDataSquared / denominator;
            const float cOut = scaledTimestep / std::sqrt(denominator);
            const float sqrtAlpha = inputScale;
            for (size_t i = 0; i < result.size(); ++i) {
                result[i] = cOut * result[i] + cSkip * (x[i] * sqrtAlpha);
            }
        }
        ++evaluations;
        if (progress) progress(std::min(evaluations, request.steps) * 100 / request.steps);
        return result;
    };
    current = sample(request.sampler, std::move(current), sigmas, random, denoise, cancelled);
    if (current.size() != latentElements || stopped(cancelled)) return {};
    const auto pixels = decodeLatent(current);
    const size_t plane = static_cast<size_t>(request.width) * request.height;
    if (pixels.size() != 3 * plane) return {};
    std::vector<uint8_t> rgba(4 * plane);
    for (size_t i = 0; i < plane; ++i) {
        const float red = pixels[i];
        const float green = pixels[plane + i];
        const float blue = pixels[2 * plane + i];
        if (!std::isfinite(red) || !std::isfinite(green) || !std::isfinite(blue)) return {};
        rgba[4 * i] = static_cast<uint8_t>(std::clamp(std::lround(red), 0L, 255L));
        rgba[4 * i + 1] = static_cast<uint8_t>(std::clamp(std::lround(green), 0L, 255L));
        rgba[4 * i + 2] = static_cast<uint8_t>(std::clamp(std::lround(blue), 0L, 255L));
        rgba[4 * i + 3] = 255;
    }
    return rgba;
}

}  // namespace aura::diffusion
