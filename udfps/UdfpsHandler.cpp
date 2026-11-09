/*
 * Copyright (C) 2022 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "UdfpsHandler.klee"

#include <aidl/android/hardware/biometrics/fingerprint/BnFingerprint.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/unique_fd.h>

#include <atomic>
#include <bitset>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <fcntl.h>
#include <fstream>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/ioctl.h>
#include <thread>
#include <unistd.h>

#include "UdfpsHandler.h"
#include "mi_disp.h"

#define CMD_DATA_BUF_SIZE 256

#define COMMON_DATA_CMD 0
#define SELECT_TOUCH_ID 3
#define SET_CUR_VALUE 0

#define Touch_Fod_Enable 10

#define COMMAND_NIT 10
#define PARAM_NIT_FOD 1
#define PARAM_NIT_FOD_LOW_LIGHT 6
#define PARAM_NIT_NONE 0

#define COMMAND_FOD_PRESS_STATUS 1
#define PARAM_FOD_PRESSED 1
#define PARAM_FOD_RELEASED 0

#define FOD_STATUS_PATH "/sys/class/touch/touch_dev/fod_press_status"
#define FOD_STATUS_OFF 0
#define FOD_STATUS_ON 1

#define TOUCH_DEV_PATH "/dev/xiaomi-touch"
#define TOUCH_MAGIC 0x54

#define DISP_FEATURE_PATH "/dev/mi_display/disp_feature"


typedef struct {
    int8_t touch_id;
    uint8_t cmd;
    uint16_t mode;
    uint16_t data_len;
    int32_t data_buf[CMD_DATA_BUF_SIZE];
} touch_base;

#define TOUCH_IOC_SELECT_TOUCH_ID _IOW(TOUCH_MAGIC, SELECT_TOUCH_ID, int)
#define TOUCH_IOC_COMMON_DATA _IOW(TOUCH_MAGIC, COMMON_DATA_CMD, touch_base)

namespace {

constexpr char kLowLightFodProperty[] = "persist.sys.xiaomi.udfps_low_light";

constexpr auto kAuthenticationHbmOffDelay = std::chrono::milliseconds(300);
constexpr int kDisplayEventPollTimeoutMs = 250;

template <typename T>
static bool set(const std::string& path, const T& value) {
    std::ofstream file(path);
    if (!file) {
        LOG(ERROR) << "failed to open " << path;
        return false;
    }

    file << value;
    file.flush();
    if (!file) {
        LOG(ERROR) << "failed to write " << path;
        return false;
    }

    return true;
}

static disp_event_resp* parseDispEvent(int fd) {
    alignas(disp_event) static char event_data[1024] = {};
    ssize_t size;
    do {
        size = read(fd, event_data, sizeof(event_data));
    } while (size < 0 && errno == EINTR);

    if (size < 0) {
        PLOG(ERROR) << "read FOD event failed";
        return nullptr;
    }

    constexpr size_t kMinimumEventSize = sizeof(struct disp_event) + sizeof(uint8_t);
    if (static_cast<size_t>(size) < kMinimumEventSize) {
        LOG(ERROR) << "Invalid event size " << size << ", expect at least "
                   << kMinimumEventSize;
        return nullptr;
    }

    return reinterpret_cast<struct disp_event_resp*>(event_data);
}

}  // anonymous namespace

class XiaomiKleeUdfpsHandler : public UdfpsHandler {
  public:
    void init(fingerprint_device_t* device) override {
        if (device == nullptr) {
            LOG(ERROR) << "Cannot initialize UDFPS handler without a fingerprint device";
            return;
        }

        mDevice = device;
        disp_fd_ = android::base::unique_fd(open(DISP_FEATURE_PATH, O_RDWR));
        touch_fd_ = android::base::unique_fd(open(TOUCH_DEV_PATH, O_RDWR));

        if (disp_fd_.get() < 0) {
            PLOG(ERROR) << "Failed to open " << DISP_FEATURE_PATH;
        }
        if (touch_fd_.get() < 0) {
            PLOG(ERROR) << "Failed to open " << TOUCH_DEV_PATH;
        }

        mLowLightFodEnabled.store(
                android::base::GetBoolProperty(kLowLightFodProperty, false));
        setLowBrightnessFod(mLowLightFodEnabled.load());

        mDisplayEventThread = std::thread([this] { displayEventLoop(); });
        mAuthOffThread = std::thread([this] { authenticationOffLoop(); });
    }

    ~XiaomiKleeUdfpsHandler() override {
        mStopRequested.store(true);
        {
            std::lock_guard<std::mutex> lock(mAuthOffMutex);
            mAuthOffStop = true;
            mAuthOffPending = false;
        }
        mAuthOffCondition.notify_all();

        if (mDisplayEventThread.joinable()) {
            mDisplayEventThread.join();
        }
        if (mAuthOffThread.joinable()) {
            mAuthOffThread.join();
        }
    }

    void onFingerDown(uint32_t /*x*/, uint32_t /*y*/, float /*minor*/, float /*major*/) override {
        mAuthCompleted.store(false);
        mAuthGeneration.fetch_add(1);

        // Filter the FOD-area contact from pointer-down. Acquired messages can arrive
        // late and must not re-arm this state after pointer-up.
        setFodStatus(FOD_STATUS_ON);

        const bool lowLightFodEnabled =
                android::base::GetBoolProperty(kLowLightFodProperty, false);
        mLowLightFodEnabled.store(lowLightFodEnabled);
        setLowBrightnessFod(lowLightFodEnabled);
        setFingerDown(true);
    }

    void onFingerUp() override {
        mAuthGeneration.fetch_add(1);
        setFingerDown(false);
        setFodStatus(FOD_STATUS_OFF);
    }

    void cancel() override {
        LOG(DEBUG) << __func__ << " resetting UDFPS illumination";
        mAuthCompleted.store(false);
        mAuthGeneration.fetch_add(1);
        setFingerDown(false, LHBM_TARGET_BRIGHTNESS_OFF_AUTH_STOP);
        setFodStatus(FOD_STATUS_OFF);
    }

    void onAcquired(int32_t result, int32_t vendorCode) {
        LOG(DEBUG) << __func__ << " result: " << result << " vendorCode: " << vendorCode;
        // Acquired messages can arrive asynchronously, including after pointer-up.
        // Pointer-down/up callbacks alone own the FOD touch-filter state.
    }

    void onAuthenticationSucceeded() override {
        mAuthCompleted.store(true);
        const uint32_t authGeneration = mAuthGeneration.fetch_add(1) + 1;

        // The panel can still be waking when authentication completes. Delay LHBM
        // off until the panel is ready, otherwise the driver may drop the command.
        scheduleAuthenticationHbmOff(authGeneration);
    }

    void onAuthenticationFailed() override {
        mAuthCompleted.store(false);
        mAuthGeneration.fetch_add(1);
        // A failed sample can finish before the physical pointer-up. Stop the glow,
        // but keep FOD touch filtering until onFingerUp() or cancel().
        setLocalHbmBrightness(LHBM_TARGET_BRIGHTNESS_OFF_FINGER_UP);
    }

  private:
    fingerprint_device_t* mDevice = nullptr;
    android::base::unique_fd disp_fd_;
    android::base::unique_fd touch_fd_;
    std::atomic<bool> mStopRequested{false};
    std::atomic<bool> mAuthCompleted{false};
    std::atomic<bool> mLowLightFodEnabled{false};
    std::atomic<uint32_t> mAuthGeneration{0};
    std::thread mDisplayEventThread;
    std::thread mAuthOffThread;
    std::mutex mAuthOffMutex;
    std::condition_variable mAuthOffCondition;
    bool mAuthOffStop = false;
    bool mAuthOffPending = false;
    uint32_t mScheduledAuthGeneration = 0;
    std::chrono::steady_clock::time_point mAuthOffDeadline{};

    void scheduleAuthenticationHbmOff(uint32_t authGeneration) {
        {
            std::lock_guard<std::mutex> lock(mAuthOffMutex);
            if (mAuthOffStop) {
                return;
            }
            mScheduledAuthGeneration = authGeneration;
            mAuthOffDeadline = std::chrono::steady_clock::now() + kAuthenticationHbmOffDelay;
            mAuthOffPending = true;
        }
        mAuthOffCondition.notify_one();
    }

    void authenticationOffLoop() {
        std::unique_lock<std::mutex> lock(mAuthOffMutex);
        while (!mAuthOffStop) {
            if (!mAuthOffPending) {
                mAuthOffCondition.wait(lock, [this] {
                    return mAuthOffStop || mAuthOffPending;
                });
                continue;
            }

            const uint32_t authGeneration = mScheduledAuthGeneration;
            const auto deadline = mAuthOffDeadline;
            const bool rescheduled = mAuthOffCondition.wait_until(
                    lock, deadline, [this, authGeneration] {
                        return mAuthOffStop || !mAuthOffPending ||
                                mScheduledAuthGeneration != authGeneration;
                    });
            if (rescheduled) {
                continue;
            }

            mAuthOffPending = false;
            lock.unlock();
            if (mAuthGeneration.load() == authGeneration && mAuthCompleted.load()) {
                // Turn off illumination without telling the touch driver that the
                // still-held FOD pointer has been released.
                setLocalHbmBrightness(LHBM_TARGET_BRIGHTNESS_OFF_FINGER_UP);
            }
            lock.lock();
        }
    }

    void displayEventLoop() {
        android::base::unique_fd fd(open(DISP_FEATURE_PATH, O_RDWR));
        if (fd.get() < 0) {
            PLOG(ERROR) << "Failed to open " << DISP_FEATURE_PATH << " for FOD events";
            return;
        }

        disp_event_req req = {};
        req.base.disp_id = MI_DISP_PRIMARY;
        req.type = MI_DISP_EVENT_FOD;
        if (ioctl(fd.get(), MI_DISP_IOCTL_REGISTER_EVENT, &req) < 0) {
            PLOG(ERROR) << "Failed to register for FOD display events";
            return;
        }

        struct pollfd dispEventPoll = {
                .fd = fd.get(),
                .events = POLLIN,
                .revents = 0,
        };

        while (!mStopRequested.load()) {
            dispEventPoll.revents = 0;
            const int rc = poll(&dispEventPoll, 1, kDisplayEventPollTimeoutMs);
            if (rc < 0) {
                if (errno == EINTR) {
                    continue;
                }
                PLOG(ERROR) << "Failed to poll " << DISP_FEATURE_PATH;
                return;
            }
            if (rc == 0) {
                continue;
            }

            if (dispEventPoll.revents & POLLIN) {
                disp_event_resp* response = parseDispEvent(fd.get());
                if (response != nullptr) {
                    if (response->base.type != MI_DISP_EVENT_FOD) {
                        LOG(WARNING) << "Unexpected display event: " << response->base.type;
                    } else {
                        const int value = response->data[0];
                        LOG(DEBUG) << "received data: " << std::bitset<8>(value);

                        const bool localHbmUiReady = value & LOCAL_HBM_UI_READY;
                        const bool lowBrightnessCapture = mLowLightFodEnabled.load();

                        // Ignore late panel-ready notifications after authentication. A late
                        // NIT_FOD command can turn the FOD glow back on after it was cleared.
                        if (localHbmUiReady && mAuthCompleted.load()) {
                            LOG(DEBUG) << "LOCAL_HBM_UI_READY ignored - auth already completed";
                            mDevice->extCmd(mDevice, COMMAND_NIT, PARAM_NIT_NONE);
                        } else {
                            mDevice->extCmd(
                                    mDevice, COMMAND_NIT,
                                    localHbmUiReady
                                            ? (lowBrightnessCapture ? PARAM_NIT_FOD_LOW_LIGHT
                                                                    : PARAM_NIT_FOD)
                                            : PARAM_NIT_NONE);
                        }
                    }
                }
            }

            if (dispEventPoll.revents & (POLLERR | POLLHUP | POLLNVAL)) {
                LOG(ERROR) << "Display event poll failed, revents="
                           << dispEventPoll.revents;
                return;
            }
        }
    }

    void setLowBrightnessFod(bool enabled) {
        disp_feature_req req = {};
        req.base.flag = 0;
        req.base.disp_id = MI_DISP_PRIMARY;
        req.feature_id = DISP_FEATURE_LOW_BRIGHTNESS_FOD;
        req.feature_val = enabled ? FEATURE_ON : FEATURE_OFF;

        if (ioctl(disp_fd_.get(), MI_DISP_IOCTL_SET_FEATURE, &req) < 0) {
            PLOG(WARNING) << "Failed to " << (enabled ? "enable" : "disable")
                          << " low-brightness FOD";
        }
    }

    void setFodStatus(int value) {
        if (touch_fd_.get() < 0) {
            PLOG(ERROR) << "Cannot set FOD touch mode: " << TOUCH_DEV_PATH
                        << " is unavailable";
        } else if (ioctl(touch_fd_.get(), TOUCH_IOC_SELECT_TOUCH_ID, MI_DISP_PRIMARY) < 0) {
            PLOG(ERROR) << "Failed to select the primary touch controller for FOD";
        } else {
            touch_base data = {};
            data.touch_id = MI_DISP_PRIMARY;
            data.cmd = SET_CUR_VALUE;
            data.mode = Touch_Fod_Enable;
            data.data_len = 1;
            data.data_buf[0] = value;
            if (ioctl(touch_fd_.get(), TOUCH_IOC_COMMON_DATA, &data) < 0) {
                PLOG(ERROR) << "Failed to set FOD touch mode to " << value;
            }
        }

        if (!set(FOD_STATUS_PATH, value)) {
            LOG(ERROR) << "Failed to update FOD press status";
        }
    }
    void setLocalHbmBrightness(uint32_t value) {
        disp_local_hbm_req req = {};
        req.base.flag = 0;
        req.base.disp_id = MI_DISP_PRIMARY;
        req.local_hbm_value = value;
        if (ioctl(disp_fd_.get(), MI_DISP_IOCTL_SET_LOCAL_HBM, &req) < 0) {
            PLOG(WARNING) << "Failed to set local HBM brightness " << value;
        }
    }

    void setFingerDown(
            bool pressed, uint32_t offValue = LHBM_TARGET_BRIGHTNESS_OFF_FINGER_UP) {
        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_STATUS,
                        pressed ? PARAM_FOD_PRESSED : PARAM_FOD_RELEASED);
        const uint32_t brightness = pressed
                ? (mLowLightFodEnabled.load() ? LHBM_TARGET_BRIGHTNESS_WHITE_110NIT
                                              : LHBM_TARGET_BRIGHTNESS_WHITE_1000NIT)
                : offValue;
        setLocalHbmBrightness(brightness);
    }
};

static UdfpsHandler* create() {
    return new XiaomiKleeUdfpsHandler();
}

static void destroy(UdfpsHandler* handler) {
    delete handler;
}

extern "C" UdfpsHandlerFactory UDFPS_HANDLER_FACTORY = {
        .create = create,
        .destroy = destroy,
};
