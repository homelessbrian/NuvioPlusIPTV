/*
 * Seek thumbnails: one keyframe at a time, read straight from the video link and decoded in software,
 * with the FFmpeg that MPV already ships in the APK (libavformat / libavcodec / libswscale / libavutil).
 * Nothing is linked: the libraries are opened with dlopen and their major versions checked against the
 * headers in ffmpeg/ before anything is called. Idea from Nuvio Arc's thumbnail decoder (GPL-3).
 *
 * Why: Android's MediaMetadataRetriever downloads far more of a remote file than one picture needs and
 * decodes on the TV's video decoder, which the playing video may need. FFmpeg seeks with the file's own
 * index (MKV cues, MP4 sample table), reads only the keyframe, and decodes it on the CPU.
 */
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <time.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "SeekThumbNative", __VA_ARGS__)
#else
#define LOGW(...) fprintf(stderr, __VA_ARGS__)
#endif

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/dict.h"
#include "libavutil/mathematics.h"
#include "libswscale/swscale.h"
}

namespace {

#define FF_FUNCS(X) \
    X(avutil_version) X(avcodec_version) X(avformat_version) X(swscale_version) \
    X(av_log_set_level) X(av_dict_set) X(av_dict_free) X(av_rescale_q) \
    X(av_packet_alloc) X(av_packet_free) X(av_packet_unref) X(av_frame_alloc) X(av_frame_free) X(av_frame_unref) \
    X(avcodec_find_decoder) X(avcodec_alloc_context3) X(avcodec_parameters_to_context) X(avcodec_open2) \
    X(avcodec_send_packet) X(avcodec_receive_frame) X(avcodec_flush_buffers) X(avcodec_free_context) \
    X(avformat_network_init) X(avformat_alloc_context) X(avformat_open_input) X(avformat_find_stream_info) \
    X(av_find_best_stream) X(av_seek_frame) X(av_read_frame) X(avformat_close_input) \
    X(sws_getCachedContext) X(sws_scale) X(sws_freeContext)

struct Api {
#define DECL(name) decltype(&::name) name = nullptr;
    FF_FUNCS(DECL)
#undef DECL
};

Api ff;
bool g_ready = false;
bool g_tried = false;
char g_error[256] = "";
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

void fail(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_error, sizeof g_error, fmt, ap);
    va_end(ap);
    LOGW("%s", g_error);
}

bool load() {
    const char *names[] = {"libavutil.so", "libswresample.so", "libavcodec.so", "libavformat.so", "libswscale.so"};
    void *h[5] = {};
    for (int i = 0; i < 5; i++) {
        h[i] = dlopen(names[i], RTLD_NOW | RTLD_GLOBAL);
        if (!h[i] && i != 1) {
            fail("can't open %s: %s", names[i], dlerror());
            return false;
        }
    }
    bool ok = true;
#define RESOLVE(name) \
    for (int i = 0; i < 5 && !ff.name; i++) if (h[i]) ff.name = reinterpret_cast<decltype(&::name)>(dlsym(h[i], #name)); \
    if (!ff.name) { fail("missing %s", #name); ok = false; }
    FF_FUNCS(RESOLVE)
#undef RESOLVE
    if (!ok) return false;
    // Struct layouts come from the headers: the libraries must be the same major versions.
    struct { unsigned have; unsigned want; const char *lib; } checks[] = {
        {ff.avutil_version() >> 16, LIBAVUTIL_VERSION_MAJOR, "avutil"},
        {ff.avcodec_version() >> 16, LIBAVCODEC_VERSION_MAJOR, "avcodec"},
        {ff.avformat_version() >> 16, LIBAVFORMAT_VERSION_MAJOR, "avformat"},
        {ff.swscale_version() >> 16, LIBSWSCALE_VERSION_MAJOR, "swscale"},
    };
    for (auto &c : checks) {
        if (c.have != c.want) {
            fail("%s %u, built for %u", c.lib, c.have, c.want);
            return false;
        }
    }
    ff.av_log_set_level(AV_LOG_QUIET);
    ff.avformat_network_init();
    return true;
}

bool ensure() {
    pthread_mutex_lock(&g_lock);
    if (!g_tried) {
        g_tried = true;
        g_ready = load();
    }
    bool r = g_ready;
    pthread_mutex_unlock(&g_lock);
    return r;
}

int64_t now_us() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

struct Reader {
    AVFormatContext *fmt = nullptr;
    AVCodecContext *dec = nullptr;
    AVStream *st = nullptr;
    int vidx = -1;
    AVPacket *pkt = nullptr;
    AVFrame *frame = nullptr;
    SwsContext *sws = nullptr;
    std::atomic<bool> abort{false};
    std::atomic<int64_t> deadline{0};
    bool pq = false;
    bool hlg = false;
    uint8_t lut[256];
    uint8_t *rgb = nullptr;
    int rgb_cap = 0;
};

int interrupt_cb(void *opaque) {
    auto *r = static_cast<Reader *>(opaque);
    if (r->abort.load()) return 1;
    int64_t d = r->deadline.load();
    return d > 0 && now_us() > d ? 1 : 0;
}

/** PQ (HDR10 / Dolby Vision base) to SDR: a soft roll-off from about 1000 nits, then gamma 2.2. */
void build_pq_lut(uint8_t *lut) {
    const double m1 = 2610.0 / 16384, m2 = 2523.0 / 4096 * 128, c1 = 3424.0 / 4096, c2 = 2413.0 / 4096 * 32,
                 c3 = 2392.0 / 4096 * 32;
    const double white = 203.0, peak = 1000.0 / white;
    for (int i = 0; i < 256; i++) {
        double e = std::pow(i / 255.0, 1.0 / m2);
        double nits = 10000.0 * std::pow(std::fmax(e - c1, 0.0) / (c2 - c3 * e), 1.0 / m1);
        double x = nits / white;
        double y = x * (1.0 + x / (peak * peak)) / (1.0 + x);
        double v = std::pow(std::fmin(std::fmax(y, 0.0), 1.0), 1.0 / 2.2);
        lut[i] = static_cast<uint8_t>(std::lround(v * 255.0));
    }
}

/** HLG shown on SDR: slightly darker mid-tones. */
void build_hlg_lut(uint8_t *lut) {
    for (int i = 0; i < 256; i++) lut[i] = static_cast<uint8_t>(std::lround(std::pow(i / 255.0, 1.15) * 255.0));
}

void close_reader(Reader *r) {
    if (!r) return;
    if (r->sws) ff.sws_freeContext(r->sws);
    if (r->frame) ff.av_frame_free(&r->frame);
    if (r->pkt) ff.av_packet_free(&r->pkt);
    if (r->dec) ff.avcodec_free_context(&r->dec);
    if (r->fmt) ff.avformat_close_input(&r->fmt);
    delete[] r->rgb;
    delete r;
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

/** Returns 0 with a picture in the reader's frame, or a negative error. */
int decode_at(Reader *r, int64_t ms) {
    int64_t start = r->st->start_time != AV_NOPTS_VALUE ? r->st->start_time : 0;
    int64_t ts = start + ff.av_rescale_q(ms * 1000, AVRational{1, 1000000}, r->st->time_base);
    if (ff.av_seek_frame(r->fmt, r->vidx, ts, AVSEEK_FLAG_BACKWARD) < 0 &&
        ff.av_seek_frame(r->fmt, r->vidx, ts, AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_ANY) < 0) {
        return -2;
    }
    ff.avcodec_flush_buffers(r->dec);
    // The first keyframe of the video stream after the seek point; a few hundred packets at most.
    for (int n = 0; n < 600; n++) {
        int err = ff.av_read_frame(r->fmt, r->pkt);
        if (err < 0) return err;
        bool key = r->pkt->stream_index == r->vidx && (r->pkt->flags & AV_PKT_FLAG_KEY);
        if (!key) {
            ff.av_packet_unref(r->pkt);
            continue;
        }
        err = ff.avcodec_send_packet(r->dec, r->pkt);
        ff.av_packet_unref(r->pkt);
        if (err < 0) return err;
        // Drain: the decoder hands back the keyframe now instead of waiting for the frames after it.
        ff.avcodec_send_packet(r->dec, nullptr);
        err = ff.avcodec_receive_frame(r->dec, r->frame);
        ff.avcodec_flush_buffers(r->dec);
        return err;
    }
    return -3;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_nuvio_tv_livetv_thumbs_NativeThumbs_nativeInit(JNIEnv *env, jclass) {
    if (ensure()) return nullptr;
    return env->NewStringUTF(g_error[0] ? g_error : "not loaded");
}

/**
 * Opens [url] (a link or a file path). headers: "Name: value\r\n" lines. info gets
 * width, height, duration ms. Returns the reader, or 0.
 */
JNIEXPORT jlong JNICALL
Java_com_nuvio_tv_livetv_thumbs_NativeThumbs_nativeOpen(JNIEnv *env, jclass, jstring jurl, jstring jheaders,
                                                       jstring juserAgent, jint timeoutMs, jintArray jinfo) {
    if (!ensure()) return 0;
    std::string url = jstr(env, jurl), headers = jstr(env, jheaders), ua = jstr(env, juserAgent);
    auto *r = new Reader();
    r->fmt = ff.avformat_alloc_context();
    if (!r->fmt) { delete r; return 0; }
    r->fmt->interrupt_callback.callback = interrupt_cb;
    r->fmt->interrupt_callback.opaque = r;
    r->deadline = now_us() + static_cast<int64_t>(timeoutMs) * 1000;
    AVDictionary *opts = nullptr;
    if (!headers.empty()) ff.av_dict_set(&opts, "headers", headers.c_str(), 0);
    if (!ua.empty()) ff.av_dict_set(&opts, "user_agent", ua.c_str(), 0);
    ff.av_dict_set(&opts, "reconnect", "1", 0);
    ff.av_dict_set(&opts, "multiple_requests", "1", 0);
    // Ask for small byte ranges, so a seek downloads about one keyframe, not the rest of the file.
    ff.av_dict_set(&opts, "initial_request_size", "262144", 0);
    ff.av_dict_set(&opts, "request_size", "1048576", 0);
    ff.av_dict_set(&opts, "rw_timeout", "15000000", 0);
    ff.av_dict_set(&opts, "probesize", "2000000", 0);
    ff.av_dict_set(&opts, "analyzeduration", "1000000", 0);
    int err = ff.avformat_open_input(&r->fmt, url.c_str(), nullptr, &opts);
    ff.av_dict_free(&opts);
    if (err < 0) {
        r->fmt = nullptr;  // freed by avformat_open_input on failure
        close_reader(r);
        return 0;
    }
    r->vidx = ff.av_find_best_stream(r->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (r->vidx >= 0 && r->fmt->streams[r->vidx]->codecpar->width <= 0) {
        ff.avformat_find_stream_info(r->fmt, nullptr);
        r->vidx = ff.av_find_best_stream(r->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    }
    if (r->vidx < 0) { close_reader(r); return 0; }
    r->st = r->fmt->streams[r->vidx];
    const AVCodec *codec = ff.avcodec_find_decoder(r->st->codecpar->codec_id);
    if (!codec) { close_reader(r); return 0; }
    r->dec = ff.avcodec_alloc_context3(codec);
    if (!r->dec || ff.avcodec_parameters_to_context(r->dec, r->st->codecpar) < 0) { close_reader(r); return 0; }
    r->dec->skip_frame = AVDISCARD_NONKEY;
    r->dec->skip_loop_filter = AVDISCARD_ALL;
    r->dec->thread_count = 2;
    r->dec->thread_type = FF_THREAD_SLICE;
    if (ff.avcodec_open2(r->dec, codec, nullptr) < 0) { close_reader(r); return 0; }
    r->pkt = ff.av_packet_alloc();
    r->frame = ff.av_frame_alloc();
    if (!r->pkt || !r->frame) { close_reader(r); return 0; }
    // Only the video stream is read.
    for (unsigned i = 0; i < r->fmt->nb_streams; i++)
        if (static_cast<int>(i) != r->vidx) r->fmt->streams[i]->discard = AVDISCARD_ALL;
    AVColorTransferCharacteristic trc = r->st->codecpar->color_trc;
    r->pq = trc == AVCOL_TRC_SMPTE2084;
    r->hlg = trc == AVCOL_TRC_ARIB_STD_B67;
    if (r->pq) build_pq_lut(r->lut);
    else if (r->hlg) build_hlg_lut(r->lut);
    r->deadline = 0;
    jint info[3] = {r->st->codecpar->width, r->st->codecpar->height,
                    static_cast<jint>(r->fmt->duration > 0 ? r->fmt->duration / 1000 : 0)};
    if (jinfo && env->GetArrayLength(jinfo) >= 3) env->SetIntArrayRegion(jinfo, 0, 3, info);
    return reinterpret_cast<jlong>(r);
}

/**
 * The keyframe at or before [ms], scaled to fit maxW x maxH (keeping its shape) as RGB_565 into [dst]
 * (a direct buffer of at least maxW * maxH * 2 bytes). size gets the picture's width and height.
 * Returns 0, or a negative error.
 */
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_livetv_thumbs_NativeThumbs_nativeFrame(JNIEnv *env, jclass, jlong handle, jlong ms, jint maxW,
                                                        jint maxH, jobject dst, jint timeoutMs, jintArray jsize) {
    auto *r = reinterpret_cast<Reader *>(handle);
    if (!r || r->abort.load()) return -1;
    auto *out = static_cast<uint8_t *>(env->GetDirectBufferAddress(dst));
    if (!out || env->GetDirectBufferCapacity(dst) < static_cast<jlong>(maxW) * maxH * 2) return -1;
    r->deadline = now_us() + static_cast<int64_t>(timeoutMs) * 1000;
    int err = decode_at(r, ms);
    r->deadline = 0;
    if (err < 0) return err;
    AVFrame *f = r->frame;
    if (f->width <= 0 || f->height <= 0) { ff.av_frame_unref(f); return -4; }
    double sar = f->sample_aspect_ratio.num > 0 && f->sample_aspect_ratio.den > 0
        ? static_cast<double>(f->sample_aspect_ratio.num) / f->sample_aspect_ratio.den : 1.0;
    double shownW = f->width * sar;
    double scale = std::fmin(maxW / shownW, static_cast<double>(maxH) / f->height);
    int w = std::max(2, static_cast<int>(shownW * scale)) & ~1;
    int h = std::max(2, static_cast<int>(f->height * scale)) & ~1;
    r->sws = ff.sws_getCachedContext(r->sws, f->width, f->height, static_cast<AVPixelFormat>(f->format), w, h,
                                     AV_PIX_FMT_RGB24, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!r->sws) { ff.av_frame_unref(f); return -5; }
    int need = w * h * 3;
    if (need > r->rgb_cap) {
        delete[] r->rgb;
        r->rgb = new uint8_t[need];
        r->rgb_cap = need;
    }
    uint8_t *planes[4] = {r->rgb, nullptr, nullptr, nullptr};
    int strides[4] = {w * 3, 0, 0, 0};
    ff.sws_scale(r->sws, f->data, f->linesize, 0, f->height, planes, strides);
    ff.av_frame_unref(f);
    bool tone = r->pq || r->hlg;
    auto *px = reinterpret_cast<uint16_t *>(out);
    for (int i = 0, n = w * h; i < n; i++) {
        uint8_t cr = r->rgb[i * 3], cg = r->rgb[i * 3 + 1], cb = r->rgb[i * 3 + 2];
        if (tone) { cr = r->lut[cr]; cg = r->lut[cg]; cb = r->lut[cb]; }
        px[i] = static_cast<uint16_t>(((cr >> 3) << 11) | ((cg >> 2) << 5) | (cb >> 3));
    }
    jint size[2] = {w, h};
    if (jsize && env->GetArrayLength(jsize) >= 2) env->SetIntArrayRegion(jsize, 0, 2, size);
    return 0;
}

/** Stops whatever the reader is doing (from any thread); the next call fails. */
JNIEXPORT void JNICALL
Java_com_nuvio_tv_livetv_thumbs_NativeThumbs_nativeAbort(JNIEnv *, jclass, jlong handle) {
    auto *r = reinterpret_cast<Reader *>(handle);
    if (r) r->abort = true;
}

JNIEXPORT void JNICALL
Java_com_nuvio_tv_livetv_thumbs_NativeThumbs_nativeClose(JNIEnv *, jclass, jlong handle) {
    close_reader(reinterpret_cast<Reader *>(handle));
}

}  // extern "C"
