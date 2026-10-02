/*
 * Low-level access for LibreHU-service: MCU serial port, SoC GPIOs and I2C.
 *
 * GPIO: /dev/gpios_ioctl (Autochips/Jancar kernel module). The argument is a pointer to the GPIO number, as in
 * libJanCarIVI.so GPIO::set/get: 0x6B00 = drive high, 0x6B01 = drive low, 0x6B02 = switch to input,
 * 0x6B03 = read level (returned by ioctl). Reading therefore switches the pin to input first.
 *
 * I2C: like libJanCarIVI I2C::open/write: I2C_SLAVE_FORCE then I2C_RDWR transfers.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/i2c.h>
#include <linux/i2c-dev.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

#define GPIO_DEV "/dev/gpios_ioctl"
#define GPIO_HIGH 0x6B00
#define GPIO_LOW 0x6B01
#define GPIO_INPUT 0x6B02
#define GPIO_READ 0x6B03

#define JNI_FN(name) Java_org_librehu_service_hw_NativeHw_##name

static speed_t baud_constant(int baud) {
    switch (baud) {
        case 9600: return B9600;
        case 19200: return B19200;
        case 38400: return B38400;
        case 57600: return B57600;
        case 115200: return B115200;
        case 230400: return B230400;
        case 460800: return B460800;
        default: return 0;
    }
}

/* Opens a tty in raw 8N1 mode. Returns the fd or -errno. */
JNIEXPORT jint JNICALL JNI_FN(serialOpen)(JNIEnv *env, jclass clazz, jstring jpath, jint baud) {
    (void) clazz;
    speed_t speed = baud_constant(baud);
    if (speed == 0) return -EINVAL;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open(path, O_RDWR | O_NOCTTY | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return -errno;
    struct termios tio;
    if (tcgetattr(fd, &tio) != 0) {
        int err = errno;
        close(fd);
        return -err;
    }
    cfmakeraw(&tio);
    tio.c_cflag |= CLOCAL | CREAD;
    tio.c_cflag &= ~(CSTOPB | PARENB | CRTSCTS);
    tio.c_cc[VMIN] = 0;
    tio.c_cc[VTIME] = 0;
    cfsetispeed(&tio, speed);
    cfsetospeed(&tio, speed);
    if (tcsetattr(fd, TCSANOW, &tio) != 0) {
        int err = errno;
        close(fd);
        return -err;
    }
    tcflush(fd, TCIOFLUSH);
    return fd;
}

/* Waits up to timeoutMs for data. Returns bytes read, 0 on timeout, -errno on error. */
JNIEXPORT jint JNICALL JNI_FN(read)(JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint timeoutMs) {
    (void) clazz;
    struct pollfd pfd = {.fd = fd, .events = POLLIN};
    int r = poll(&pfd, 1, timeoutMs);
    if (r < 0) return errno == EINTR ? 0 : -errno;
    if (r == 0) return 0;
    if (pfd.revents & (POLLERR | POLLNVAL)) return -EIO;
    jsize len = (*env)->GetArrayLength(env, buffer);
    jbyte tmp[1024];
    if (len > (jsize) sizeof(tmp)) len = sizeof(tmp);
    ssize_t n = read(fd, tmp, (size_t) len);
    if (n < 0) return errno == EAGAIN || errno == EINTR ? 0 : -errno;
    (*env)->SetByteArrayRegion(env, buffer, 0, (jsize) n, tmp);
    return (jint) n;
}

/* Writes all bytes. Returns the count written or -errno. */
JNIEXPORT jint JNICALL JNI_FN(write)(JNIEnv *env, jclass clazz, jint fd, jbyteArray data) {
    (void) clazz;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    jsize done = 0;
    int err = 0;
    while (done < len) {
        ssize_t n = write(fd, bytes + done, (size_t) (len - done));
        if (n < 0) {
            if (errno == EINTR) continue;
            err = errno;
            break;
        }
        done += (jsize) n;
    }
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return err ? -err : done;
}

JNIEXPORT void JNICALL JNI_FN(close)(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd >= 0) close(fd);
}

static int gpio_fd = -1;

static int gpio_open(void) {
    if (gpio_fd < 0) gpio_fd = open(GPIO_DEV, O_RDWR | O_CLOEXEC);
    return gpio_fd;
}

/* Drives a GPIO. Returns 0 or -errno. */
JNIEXPORT jint JNICALL JNI_FN(gpioSet)(JNIEnv *env, jclass clazz, jint gpio, jboolean high) {
    (void) env;
    (void) clazz;
    int fd = gpio_open();
    if (fd < 0) return -errno;
    int n = gpio;
    return ioctl(fd, high ? GPIO_HIGH : GPIO_LOW, &n) == 0 ? 0 : -errno;
}

/* Switches a GPIO to input and reads it. Returns 0/1 or -errno. */
JNIEXPORT jint JNICALL JNI_FN(gpioRead)(JNIEnv *env, jclass clazz, jint gpio) {
    (void) env;
    (void) clazz;
    int fd = gpio_open();
    if (fd < 0) return -errno;
    int n = gpio;
    if (ioctl(fd, GPIO_INPUT, &n) != 0) return -errno;
    n = gpio;
    int r = ioctl(fd, GPIO_READ, &n);
    if (r < 0) return -errno;
    return r ? 1 : 0;
}

/* Opens /dev/i2c-<bus> for a 7-bit address. Returns the fd or -errno. */
JNIEXPORT jint JNICALL JNI_FN(i2cOpen)(JNIEnv *env, jclass clazz, jint bus, jint address) {
    (void) env;
    (void) clazz;
    char path[32];
    snprintf(path, sizeof(path), "/dev/i2c-%d", bus);
    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) return -errno;
    if (ioctl(fd, I2C_SLAVE_FORCE, address) < 0) {
        int err = errno;
        close(fd);
        return -err;
    }
    return fd;
}

/* Writes one 8-bit register. Returns 0 or -errno. */
JNIEXPORT jint JNICALL JNI_FN(i2cWriteReg)(JNIEnv *env, jclass clazz, jint fd, jint address, jint reg, jint value) {
    (void) env;
    (void) clazz;
    unsigned char buf[2] = {(unsigned char) reg, (unsigned char) value};
    struct i2c_msg msg = {.addr = (unsigned short) address, .flags = 0, .len = 2, .buf = buf};
    struct i2c_rdwr_ioctl_data xfer = {.msgs = &msg, .nmsgs = 1};
    return ioctl(fd, I2C_RDWR, &xfer) < 0 ? -errno : 0;
}
