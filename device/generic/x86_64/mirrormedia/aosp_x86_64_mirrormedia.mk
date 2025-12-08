# 继承官方 aosp_x86_64 的一切配置
$(call inherit-product, build/make/target/product/aosp_x86_64.mk)

# 仅在这个自定义产品里放行你新增到 /system 的文件
PRODUCT_ARTIFACT_PATH_REQUIREMENT_WHITELIST += \
    system/bin/mirrormediad \
    system/etc/init/mirrormediad.rc

# 把守护进程打进镜像（你的 cc_binary 模块名）
PRODUCT_PACKAGES += mirrormediad

# 如果 cc_binary 没用 init_rc 属性安装 rc，就用 COPY 方式：
PRODUCT_COPY_FILES += \
    system/mirrormedia/daemon/mirrormediad.rc:system/etc/init/mirrormediad.rc

#（可选）把你针对 mirrormediad 的私有 sepolicy 放到这个产品里管理
PRODUCT_PRIVATE_SEPOLICY_DIRS += \
    device/generic/x86_64/mirrormedia/sepolicy/private
