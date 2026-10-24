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

#include "ParameterParser.h"

#include <string>
#include <unistd.h>
#include <utility>

namespace vendor::audio::parserservice {

using ::aidl::android::hardware::audio::core::VendorParameter;
using ParameterScope = ::aidl::android::media::audio::IHalAdapterVendorExtension::ParameterScope;

::ndk::ScopedAStatus ParameterParser::parseVendorParameterIds(
        const ParameterScope& in_scope, const std::string& in_rawKeys,
        std::vector<std::string>* _aidl_return) {
    static_cast<void>(in_scope);

    // The stock MediaTek parser treats the entire legacy key list as one ID.
    _aidl_return->assign(1, in_rawKeys);
    return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus ParameterParser::parseVendorParameters(
        const ParameterScope& in_scope, const std::string& in_rawKeysAndValues,
        std::vector<VendorParameter>* out_syncParameters,
        std::vector<VendorParameter>* out_asyncParameters) {
    static_cast<void>(in_scope);

    out_syncParameters->clear();
    out_asyncParameters->clear();

    const auto appendSyncParameter = [out_syncParameters](std::string id) {
        VendorParameter parameter;
        parameter.id = std::move(id);
        // Generated VendorParameter initializes ext as an empty VINTF-stability
        // ParcelableHolder, matching the stock parser's AParcel(size=0).
        out_syncParameters->push_back(std::move(parameter));
    };

    // MTK expects the original key=value;key=value string in VendorParameter.id.
    appendSyncParameter(in_rawKeysAndValues);

    // Preserve the stock parser's BinderThread_VIP side channel. It is unrelated
    // to normal volume KVPs but is part of the vendor parser's behavior.
    if (in_rawKeysAndValues.find("BinderThread_VIP") != std::string::npos) {
        appendSyncParameter("BinderThread_VIP= " + std::to_string(getpid()));
    }

    return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus ParameterParser::parseBluetoothA2dpReconfigureOffload(
        const std::string& in_rawValue, std::vector<VendorParameter>* _aidl_return) {
    static_cast<void>(in_rawValue);
    _aidl_return->clear();
    return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus ParameterParser::parseBluetoothLeReconfigureOffload(
        const std::string& in_rawValue, std::vector<VendorParameter>* _aidl_return) {
    static_cast<void>(in_rawValue);
    _aidl_return->clear();
    return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus ParameterParser::processVendorParameters(
        const ParameterScope& in_scope, const std::vector<VendorParameter>& in_parameters,
        std::string* _aidl_return) {
    static_cast<void>(in_scope);

    // MTK's implementation emits the first returned ID unchanged.
    _aidl_return->clear();
    if (!in_parameters.empty()) {
        *_aidl_return = in_parameters.front().id;
    }
    return ::ndk::ScopedAStatus::ok();
}

}  // namespace vendor::audio::parserservice
