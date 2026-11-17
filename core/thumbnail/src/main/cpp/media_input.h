#pragma once

extern "C" {
#include <libavformat/avformat.h>
}

int media_input_open(AVFormatContext **context, const char *source, int descriptor, int64_t *deadline);
void media_input_close(AVFormatContext **context);
