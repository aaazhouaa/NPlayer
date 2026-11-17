// SPDX-License-Identifier: GPL-3.0-or-later
// NextLib d8fc329c4bbbf4a83bd44dda5337ea4a2685025a, mediainfo/src/main/cpp/utils.cpp
#include "media_input.h"
#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/time.h>
}

namespace {
int interrupt_input(void *opaque) {
    return av_gettime_relative() >= *static_cast<int64_t *>(opaque);
}

struct DescriptorInput {
    int fd;
    int64_t position = 0;
    bool seekable;
    int64_t *deadline;
};

int descriptor_read(void *opaque, uint8_t *buffer, int size) {
    auto *input = static_cast<DescriptorInput *>(opaque);
    if (av_gettime_relative() >= *input->deadline) return AVERROR_EXIT;
    if (!input->seekable) return AVERROR(ENOSYS);
    ssize_t count;
    do {
        count = input->seekable ? pread64(input->fd, buffer, size, input->position)
                                : read(input->fd, buffer, size);
    } while (count < 0 && errno == EINTR);
    if (count < 0) return AVERROR(errno);
    input->position += count;
    return count ? static_cast<int>(count) : AVERROR_EOF;
}

int64_t descriptor_seek(void *opaque, int64_t offset, int whence) {
    auto *input = static_cast<DescriptorInput *>(opaque);
    if (av_gettime_relative() >= *input->deadline) return AVERROR_EXIT;
    struct stat64 status;
    if (fstat64(input->fd, &status) < 0) return AVERROR(errno);
    if (!input->seekable || !S_ISREG(status.st_mode)) return AVERROR(ENOSYS);
    whence &= ~AVSEEK_FORCE;
    if (whence == AVSEEK_SIZE) return status.st_size;
    int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? input->position
                   : whence == SEEK_END ? status.st_size : -1;
    int64_t position;
    if (base < 0 || __builtin_add_overflow(base, offset, &position) || position < 0) return AVERROR(EINVAL);
    return input->position = position;
}

void descriptor_close(AVIOContext *io) {
    if (!io) return;
    auto *input = static_cast<DescriptorInput *>(io->opaque);
    close(input->fd);
    delete input;
    av_freep(&io->buffer);
    avio_context_free(&io);
}
}

int media_input_open(AVFormatContext **context, const char *source, int descriptor, int64_t *deadline) {
    *context = avformat_alloc_context();
    if (!*context) return AVERROR(ENOMEM);
    (*context)->interrupt_callback = {interrupt_input, deadline};
    if (descriptor < 0) return avformat_open_input(context, source, nullptr, nullptr);
    int fd = fcntl(descriptor, F_DUPFD_CLOEXEC, 0);
    if (fd < 0) {
        int error = AVERROR(errno);
        avformat_free_context(*context);
        *context = nullptr;
        return error;
    }
    auto *input = new DescriptorInput{fd, 0, lseek64(fd, 0, SEEK_CUR) >= 0, deadline};
    auto *buffer = static_cast<uint8_t *>(av_malloc(64 * 1024));
    AVIOContext *io = buffer ? avio_alloc_context(buffer, 64 * 1024, 0, input,
                                                 descriptor_read, nullptr, descriptor_seek) : nullptr;
    if (!io) {
        av_free(buffer);
        close(fd);
        delete input;
        avformat_free_context(*context);
        *context = nullptr;
        return AVERROR(ENOMEM);
    }
    io->seekable = input->seekable ? AVIO_SEEKABLE_NORMAL : 0;
    (*context)->pb = io;
    (*context)->flags |= AVFMT_FLAG_CUSTOM_IO;
    int result = avformat_open_input(context, nullptr, nullptr, nullptr);
    if (result < 0) descriptor_close(io);
    return result;
}

void media_input_close(AVFormatContext **context) {
    AVIOContext *io = *context && ((*context)->flags & AVFMT_FLAG_CUSTOM_IO) ? (*context)->pb : nullptr;
    avformat_close_input(context);
    descriptor_close(io);
}
