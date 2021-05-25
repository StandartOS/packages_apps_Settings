/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.settings.network.telephony;

import static com.android.settings.network.InternetUpdater.INTERNET_ETHERNET;
import static com.android.settingslib.mobile.MobileMappings.getIconKey;
import static com.android.settingslib.mobile.MobileMappings.mapIconSets;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.settings.network.InternetUpdater;
import com.android.settings.network.MobileDataContentObserver;
import com.android.settings.network.MobileDataEnabledListener;
import com.android.settings.network.SubscriptionsChangeListener;
import com.android.settings.wifi.slice.WifiScanWorker;
import com.android.settingslib.SignalIcon.MobileIconGroup;
import com.android.settingslib.mobile.MobileMappings;
import com.android.settingslib.mobile.MobileMappings.Config;
import com.android.settingslib.mobile.TelephonyIcons;

import java.util.Collections;

/**
 * BackgroundWorker for Provider Model slice.
 */
public class NetworkProviderWorker extends WifiScanWorker implements
        SignalStrengthListener.Callback, MobileDataEnabledListener.Client,
        DataConnectivityListener.Client, InternetUpdater.InternetChangeListener,
        SubscriptionsChangeListener.SubscriptionsChangeListenerClient {
    private static final String TAG = "NetworkProviderWorker";
    private static final int PROVIDER_MODEL_DEFAULT_EXPANDED_ROW_COUNT = 5;
    private DataContentObserver mMobileDataObserver;
    private SignalStrengthListener mSignalStrengthListener;
    private SubscriptionsChangeListener mSubscriptionsListener;
    private MobileDataEnabledListener mDataEnabledListener;
    private DataConnectivityListener mConnectivityListener;
    private int mDefaultDataSubid = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private final Context mContext;
    final Handler mHandler;
    @VisibleForTesting
    final NetworkProviderTelephonyCallback mTelephonyCallback;
    private TelephonyManager mTelephonyManager;
    private Config mConfig = null;
    private TelephonyDisplayInfo mTelephonyDisplayInfo =
            new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE);
    private InternetUpdater mInternetUpdater;
    private @InternetUpdater.InternetType int mInternetType;

    public NetworkProviderWorker(Context context, Uri uri) {
        super(context, uri);
        // Mobile data worker
        mHandler = new Handler(Looper.getMainLooper());
        mMobileDataObserver = new DataContentObserver(mHandler, this);

        mContext = context;
        mDefaultDataSubid = getDefaultDataSubscriptionId();

        mTelephonyManager = mContext.getSystemService(
                TelephonyManager.class).createForSubscriptionId(mDefaultDataSubid);
        mTelephonyCallback = new NetworkProviderTelephonyCallback();
        mSubscriptionsListener = new SubscriptionsChangeListener(context, this);
        mDataEnabledListener = new MobileDataEnabledListener(context, this);
        mConnectivityListener = new DataConnectivityListener(context, this);
        mSignalStrengthListener = new SignalStrengthListener(context, this);
        mConfig = getConfig(mContext);

        mInternetUpdater = new InternetUpdater(mContext, getLifecycle(), this);
        mInternetType = mInternetUpdater.getInternetType();
    }

    @Override
    protected void onSlicePinned() {
        mMobileDataObserver.register(mContext, mDefaultDataSubid);
        mSubscriptionsListener.start();
        mDataEnabledListener.start(mDefaultDataSubid);
        mConnectivityListener.start();
        mSignalStrengthListener.resume();
        mTelephonyManager.registerTelephonyCallback(mHandler::post, mTelephonyCallback);
        super.onSlicePinned();
    }

    @Override
    protected void onSliceUnpinned() {
        mMobileDataObserver.unregister(mContext);
        mSubscriptionsListener.stop();
        mDataEnabledListener.stop();
        mConnectivityListener.stop();
        mSignalStrengthListener.pause();
        mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback);
        super.onSliceUnpinned();
    }

    @Override
    public void close() {
        mMobileDataObserver = null;
        super.close();
    }

    @Override
    public int getApRowCount() {
        return PROVIDER_MODEL_DEFAULT_EXPANDED_ROW_COUNT;
    }

    /**
     * To update the Slice.
     */
    public void updateSlice() {
        notifySliceChange();
    }

    @Override
    public void onSubscriptionsChanged() {
        int defaultDataSubId = getDefaultDataSubscriptionId();
        Log.d(TAG, "onSubscriptionsChanged: defaultDataSubId:" + defaultDataSubId);
        if (mDefaultDataSubid == defaultDataSubId) {
            return;
        }
        if (SubscriptionManager.isUsableSubscriptionId(defaultDataSubId)) {
            mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback);
            mMobileDataObserver.unregister(mContext);

            mSignalStrengthListener.updateSubscriptionIds(Collections.singleton(defaultDataSubId));
            mTelephonyManager = mTelephonyManager.createForSubscriptionId(defaultDataSubId);
            mTelephonyManager.registerTelephonyCallback(mHandler::post, mTelephonyCallback);
            mMobileDataObserver.register(mContext, mDefaultDataSubid);
            mConfig = getConfig(mContext);
        } else {
            mSignalStrengthListener.updateSubscriptionIds(Collections.emptySet());
        }
        updateSlice();
    }

    @Override
    public void onSignalStrengthChanged() {
        Log.d(TAG, "onSignalStrengthChanged");
        updateSlice();
    }

    @Override
    public void onAirplaneModeChanged(boolean airplaneModeEnabled) {
        Log.d(TAG, "onAirplaneModeChanged");
        updateSlice();
    }

    @Override
    public void onMobileDataEnabledChange() {
        Log.d(TAG, "onMobileDataEnabledChange");
        updateSlice();
    }

    @Override
    public void onDataConnectivityChange() {
        Log.d(TAG, "onDataConnectivityChange");
        updateSlice();
    }

    /**
     * Listen to update of mobile data change.
     */
    public class DataContentObserver extends ContentObserver {
        private final NetworkProviderWorker mNetworkProviderWorker;

        public DataContentObserver(Handler handler, NetworkProviderWorker backgroundWorker) {
            super(handler);
            mNetworkProviderWorker = backgroundWorker;
        }

        @Override
        public void onChange(boolean selfChange) {
            mNetworkProviderWorker.updateSlice();
        }

        /**
         * To register the observer for mobile data changed.
         *
         * @param context the Context object.
         * @param subId the default data subscription id.
         */
        public void register(Context context, int subId) {
            final Uri uri = MobileDataContentObserver.getObservableUri(context, subId);
            context.getContentResolver().registerContentObserver(uri, false, this);
        }

        /**
         * To unregister the observer for mobile data changed.
         *
         * @param context the Context object.
         */
        public void unregister(Context context) {
            context.getContentResolver().unregisterContentObserver(this);
        }
    }

    class NetworkProviderTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.DataConnectionStateListener,
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.ServiceStateListener {
        @Override
        public void onServiceStateChanged(ServiceState state) {
            Log.d(TAG, "onServiceStateChanged voiceState=" + state.getState()
                    + " dataState=" + state.getDataRegistrationState());
            updateSlice();
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo) {
            Log.d(TAG, "onDisplayInfoChanged: telephonyDisplayInfo=" + telephonyDisplayInfo);
            mTelephonyDisplayInfo = telephonyDisplayInfo;
            updateSlice();
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            Log.d(TAG,
                    "onDataConnectionStateChanged: networkType=" + networkType + " state=" + state);
            updateSlice();
        }
    }

    @VisibleForTesting
    int getDefaultDataSubscriptionId() {
        return SubscriptionManager.getDefaultDataSubscriptionId();
    }

    private String updateNetworkTypeName(Context context, Config config,
            TelephonyDisplayInfo telephonyDisplayInfo, int subId) {
        String iconKey = getIconKey(telephonyDisplayInfo);
        int resId = mapIconSets(config).get(iconKey).dataContentDescription;
        if (mWifiPickerTrackerHelper != null
                && mWifiPickerTrackerHelper.isActiveCarrierNetwork()) {
            MobileIconGroup carrierMergedWifiIconGroup = TelephonyIcons.CARRIER_MERGED_WIFI;
            resId = carrierMergedWifiIconGroup.dataContentDescription;
            return resId != 0
                    ? SubscriptionManager.getResourcesForSubId(context, subId)
                    .getString(resId) : "";
        }

        return resId != 0
                ? SubscriptionManager.getResourcesForSubId(context, subId).getString(resId) : "";
    }

    @VisibleForTesting
    Config getConfig(Context context) {
        return MobileMappings.Config.readConfig(context);
    }

    /**
     * Get currently description of mobile network type.
     */
    public String getNetworkTypeDescription() {
        return updateNetworkTypeName(mContext, mConfig, mTelephonyDisplayInfo,
                mDefaultDataSubid);
    }

    /**
     * Called when internet type is changed.
     *
     * @param internetType the internet type
     */
    public void onInternetTypeChanged(@InternetUpdater.InternetType int internetType) {
        if (mInternetType == internetType) {
            return;
        }
        boolean changeWithEthernet =
                mInternetType == INTERNET_ETHERNET || internetType == INTERNET_ETHERNET;
        mInternetType = internetType;
        if (changeWithEthernet) {
            updateSlice();
        }
    }

    /**
     * Returns the internet type.
     */
    public @InternetUpdater.InternetType int getInternetType() {
        return mInternetType;
    }
}
