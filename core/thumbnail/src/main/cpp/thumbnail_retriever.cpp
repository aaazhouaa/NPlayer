// SPDX-License-Identifier: GPL-3.0-or-later
// NextLib d8fc329c4bbbf4a83bd44dda5337ea4a2685025a, mediainfo/src/main/cpp/media_thumbnail_retriever.cpp
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libavutil/display.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
}

#include <android/bitmap.h>
#include <android/log.h>
#include <chrono>
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include "media_input.h"

struct MediaThumbnailRetrieverContext {
    AVFormatContext *formatContext;
    int videoStreamIndex;
    int rotationDegrees;
    int64_t deadline;
};

static void log_ffmpeg_error(const char *operation, int error) {
    char message[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(error, message, sizeof(message));
    __android_log_print(ANDROID_LOG_WARN, "ThumbnailNative", "%s: %s (%d)", operation, message, error);
}

static MediaThumbnailRetrieverContext *context_from_handle(jlong handle) {
    return reinterpret_cast<MediaThumbnailRetrieverContext *>(handle);
}

static jlong handle_from_context(MediaThumbnailRetrieverContext *context) {
    return reinterpret_cast<jlong>(context);
}

static int normalize_rotation(int rotation) {
    rotation %= 360;
    if (rotation < 0) {
        rotation += 360;
    }
    return rotation;
}

static int read_rotation_degrees(AVStream *stream) {
    if (!stream) {
        return 0;
    }

    int rotation = 0;
    AVDictionaryEntry *rotateTag = av_dict_get(stream->metadata, "rotate", nullptr, 0);
    if (rotateTag && rotateTag->value && *rotateTag->value) {
        rotation = normalize_rotation(atoi(rotateTag->value));
    }

    const AVPacketSideData *displayMatrix = av_packet_side_data_get(
            stream->codecpar->coded_side_data, stream->codecpar->nb_coded_side_data,
            AV_PKT_DATA_DISPLAYMATRIX);
    if (displayMatrix && displayMatrix->size >= 9 * sizeof(int32_t)) {
        double theta = av_display_rotation_get(reinterpret_cast<const int32_t *>(displayMatrix->data));
        rotation = normalize_rotation(static_cast<int>(-theta));
    }

    return rotation;
}

static jobject create_bitmap(JNIEnv *env, int width, int height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (!bitmapClass) {
        return nullptr;
    }

    jclass bitmapConfigClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmapConfigClass) {
        return nullptr;
    }

    jfieldID argb8888Field = env->GetStaticFieldID(
            bitmapConfigClass,
            "ARGB_8888",
            "Landroid/graphics/Bitmap$Config;");
    if (!argb8888Field) {
        return nullptr;
    }

    jobject argb8888Obj = env->GetStaticObjectField(bitmapConfigClass, argb8888Field);
    if (!argb8888Obj) {
        return nullptr;
    }

    jmethodID createBitmapMethod = env->GetStaticMethodID(
            bitmapClass,
            "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!createBitmapMethod) {
        return nullptr;
    }

    return env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, width, height, argb8888Obj);
}

static jobject frame_to_bitmap(JNIEnv *env, const AVFrame *frame) {
    if (!frame || frame->width <= 0 || frame->height <= 0) {
        return nullptr;
    }

    jobject bitmap = create_bitmap(env, frame->width, frame->height);
    if (!bitmap) {
        return nullptr;
    }

    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) < 0) {
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    void *bitmapPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) < 0 || !bitmapPixels) {
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    SwsContext *swsContext = sws_getContext(
            frame->width,
            frame->height,
            static_cast<AVPixelFormat>(frame->format),
            bitmapInfo.width,
            bitmapInfo.height,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr);

    if (!swsContext) {
        AndroidBitmap_unlockPixels(env, bitmap);
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    AVFrame *outputFrame = av_frame_alloc();
    if (!outputFrame) {
        sws_freeContext(swsContext);
        AndroidBitmap_unlockPixels(env, bitmap);
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    av_image_fill_arrays(
            outputFrame->data,
            outputFrame->linesize,
            static_cast<uint8_t *>(bitmapPixels),
            AV_PIX_FMT_RGBA,
            bitmapInfo.width,
            bitmapInfo.height,
            1);

    sws_scale(
            swsContext,
            frame->data,
            frame->linesize,
            0,
            frame->height,
            outputFrame->data,
            outputFrame->linesize);

    av_frame_free(&outputFrame);
    sws_freeContext(swsContext);
    AndroidBitmap_unlockPixels(env, bitmap);

    return bitmap;
}

static AVCodecContext *create_decoder_context(MediaThumbnailRetrieverContext *context) {
    if (!context || !context->formatContext || context->videoStreamIndex < 0) {
        return nullptr;
    }

    AVStream *videoStream = context->formatContext->streams[context->videoStreamIndex];
    if (!videoStream || !videoStream->codecpar) {
        return nullptr;
    }

    const AVCodec *decoder = avcodec_find_decoder(videoStream->codecpar->codec_id);
    if (!decoder) {
        return nullptr;
    }

    AVCodecContext *codecContext = avcodec_alloc_context3(decoder);
    if (!codecContext) {
        return nullptr;
    }

    if (avcodec_parameters_to_context(codecContext, videoStream->codecpar) < 0 ||
            avcodec_open2(codecContext, decoder, nullptr) < 0) {
        avcodec_free_context(&codecContext);
        return nullptr;
    }

    return codecContext;
}

static bool decode_next_frame(MediaThumbnailRetrieverContext *context,
        AVCodecContext *codecContext,
        AVPacket *packet,
        AVFrame *frame) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    for (;;) {
        if (std::chrono::steady_clock::now() >= deadline) {
            log_ffmpeg_error("decode deadline", AVERROR(ETIMEDOUT));
            return false;
        }
        int receiveResult = avcodec_receive_frame(codecContext, frame);
        if (receiveResult >= 0) return true;
        if (receiveResult != AVERROR(EAGAIN)) {
            log_ffmpeg_error("receive frame", receiveResult);
            return false;
        }

        int readResult;
        while ((readResult = av_read_frame(context->formatContext, packet)) >= 0 &&
               packet->stream_index != context->videoStreamIndex) {
            av_packet_unref(packet);
        }
        if (readResult < 0) {
            avcodec_send_packet(codecContext, nullptr);
            return avcodec_receive_frame(codecContext, frame) >= 0;
        }

        int sendResult = avcodec_send_packet(codecContext, packet);
        av_packet_unref(packet);
        // A seek can land before a usable keyframe; let the decoder resynchronize.
        if (sendResult < 0 && sendResult != AVERROR_INVALIDDATA) {
            log_ffmpeg_error("send packet", sendResult);
            return false;
        }
    }
}

static jobject decode_frame_at_time(JNIEnv *env, MediaThumbnailRetrieverContext *context, int64_t timeUs) {
    context->deadline = av_gettime_relative() + 10000000;
    AVCodecContext *codecContext = create_decoder_context(context);
    if (!codecContext) {
        return nullptr;
    }

    AVStream *videoStream = context->formatContext->streams[context->videoStreamIndex];
    int64_t startUs = context->formatContext->start_time;
    if (startUs == AV_NOPTS_VALUE) {
        startUs = videoStream->start_time == AV_NOPTS_VALUE ? 0
                : av_rescale_q(videoStream->start_time, videoStream->time_base, AV_TIME_BASE_Q);
    }
    if (startUs > 0 && timeUs > INT64_MAX - startUs) {
        avcodec_free_context(&codecContext);
        return nullptr;
    }
    int64_t targetTimestamp = av_rescale_q(timeUs + startUs, AV_TIME_BASE_Q, videoStream->time_base);
    __android_log_print(ANDROID_LOG_INFO, "ThumbnailNative",
            "seek relative=%lldus start=%lldus target=%lld time_base=%d/%d",
            static_cast<long long>(timeUs), static_cast<long long>(startUs),
            static_cast<long long>(targetTimestamp), videoStream->time_base.num, videoStream->time_base.den);
    int seekResult = av_seek_frame(context->formatContext, context->videoStreamIndex, targetTimestamp, AVSEEK_FLAG_BACKWARD);
    if (seekResult < 0) {
        log_ffmpeg_error("seek", seekResult);
        avcodec_free_context(&codecContext);
        return nullptr;
    }
    avcodec_flush_buffers(codecContext);

    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    if (!packet || !frame) {
        av_packet_free(&packet);
        av_frame_free(&frame);
        avcodec_free_context(&codecContext);
        return nullptr;
    }

    jobject result = nullptr;
    if (decode_next_frame(context, codecContext, packet, frame)) {
        result = frame_to_bitmap(env, frame);
    }

    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&codecContext);

    return result;
}

static jlong create_context_from_source(const char *source, int descriptor = -1) {
    auto *context = new MediaThumbnailRetrieverContext{};
    context->deadline = av_gettime_relative() + 10000000;
    AVFormatContext *formatContext = nullptr;
    int openResult = media_input_open(&formatContext, source, descriptor, &context->deadline);
    if (openResult < 0) {
        log_ffmpeg_error("open", openResult);
        delete context;
        return 0;
    }

    int probeResult = avformat_find_stream_info(formatContext, nullptr);
    if (probeResult < 0) {
        log_ffmpeg_error("probe", probeResult);
        media_input_close(&formatContext);
        delete context;
        return 0;
    }

    int videoStreamIndex = -1;
    for (unsigned int i = 0; i < formatContext->nb_streams; ++i) {
        AVStream *stream = formatContext->streams[i];
        if (!stream || !stream->codecpar) {
            continue;
        }
        if (stream->codecpar->codec_type != AVMEDIA_TYPE_VIDEO) {
            continue;
        }
        if ((stream->disposition & AV_DISPOSITION_ATTACHED_PIC) != 0) {
            continue;
        }
        videoStreamIndex = static_cast<int>(i);
        break;
    }

    if (videoStreamIndex < 0) {
        videoStreamIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    }

    context->formatContext = formatContext;
    context->videoStreamIndex = videoStreamIndex;
    context->rotationDegrees = (videoStreamIndex >= 0)
            ? read_rotation_degrees(formatContext->streams[videoStreamIndex])
            : 0;
    return handle_from_context(context);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_dev_anilbeesetti_nextplayer_core_thumbnail_FfmpegThumbnailRetriever_nativeCreateFromPath(
        JNIEnv *env,
        jobject thiz,
        jstring file_path) {
    const char *source = env->GetStringUTFChars(file_path, nullptr);
    jlong handle = create_context_from_source(source);
    env->ReleaseStringUTFChars(file_path, source);
    return handle;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_dev_anilbeesetti_nextplayer_core_thumbnail_FfmpegThumbnailRetriever_nativeCreateFromFD(
        JNIEnv *env,
        jobject thiz,
        jint file_descriptor) {
    return create_context_from_source(nullptr, file_descriptor);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_dev_anilbeesetti_nextplayer_core_thumbnail_FfmpegThumbnailRetriever_nativeGetDurationMillis(
        JNIEnv *, jobject, jlong handle) {
    auto *context = context_from_handle(handle);
    int64_t duration = context->formatContext->duration;
    return duration <= 0 ? 0 : av_rescale_q(duration, AV_TIME_BASE_Q, AVRational{1, 1000});
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_dev_anilbeesetti_nextplayer_core_thumbnail_FfmpegThumbnailRetriever_nativeGetEmbeddedPicture(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {
    auto *context = context_from_handle(handle);
    if (!context || !context->formatContext) {
        return nullptr;
    }

    for (unsigned int i = 0; i < context->formatContext->nb_streams; ++i) {
        AVStream *stream = context->formatContext->streams[i];
        if (!stream) {
            continue;
        }
        if ((stream->disposition & AV_DISPOSITION_ATTACHED_PIC) == 0) {
            continue;
        }

        AVPacket attachedPic = stream->attached_pic;
        if (!attachedPic.data || attachedPic.size <= 0) {
            continue;
        }

        jbyteArray result = env->NewByteArray(attachedPic.size);
        if (!result) {
            return nullptr;
        }

        env->SetByteArrayRegion(result, 0, attachedPic.size, reinterpret_cast<const jbyte *>(attachedPic.data));
        return result;
    }

    return nullptr;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_dev_anilbeesetti_nextplayer_core_thumbnail_FfmpegThumbnailRetriever_nativeGetFrameAtTime(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jlong time_us) {
    auto *context = context_from_handle(handle);
    if (!context || !context->formatContext || context->videoStreamIndex < 0) {
        return nullptr;
    }

    return decode_frame_at_time(env, context, time_us);
}

extern "C"
JNIEXPORT jint JNICALL
Java_dev_anilbeesetti_nextplayer_core_thumbnail_FfmpegThumbnailRetriever_nativeGetRotationDegrees(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {
    auto *context = context_from_handle(handle);
    if (!context) {
        return 0;
    }
    return context->rotationDegrees;
}

extern "C"
JNIEXPORT void JNICALL
Java_dev_anilbeesetti_nextplayer_core_thumbnail_FfmpegThumbnailRetriever_nativeRelease(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {
    auto *context = context_from_handle(handle);
    if (!context) {
        return;
    }

    if (context->formatContext) {
        media_input_close(&context->formatContext);
    }

    delete context;
}
