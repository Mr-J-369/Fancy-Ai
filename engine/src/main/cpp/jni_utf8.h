#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>

namespace {

// JNI modified-UTF-8 to standard UTF-8, shared by every llama entry point.
std::string to_utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) throw std::bad_alloc();
    std::string utf8;
    utf8.reserve(static_cast<size_t>(length));
    for (jsize index = 0; index < length; ++index) {
        uint32_t point = chars[index];
        if (point >= 0xD800U && point <= 0xDBFFU && index + 1 < length) {
            const uint32_t low = chars[index + 1];
            if (low >= 0xDC00U && low <= 0xDFFFU) {
                point = 0x10000U + ((point - 0xD800U) << 10U) + (low - 0xDC00U);
                ++index;
            } else {
                point = 0xFFFDU;
            }
        } else if (point >= 0xDC00U && point <= 0xDFFFU) {
            point = 0xFFFDU;
        }
        if (point <= 0x7FU) {
            utf8.push_back(static_cast<char>(point));
        } else if (point <= 0x7FFU) {
            utf8.push_back(static_cast<char>(0xC0U | (point >> 6U)));
            utf8.push_back(static_cast<char>(0x80U | (point & 0x3FU)));
        } else if (point <= 0xFFFFU) {
            utf8.push_back(static_cast<char>(0xE0U | (point >> 12U)));
            utf8.push_back(static_cast<char>(0x80U | ((point >> 6U) & 0x3FU)));
            utf8.push_back(static_cast<char>(0x80U | (point & 0x3FU)));
        } else {
            utf8.push_back(static_cast<char>(0xF0U | (point >> 18U)));
            utf8.push_back(static_cast<char>(0x80U | ((point >> 12U) & 0x3FU)));
            utf8.push_back(static_cast<char>(0x80U | ((point >> 6U) & 0x3FU)));
            utf8.push_back(static_cast<char>(0x80U | (point & 0x3FU)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return utf8;
}

bool complete_utf8(const std::string & value) {
    // tools/server/server-common.cpp: validate_utf8 checks only an unfinished tail.
    for (size_t i = 1; i <= 4 && i <= value.size(); ++i) {
        const unsigned char c = value[value.size() - i];
        if ((c & 0xE0) == 0xC0 && i < 2) return false;
        if ((c & 0xF0) == 0xE0 && i < 3) return false;
        if ((c & 0xF8) == 0xF0 && i < 4) return false;
    }
    return true;
}

}  // namespace
