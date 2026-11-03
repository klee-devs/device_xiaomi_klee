# Shared MediaTek policy paths used by Klee.
# libperfmgr policy is maintained locally under sepolicy/vendor.
MTK_SEPOLICY_PATH := device/mediatek/sepolicy_vndr

BOARD_VENDOR_SEPOLICY_DIRS += $(MTK_SEPOLICY_PATH)/base/vendor
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(MTK_SEPOLICY_PATH)/base/private
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += $(MTK_SEPOLICY_PATH)/base/public
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/public

ifneq ($(TARGET_BUILD_VARIANT),user)
BOARD_VENDOR_SEPOLICY_DIRS += $(MTK_SEPOLICY_PATH)/debug/vendor
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(MTK_SEPOLICY_PATH)/debug/private
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += $(MTK_SEPOLICY_PATH)/debug/public
endif
