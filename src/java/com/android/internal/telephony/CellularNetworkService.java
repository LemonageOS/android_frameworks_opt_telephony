/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.internal.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.radio.V1_0.RegState;
import android.hardware.radio.V1_4.DataRegStateResult.VopsInfo.hidl_discriminator;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telephony.AccessNetworkConstants;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.CellIdentity;
import android.telephony.CellIdentityCdma;
import android.telephony.LteVopsSupportInfo;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.NetworkService;
import android.telephony.NetworkServiceCallback;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of network services for Cellular. It's a service that handles network requests
 * for Cellular. It passes the requests to inner CellularNetworkServiceProvider which has a
 * handler thread for each slot.
 */
public class CellularNetworkService extends NetworkService {
    private static final boolean DBG = false;

    private static final String TAG = CellularNetworkService.class.getSimpleName();

    private static final int GET_CS_REGISTRATION_STATE_DONE = 1;
    private static final int GET_PS_REGISTRATION_STATE_DONE = 2;
    private static final int NETWORK_REGISTRATION_STATE_CHANGED = 3;

    // From 24.008 6.1.3.0 and 10.5.6.2 the maximum number of PDP Contexts is 16.
    private static final int MAX_DATA_CALLS = 16;

    private class CellularNetworkServiceProvider extends NetworkServiceProvider {

        private final Map<Message, NetworkServiceCallback> mCallbackMap = new HashMap<>();

        private final Handler mHandler;

        private final Phone mPhone;

        CellularNetworkServiceProvider(int slotId) {
            super(slotId);

            mPhone = PhoneFactory.getPhone(getSlotIndex());

            mHandler = new Handler(Looper.myLooper()) {
                @Override
                public void handleMessage(Message message) {
                    NetworkServiceCallback callback = mCallbackMap.remove(message);

                    AsyncResult ar;
                    switch (message.what) {
                        case GET_CS_REGISTRATION_STATE_DONE:
                        case GET_PS_REGISTRATION_STATE_DONE:
                            if (callback == null) return;
                            ar = (AsyncResult) message.obj;
                            int domain = (message.what == GET_CS_REGISTRATION_STATE_DONE)
                                    ? NetworkRegistrationInfo.DOMAIN_CS
                                    : NetworkRegistrationInfo.DOMAIN_PS;
                            NetworkRegistrationInfo netState =
                                    getRegistrationStateFromResult(ar.result, domain);

                            int resultCode;
                            if (ar.exception != null || netState == null) {
                                resultCode = NetworkServiceCallback.RESULT_ERROR_FAILED;
                            } else {
                                resultCode = NetworkServiceCallback.RESULT_SUCCESS;
                            }

                            try {
                                if (DBG) {
                                    log("Calling onRequestNetworkRegistrationInfoComplete."
                                            + "resultCode = " + resultCode
                                            + ", netState = " + netState);
                                }
                                callback.onRequestNetworkRegistrationInfoComplete(
                                         resultCode, netState);
                            } catch (Exception e) {
                                loge("Exception: " + e);
                            }
                            break;
                        case NETWORK_REGISTRATION_STATE_CHANGED:
                            notifyNetworkRegistrationInfoChanged();
                            break;
                        default:
                            return;
                    }
                }
            };

            mPhone.mCi.registerForNetworkStateChanged(
                    mHandler, NETWORK_REGISTRATION_STATE_CHANGED, null);
        }

        private int getRegStateFromHalRegState(int halRegState) {
            switch (halRegState) {
                case RegState.NOT_REG_MT_NOT_SEARCHING_OP:
                case RegState.NOT_REG_MT_NOT_SEARCHING_OP_EM:
                    return NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING;
                case RegState.REG_HOME:
                    return NetworkRegistrationInfo.REGISTRATION_STATE_HOME;
                case RegState.NOT_REG_MT_SEARCHING_OP:
                case RegState.NOT_REG_MT_SEARCHING_OP_EM:
                    return NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_SEARCHING;
                case RegState.REG_DENIED:
                case RegState.REG_DENIED_EM:
                    return NetworkRegistrationInfo.REGISTRATION_STATE_DENIED;
                case RegState.UNKNOWN:
                case RegState.UNKNOWN_EM:
                    return NetworkRegistrationInfo.REGISTRATION_STATE_UNKNOWN;
                case RegState.REG_ROAMING:
                    return NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING;
                default:
                    return NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING;
            }
        }

        private boolean isEmergencyOnly(int halRegState) {
            switch (halRegState) {
                case RegState.NOT_REG_MT_NOT_SEARCHING_OP_EM:
                case RegState.NOT_REG_MT_SEARCHING_OP_EM:
                case RegState.REG_DENIED_EM:
                case RegState.UNKNOWN_EM:
                    return true;
                case RegState.NOT_REG_MT_NOT_SEARCHING_OP:
                case RegState.REG_HOME:
                case RegState.NOT_REG_MT_SEARCHING_OP:
                case RegState.REG_DENIED:
                case RegState.UNKNOWN:
                case RegState.REG_ROAMING:
                default:
                    return false;
            }
        }

        private List<Integer> getAvailableServices(int regState, int domain,
                                                   boolean emergencyOnly) {
            List<Integer> availableServices = new ArrayList<>();

            // In emergency only states, only SERVICE_TYPE_EMERGENCY is available.
            // Otherwise, certain services are available only if it's registered on home or roaming
            // network.
            if (emergencyOnly) {
                availableServices.add(NetworkRegistrationInfo.SERVICE_TYPE_EMERGENCY);
            } else if (regState == NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING
                    || regState == NetworkRegistrationInfo.REGISTRATION_STATE_HOME) {
                if (domain == NetworkRegistrationInfo.DOMAIN_PS) {
                    availableServices.add(NetworkRegistrationInfo.SERVICE_TYPE_DATA);
                } else if (domain == NetworkRegistrationInfo.DOMAIN_CS) {
                    availableServices.add(NetworkRegistrationInfo.SERVICE_TYPE_VOICE);
                    availableServices.add(NetworkRegistrationInfo.SERVICE_TYPE_SMS);
                    availableServices.add(NetworkRegistrationInfo.SERVICE_TYPE_VIDEO);
                }
            }

            return availableServices;
        }

        private NetworkRegistrationInfo getRegistrationStateFromResult(Object result, int domain) {
            if (result == null) {
                return null;
            }

            // TODO: unify when voiceRegStateResult and DataRegStateResult are unified.
            if (domain == NetworkRegistrationInfo.DOMAIN_CS) {
                return createRegistrationStateFromVoiceRegState(result);
            } else if (domain == NetworkRegistrationInfo.DOMAIN_PS) {
                return createRegistrationStateFromDataRegState(result);
            } else {
                return null;
            }
        }

        private @NonNull String getPlmnFromCellIdentity(@Nullable final CellIdentity ci) {
            if (ci == null || ci instanceof CellIdentityCdma) return "";

            final String mcc = ci.getMccString();
            final String mnc = ci.getMncString();

            if (TextUtils.isEmpty(mcc) || TextUtils.isEmpty(mnc)) return "";

            return mcc + mnc;
        }

        private NetworkRegistrationInfo createRegistrationStateFromVoiceRegState(Object result) {
            final int transportType = AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
            final int domain = NetworkRegistrationInfo.DOMAIN_CS;

            // 1.5 at the top so that we can do an "early exit" from the method
            if (result instanceof android.hardware.radio.V1_5.RegStateResult) {
                return getNetworkRegistrationInfo(
                        domain,
                        transportType,
                        (android.hardware.radio.V1_5.RegStateResult) result);
            } else if (result instanceof android.hardware.radio.V1_0.VoiceRegStateResult) {
                android.hardware.radio.V1_0.VoiceRegStateResult voiceRegState =
                        (android.hardware.radio.V1_0.VoiceRegStateResult) result;
                int regState = getRegStateFromHalRegState(voiceRegState.regState);
                int networkType = ServiceState.rilRadioTechnologyToNetworkType(voiceRegState.rat);
                int reasonForDenial = voiceRegState.reasonForDenial;
                boolean emergencyOnly = isEmergencyOnly(voiceRegState.regState);
                boolean cssSupported = voiceRegState.cssSupported;
                int roamingIndicator = voiceRegState.roamingIndicator;
                int systemIsInPrl = voiceRegState.systemIsInPrl;
                int defaultRoamingIndicator = voiceRegState.defaultRoamingIndicator;
                List<Integer> availableServices = getAvailableServices(
                        regState, domain, emergencyOnly);
                CellIdentity cellIdentity = CellIdentity.create(voiceRegState.cellIdentity);
                final String rplmn = getPlmnFromCellIdentity(cellIdentity);

                return new NetworkRegistrationInfo(domain, transportType, regState,
                        networkType, reasonForDenial, emergencyOnly, availableServices,
                        cellIdentity, rplmn, cssSupported, roamingIndicator, systemIsInPrl,
                        defaultRoamingIndicator);
            } else if (result instanceof android.hardware.radio.V1_2.VoiceRegStateResult) {
                android.hardware.radio.V1_2.VoiceRegStateResult voiceRegState =
                        (android.hardware.radio.V1_2.VoiceRegStateResult) result;
                int regState = getRegStateFromHalRegState(voiceRegState.regState);
                int networkType = ServiceState.rilRadioTechnologyToNetworkType(voiceRegState.rat);
                int reasonForDenial = voiceRegState.reasonForDenial;
                boolean emergencyOnly = isEmergencyOnly(voiceRegState.regState);
                boolean cssSupported = voiceRegState.cssSupported;
                int roamingIndicator = voiceRegState.roamingIndicator;
                int systemIsInPrl = voiceRegState.systemIsInPrl;
                int defaultRoamingIndicator = voiceRegState.defaultRoamingIndicator;
                List<Integer> availableServices = getAvailableServices(
                        regState, domain, emergencyOnly);
                CellIdentity cellIdentity = CellIdentity.create(voiceRegState.cellIdentity);
                final String rplmn = getPlmnFromCellIdentity(cellIdentity);

                return new NetworkRegistrationInfo(domain, transportType, regState,
                        networkType, reasonForDenial, emergencyOnly, availableServices,
                        cellIdentity, rplmn, cssSupported, roamingIndicator, systemIsInPrl,
                        defaultRoamingIndicator);
            }

            return null;
        }

        private NetworkRegistrationInfo createRegistrationStateFromDataRegState(Object result) {
            final int domain = NetworkRegistrationInfo.DOMAIN_PS;
            final int transportType = AccessNetworkConstants.TRANSPORT_TYPE_WWAN;

            int regState = NetworkRegistrationInfo.REGISTRATION_STATE_UNKNOWN;
            int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
            int reasonForDenial = 0;
            boolean emergencyOnly = false;
            int maxDataCalls = 0;
            CellIdentity cellIdentity;
            boolean isEndcAvailable = false;
            boolean isNrAvailable = false;
            boolean isDcNrRestricted = false;

            LteVopsSupportInfo lteVopsSupportInfo =
                    new LteVopsSupportInfo(LteVopsSupportInfo.LTE_STATUS_NOT_AVAILABLE,
                            LteVopsSupportInfo.LTE_STATUS_NOT_AVAILABLE);

            // 1.5 at the top so that we can do an "early exit" from the method
            if (result instanceof android.hardware.radio.V1_5.RegStateResult) {
                return getNetworkRegistrationInfo(
                        domain,
                        transportType,
                        (android.hardware.radio.V1_5.RegStateResult) result);
            } else if (result instanceof android.hardware.radio.V1_0.DataRegStateResult) {
                android.hardware.radio.V1_0.DataRegStateResult dataRegState =
                        (android.hardware.radio.V1_0.DataRegStateResult) result;
                regState = getRegStateFromHalRegState(dataRegState.regState);
                networkType = ServiceState.rilRadioTechnologyToNetworkType(dataRegState.rat);
                reasonForDenial = dataRegState.reasonDataDenied;
                emergencyOnly = isEmergencyOnly(dataRegState.regState);
                maxDataCalls = dataRegState.maxDataCalls;
                cellIdentity = CellIdentity.create(dataRegState.cellIdentity);
            } else if (result instanceof android.hardware.radio.V1_2.DataRegStateResult) {
                android.hardware.radio.V1_2.DataRegStateResult dataRegState =
                        (android.hardware.radio.V1_2.DataRegStateResult) result;
                regState = getRegStateFromHalRegState(dataRegState.regState);
                networkType = ServiceState.rilRadioTechnologyToNetworkType(dataRegState.rat);
                reasonForDenial = dataRegState.reasonDataDenied;
                emergencyOnly = isEmergencyOnly(dataRegState.regState);
                maxDataCalls = dataRegState.maxDataCalls;
                cellIdentity = CellIdentity.create(dataRegState.cellIdentity);
            } else if (result instanceof android.hardware.radio.V1_4.DataRegStateResult) {
                android.hardware.radio.V1_4.DataRegStateResult dataRegState =
                        (android.hardware.radio.V1_4.DataRegStateResult) result;
                regState = getRegStateFromHalRegState(dataRegState.base.regState);
                networkType = ServiceState.rilRadioTechnologyToNetworkType(dataRegState.base.rat);

                reasonForDenial = dataRegState.base.reasonDataDenied;
                emergencyOnly = isEmergencyOnly(dataRegState.base.regState);
                maxDataCalls = dataRegState.base.maxDataCalls;
                cellIdentity = CellIdentity.create(dataRegState.base.cellIdentity);
                android.hardware.radio.V1_4.NrIndicators nrIndicators = dataRegState.nrIndicators;

                // Check for lteVopsInfo only if its initialized and RAT is EUTRAN
                if (dataRegState.vopsInfo.getDiscriminator() == hidl_discriminator.lteVopsInfo
                        && ServiceState.rilRadioTechnologyToAccessNetworkType(dataRegState.base.rat)
                            == AccessNetworkType.EUTRAN) {
                    android.hardware.radio.V1_4.LteVopsInfo vopsSupport =
                            dataRegState.vopsInfo.lteVopsInfo();
                    lteVopsSupportInfo = convertHalLteVopsSupportInfo(vopsSupport.isVopsSupported,
                        vopsSupport.isEmcBearerSupported);
                } else {
                    lteVopsSupportInfo =
                        new LteVopsSupportInfo(LteVopsSupportInfo.LTE_STATUS_NOT_AVAILABLE,
                        LteVopsSupportInfo.LTE_STATUS_NOT_AVAILABLE);
                }

                isEndcAvailable = nrIndicators.isEndcAvailable;
                isNrAvailable = nrIndicators.isNrAvailable;
                isDcNrRestricted = nrIndicators.isDcNrRestricted;
            } else {
                loge("Unknown type of DataRegStateResult " + result);
                return null;
            }

            String rplmn = getPlmnFromCellIdentity(cellIdentity);
            List<Integer> availableServices = getAvailableServices(
                    regState, domain, emergencyOnly);

            return new NetworkRegistrationInfo(domain, transportType, regState, networkType,
                    reasonForDenial, emergencyOnly, availableServices, cellIdentity, rplmn,
                    maxDataCalls, isDcNrRestricted, isNrAvailable, isEndcAvailable,
                    lteVopsSupportInfo);
        }

        private @NonNull NetworkRegistrationInfo getNetworkRegistrationInfo(
                int domain, int transportType,
                android.hardware.radio.V1_5.RegStateResult regResult) {

            // Perform common conversions that aren't domain specific
            final int regState = getRegStateFromHalRegState(regResult.regState);
            final boolean isEmergencyOnly = isEmergencyOnly(regResult.regState);
            final List<Integer> availableServices = getAvailableServices(
                    regState, domain, isEmergencyOnly);
            final int rejectCause = regResult.reasonForDenial;
            final CellIdentity cellIdentity = CellIdentity.create(regResult.cellIdentity);
            final String rplmn = regResult.registeredPlmn;
            final int reasonForDenial = regResult.reasonForDenial;

            int networkType = ServiceState.rilRadioTechnologyToNetworkType(regResult.rat);
            if (networkType == TelephonyManager.NETWORK_TYPE_LTE_CA) {
                // In Radio HAL v1.5, NETWORK_TYPE_LTE_CA is ignored. Callers should use
                // PhysicalChannelConfig.
                networkType = TelephonyManager.NETWORK_TYPE_LTE;
            }

            // Conditional parameters for specific RANs
            boolean cssSupported = false;
            int roamingIndicator = 0;
            int systemIsInPrl = 0;
            int defaultRoamingIndicator = 0;
            boolean isEndcAvailable = false;
            boolean isNrAvailable = false;
            boolean isDcNrRestricted = false;
            LteVopsSupportInfo vopsInfo = new LteVopsSupportInfo(
                    LteVopsSupportInfo.LTE_STATUS_NOT_AVAILABLE,
                    LteVopsSupportInfo.LTE_STATUS_NOT_AVAILABLE);

            switch (regResult.accessTechnologySpecificInfo.getDiscriminator()) {
                case android.hardware.radio.V1_5.RegStateResult
                        .AccessTechnologySpecificInfo.hidl_discriminator.cdmaInfo:
                    android.hardware.radio.V1_5.RegStateResult
                            .AccessTechnologySpecificInfo.Cdma2000RegistrationInfo cdmaInfo =
                                    regResult.accessTechnologySpecificInfo.cdmaInfo();
                    cssSupported = cdmaInfo.cssSupported;
                    roamingIndicator = cdmaInfo.roamingIndicator;
                    systemIsInPrl = cdmaInfo.systemIsInPrl;
                    defaultRoamingIndicator = cdmaInfo.defaultRoamingIndicator;
                    break;
                case android.hardware.radio.V1_5.RegStateResult
                        .AccessTechnologySpecificInfo.hidl_discriminator.eutranInfo:
                    android.hardware.radio.V1_5.RegStateResult
                            .AccessTechnologySpecificInfo.EutranRegistrationInfo eutranInfo =
                                    regResult.accessTechnologySpecificInfo.eutranInfo();

                    isDcNrRestricted = eutranInfo.nrIndicators.isDcNrRestricted;
                    isNrAvailable = eutranInfo.nrIndicators.isNrAvailable;
                    isEndcAvailable = eutranInfo.nrIndicators.isEndcAvailable;
                    vopsInfo = convertHalLteVopsSupportInfo(
                            eutranInfo.lteVopsInfo.isVopsSupported,
                            eutranInfo.lteVopsInfo.isEmcBearerSupported);
                    break;
                default:
                    log("No access tech specific info passes for RegStateResult");
                    break;
            }

            // build the result based on the domain for the request
            switch(domain) {
                case NetworkRegistrationInfo.DOMAIN_CS:
                    return new NetworkRegistrationInfo(domain, transportType, regState,
                            networkType, reasonForDenial, isEmergencyOnly, availableServices,
                            cellIdentity, rplmn, cssSupported, roamingIndicator, systemIsInPrl,
                            defaultRoamingIndicator);
                default:
                    loge("Unknown domain passed to CellularNetworkService= " + domain);
                    // fall through
                case NetworkRegistrationInfo.DOMAIN_PS:
                    return new NetworkRegistrationInfo(domain, transportType, regState, networkType,
                            reasonForDenial, isEmergencyOnly, availableServices, cellIdentity,
                            rplmn, MAX_DATA_CALLS, isDcNrRestricted, isNrAvailable, isEndcAvailable,
                            vopsInfo);
            }
        }

        private LteVopsSupportInfo convertHalLteVopsSupportInfo(
                boolean vopsSupport, boolean emcBearerSupport) {
            int vops = LteVopsSupportInfo.LTE_STATUS_NOT_SUPPORTED;
            int emergency = LteVopsSupportInfo.LTE_STATUS_NOT_SUPPORTED;

            if (vopsSupport) {
                vops = LteVopsSupportInfo.LTE_STATUS_SUPPORTED;
            }
            if (emcBearerSupport) {
                emergency = LteVopsSupportInfo.LTE_STATUS_SUPPORTED;
            }
            return new LteVopsSupportInfo(vops, emergency);
        }

        @Override
        public void requestNetworkRegistrationInfo(int domain, NetworkServiceCallback callback) {
            if (DBG) log("requestNetworkRegistrationInfo for domain " + domain);
            Message message = null;

            if (domain == NetworkRegistrationInfo.DOMAIN_CS) {
                message = Message.obtain(mHandler, GET_CS_REGISTRATION_STATE_DONE);
                mCallbackMap.put(message, callback);
                mPhone.mCi.getVoiceRegistrationState(message);
            } else if (domain == NetworkRegistrationInfo.DOMAIN_PS) {
                message = Message.obtain(mHandler, GET_PS_REGISTRATION_STATE_DONE);
                mCallbackMap.put(message, callback);
                mPhone.mCi.getDataRegistrationState(message);
            } else {
                loge("requestNetworkRegistrationInfo invalid domain " + domain);
                callback.onRequestNetworkRegistrationInfoComplete(
                        NetworkServiceCallback.RESULT_ERROR_INVALID_ARG, null);
            }
        }

        @Override
        public void close() {
            mCallbackMap.clear();
            mPhone.mCi.unregisterForNetworkStateChanged(mHandler);
        }
    }

    @Override
    public NetworkServiceProvider onCreateNetworkServiceProvider(int slotIndex) {
        if (DBG) log("Cellular network service created for slot " + slotIndex);
        if (!SubscriptionManager.isValidSlotIndex(slotIndex)) {
            loge("Tried to Cellular network service with invalid slotId " + slotIndex);
            return null;
        }
        return new CellularNetworkServiceProvider(slotIndex);
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}
