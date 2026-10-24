/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <mediautils/Library.h>

#include <cstdlib>
#include <memory>
#include <string>

#include "ParameterParser.h"

using vendor::audio::parserservice::ParameterParser;

namespace {

binder_status_t tryRegisteringVendorImpl() {
    const std::string libName =
            ::android::base::GetProperty("ro.audio.ihaladaptervendorextension_libname", "");
    if (libName.empty()) {
        return STATUS_BAD_VALUE;
    }

    const std::shared_ptr<void> libHandle = ::android::mediautils::loadLibrary(libName.c_str());
    if (libHandle == nullptr) {
        LOG(ERROR) << "Failed to load audio parameter parser extension: " << libName;
        return STATUS_BAD_VALUE;
    }

    const std::shared_ptr<void> symbol = ::android::mediautils::getUntypedObjectFromLibrary(
            "createIHalAdapterVendorExtension", libHandle);
    const auto createExtension = reinterpret_cast<int (*)()>(symbol.get());
    if (createExtension == nullptr) {
        LOG(ERROR) << "Audio parameter parser extension has no create function: " << libName;
        return STATUS_BAD_VALUE;
    }

    return createExtension();
}

}  // namespace

int main() {
    if (tryRegisteringVendorImpl() != STATUS_OK) {
        const std::string serviceName =
                std::string(::aidl::android::media::audio::IHalAdapterVendorExtension::descriptor) +
                "/default";
        auto parser = ::ndk::SharedRefBase::make<ParameterParser>();
        const binder_status_t status =
                AServiceManager_addService(parser->asBinder().get(), serviceName.c_str());
        if (status != STATUS_OK) {
            LOG(ERROR) << "Failed to register audio parameter parser service " << serviceName
                       << " (status " << status << ")";
            return EXIT_FAILURE;
        }
        LOG(INFO) << "Registered A17 audio parameter parser service: " << serviceName;
    }

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
