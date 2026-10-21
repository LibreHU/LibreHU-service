/*
 * USB (UVC) cameras for LibreHU-service's rear view: V4L2 capture of /dev/videoN (uvcvideo is built into the
 * LibreHU and Jancar kernels, CONFIG_USB_VIDEO_CLASS=y), memory-mapped buffers, MJPEG first (decoded by Android's
 * BitmapFactory), else YUYV converted here into an ARGB_8888 bitmap. References: linux/videodev2.h, the kernel's
 * "V4L2 video capture example" (Documentation/media/uapi/v4l/capture.c).
 */
#include <android/bitmap.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#define JNI_FN(name) Java_org_librehu_service_hw_NativeUvc_##name
#define MAX_STREAMS 4
#define BUFFERS 4

struct stream {
    int fd;
    uint32_t format;
    int width;
    int height;
    int count;
    void *start[BUFFERS];
    size_t length[BUFFERS];
};

static struct stream streams[MAX_STREAMS];

static int xioctl(int fd, unsigned long request, void *arg) {
    int r;
    do {
        r = ioctl(fd, request, arg);
    } while (r < 0 && errno == EINTR);
    return r;
}

static struct stream *find(int fd) {
    for (int i = 0; i < MAX_STREAMS; i++) {
        if (streams[i].fd == fd && fd > 0) return &streams[i];
    }
    return NULL;
}

static void release(struct stream *s) {
    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    xioctl(s->fd, VIDIOC_STREAMOFF, &type);
    for (int i = 0; i < s->count; i++) {
        if (s->start[i] && s->start[i] != MAP_FAILED) munmap(s->start[i], s->length[i]);
    }
    close(s->fd);
    memset(s, 0, sizeof(*s));
}

/* Opens [path] at about width x height and fps: MJPEG, else YUYV. Returns the fd, or -errno. */
JNIEXPORT jint JNICALL JNI_FN(open)(JNIEnv *env, jclass clazz, jstring jpath, jint width, jint height, jint fps) {
    (void) clazz;
    struct stream *s = NULL;
    for (int i = 0; i < MAX_STREAMS; i++) {
        if (streams[i].fd <= 0) {
            s = &streams[i];
            break;
        }
    }
    if (!s) return -EMFILE;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return -errno;
    memset(s, 0, sizeof(*s));
    s->fd = fd;

    struct v4l2_capability cap;
    memset(&cap, 0, sizeof(cap));
    if (xioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) {
        int e = errno;
        release(s);
        return -e;
    }
    uint32_t caps = (cap.capabilities & V4L2_CAP_DEVICE_CAPS) ? cap.device_caps : cap.capabilities;
    if (!(caps & V4L2_CAP_VIDEO_CAPTURE) || !(caps & V4L2_CAP_STREAMING)) {
        release(s);
        return -ENODEV;
    }

    const uint32_t wanted[] = {V4L2_PIX_FMT_MJPEG, V4L2_PIX_FMT_YUYV};
    int ok = 0;
    for (int i = 0; i < 2 && !ok; i++) {
        struct v4l2_format f;
        memset(&f, 0, sizeof(f));
        f.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        f.fmt.pix.width = width;
        f.fmt.pix.height = height;
        f.fmt.pix.pixelformat = wanted[i];
        f.fmt.pix.field = V4L2_FIELD_ANY;
        if (xioctl(fd, VIDIOC_S_FMT, &f) == 0 && f.fmt.pix.pixelformat == wanted[i]) {
            s->format = f.fmt.pix.pixelformat;
            s->width = f.fmt.pix.width;
            s->height = f.fmt.pix.height;
            ok = 1;
        }
    }
    if (!ok) {
        release(s);
        return -EINVAL;
    }

    if (fps > 0) {
        struct v4l2_streamparm p;
        memset(&p, 0, sizeof(p));
        p.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        p.parm.capture.timeperframe.numerator = 1;
        p.parm.capture.timeperframe.denominator = fps;
        xioctl(fd, VIDIOC_S_PARM, &p); /* best effort */
    }

    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.count = BUFFERS;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (xioctl(fd, VIDIOC_REQBUFS, &req) < 0 || req.count < 2) {
        int e = errno ? errno : ENOMEM;
        release(s);
        return -e;
    }
    s->count = req.count > BUFFERS ? BUFFERS : (int) req.count;
    for (int i = 0; i < s->count; i++) {
        struct v4l2_buffer b;
        memset(&b, 0, sizeof(b));
        b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        b.memory = V4L2_MEMORY_MMAP;
        b.index = i;
        if (xioctl(fd, VIDIOC_QUERYBUF, &b) < 0) {
            int e = errno;
            release(s);
            return -e;
        }
        s->length[i] = b.length;
        s->start[i] = mmap(NULL, b.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, b.m.offset);
        if (s->start[i] == MAP_FAILED) {
            int e = errno;
            release(s);
            return -e;
        }
        if (xioctl(fd, VIDIOC_QBUF, &b) < 0) {
            int e = errno;
            release(s);
            return -e;
        }
    }
    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd, VIDIOC_STREAMON, &type) < 0) {
        int e = errno;
        release(s);
        return -e;
    }
    return fd;
}

/* [fourcc, width, height] of an open stream. */
JNIEXPORT jintArray JNICALL JNI_FN(format)(JNIEnv *env, jclass clazz, jint fd) {
    (void) clazz;
    struct stream *s = find(fd);
    jintArray out = (*env)->NewIntArray(env, 3);
    if (!s || !out) return out;
    jint v[3] = {(jint) s->format, s->width, s->height};
    (*env)->SetIntArrayRegion(env, out, 0, 3, v);
    return out;
}

static inline uint8_t clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : (uint8_t) v); }

/*
 * Waits up to timeoutMs for a frame. MJPEG: copies the JPEG into jpeg and returns its length. YUYV: converts it into
 * bitmap (ARGB_8888, the stream's size) and returns 0. -EAGAIN on timeout, -errno on error, -ENOSPC when jpeg is too
 * small.
 */
JNIEXPORT jint JNICALL JNI_FN(read)(JNIEnv *env, jclass clazz, jint fd, jbyteArray jpeg, jobject bitmap, jint timeoutMs) {
    (void) clazz;
    struct stream *s = find(fd);
    if (!s) return -EBADF;
    struct pollfd p = {.fd = fd, .events = POLLIN};
    int r = poll(&p, 1, timeoutMs);
    if (r == 0) return -EAGAIN;
    if (r < 0) return -errno;
    struct v4l2_buffer b;
    memset(&b, 0, sizeof(b));
    b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    b.memory = V4L2_MEMORY_MMAP;
    if (xioctl(fd, VIDIOC_DQBUF, &b) < 0) return errno == EAGAIN ? -EAGAIN : -errno;
    int result = 0;
    const uint8_t *data = s->start[b.index];
    if (s->format == V4L2_PIX_FMT_MJPEG) {
        jsize cap = (*env)->GetArrayLength(env, jpeg);
        if ((jsize) b.bytesused > cap) {
            result = -ENOSPC;
        } else {
            (*env)->SetByteArrayRegion(env, jpeg, 0, (jsize) b.bytesused, (const jbyte *) data);
            result = (int) b.bytesused;
        }
    } else if (bitmap) {
        AndroidBitmapInfo info;
        void *pixels = NULL;
        if (AndroidBitmap_getInfo(env, bitmap, &info) == 0 && info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            (int) info.width == s->width && (int) info.height == s->height &&
            AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0) {
            /* YUYV (YUV 4:2:2, BT.601): Y0 U Y1 V for two pixels. */
            for (int y = 0; y < s->height; y++) {
                const uint8_t *in = data + (size_t) y * s->width * 2;
                uint8_t *out = (uint8_t *) pixels + (size_t) y * info.stride;
                for (int x = 0; x < s->width; x += 2) {
                    int y0 = in[0] - 16, u = in[1] - 128, y1 = in[2] - 16, v = in[3] - 128;
                    int rv = 409 * v, gu = -100 * u - 208 * v, bu = 516 * u;
                    int c0 = 298 * y0, c1 = 298 * y1;
                    out[0] = clamp((c0 + rv + 128) >> 8);
                    out[1] = clamp((c0 + gu + 128) >> 8);
                    out[2] = clamp((c0 + bu + 128) >> 8);
                    out[3] = 255;
                    out[4] = clamp((c1 + rv + 128) >> 8);
                    out[5] = clamp((c1 + gu + 128) >> 8);
                    out[6] = clamp((c1 + bu + 128) >> 8);
                    out[7] = 255;
                    in += 4;
                    out += 8;
                }
            }
            AndroidBitmap_unlockPixels(env, bitmap);
        } else {
            result = -EINVAL;
        }
    }
    xioctl(fd, VIDIOC_QBUF, &b);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(close)(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    struct stream *s = find(fd);
    if (s) release(s);
}
