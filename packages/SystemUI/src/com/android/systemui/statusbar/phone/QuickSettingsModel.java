/*
 * Copyright (C) 2012 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2014 ParanoidAndroid Project.
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

package com.android.systemui.statusbar.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.BluetoothStateChangeCallback;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.SyncStatusObserver;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.net.NetworkUtils;
import android.nfc.NfcAdapter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.ImageView;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.paranoid.DeviceUtils;
import com.android.systemui.R;
import com.android.systemui.settings.BrightnessController.BrightnessStateChangeCallback;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationController.LocationSettingsChangeCallback;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;
import com.android.systemui.nameless.onthego.OnTheGoReceiver;
import com.android.internal.util.slim.QuietHoursHelper;

import com.android.internal.util.omni.OmniTorchConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.UnsupportedOperationException;
import java.util.ArrayList;
import java.util.List;

public class QuickSettingsModel implements BluetoothStateChangeCallback,
        NetworkSignalChangedCallback,
        BatteryStateChangeCallback,
        BrightnessStateChangeCallback,
        RotationLockControllerCallback,
        LocationSettingsChangeCallback {
    // Set InputMethodManagerService
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";

    /** Represents the state of a given attribute. */
    static class State {
        int iconId;
        String label;
        boolean enabled = false;
    }
    static class BatteryState extends State {
        int batteryLevel;
        boolean pluggedIn;
    }
    static class ActivityState extends State {
        boolean activityIn;
        boolean activityOut;
    }
    static class RSSIState extends ActivityState {
        int signalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        String dataContentDescription;
    }
    static class WifiState extends ActivityState {
        String signalContentDescription;
        boolean connected;
    }
    static class UserState extends State {
        Drawable avatar;
    }
    static class BrightnessState extends State {
        boolean autoBrightness;
    }
    static class QuietHourState extends State {
        boolean isEnabled;
        boolean isPaused;
        boolean isForced;
        boolean isActive;
    }
    public static class BluetoothState extends State {
        boolean connected = false;
        String stateContentDescription;
    }
    static class NfcState extends State {
        boolean isEnabled;
    }
    static class MusicState extends State {
        String trackTitle; 
        Bitmap mCurrentBitmap;
    }

    static class RecordingState extends State {
        int recording;
    }

    public static class RotationLockState extends State {
        boolean visible = false;
    }

    /** The callback to update a given tile. */
    interface RefreshCallback {
        public void refreshView(QuickSettingsTileView view, State state);
    }

    // General basic tiles
    public static class BasicRefreshCallback implements RefreshCallback {
        private final QuickSettingsBasicTile mView;
        private boolean mShowWhenEnabled;

        public BasicRefreshCallback(QuickSettingsBasicTile v) {
            mView = v;
        }
        public void refreshView(QuickSettingsTileView ignored, State state) {
            if (mShowWhenEnabled) {
                mView.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
            if (state.iconId != 0) {
                mView.setImageDrawable(null); // needed to flush any cached IDs
                mView.setImageResource(state.iconId);
            }
            if (state.label != null) {
                mView.setText(state.label);
            }
        }
        public BasicRefreshCallback setShowWhenEnabled(boolean swe) {
            mShowWhenEnabled = swe;
            return this;
        }
    }

    /** Broadcast receive to determine torch. */
    private BroadcastReceiver mTorchIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mTorchActive = intent.getIntExtra(OmniTorchConstants.EXTRA_CURRENT_STATE, 0) != 0;
            onTorchChanged();
        }
    };

    /** Broadcast receive to determine ringer. */
    private BroadcastReceiver mRingerIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                updateRingerState();
            }
        }
    };

    /** Broadcast receive to determine if there is an alarm set. */
    private BroadcastReceiver mAlarmIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_ALARM_CHANGED)) {
                onAlarmChanged(intent);
                onNextAlarmChanged();
            }
        }
    };

    /** Broadcast receive to determine if device boot is complete*/
    private BroadcastReceiver mBootReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final ContentResolver cr = mContext.getContentResolver();
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                mHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        mUsesAospDialer = Settings.System
                                                .getInt(cr, Settings.System.AOSP_DIALER, 0) == 1;
                        if (deviceHasMobileData()) {
                            if (mUsesAospDialer) {
                                refreshMobileNetworkTile();
                            } else {
                                mMobileNetworkState.label =
                                    mContext.getResources()
                                            .getString(R.string.quick_settings_network_disabled);
                                mMobileNetworkState.iconId =
                                    R.drawable.ic_qs_unexpected_network;
                                mMobileNetworkCallback.refreshView(mMobileNetworkTile,
                                                                    mMobileNetworkState);
                            }
                        }
                    }
                }, 200);
            }
            context.unregisterReceiver(mBootReceiver);
        }
    };

    /** Broadcast receive to catch device shutdown */
    private BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final ContentResolver cr = mContext.getContentResolver();
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SHUTDOWN)) {
                Settings.System.putInt(cr, Settings.System.AOSP_DIALER, 0);
            }
        }
    };

    /** Broadcast receive to determine usb tether. */
    private BroadcastReceiver mUsbIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(UsbManager.ACTION_USB_STATE)) {
                mUsbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
            } else if (action.equals(Intent.ACTION_MEDIA_SHARED)) {
                mMassStorageActive = true;
            } else if (action.equals(Intent.ACTION_MEDIA_UNSHARED)) {
                mMassStorageActive = false;
            }
            onUsbChanged();
        }
    };

    /** Broadcast receive to determine on-the-go. */
    private BroadcastReceiver mOnTheGoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(OnTheGoReceiver.ACTION_START)) {
                mOnTheGoRunning = true;
            } else if (action.equals(OnTheGoReceiver.ACTION_ALREADY_STOP)) {
                mOnTheGoRunning = false;
            }
            onOnTheGoChanged();
        }
    };

    /** Broadcast receive to determine NFC. */
    private BroadcastReceiver mNfcIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(intent.getAction().equals(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)) {
                refreshNfcTile();
            }
        }
    };

    /** ContentObserver to determine the on-the-go tile */
    private class OnTheGoObserver extends ContentObserver {
        public OnTheGoObserver(Handler handler) {
            super(handler);
        }

        @Override 
        public void onChange(boolean selfChange) {
            onOnTheGoChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ONTHEGO_IN_POWER_MENU), false, this,
                    UserHandle.USER_ALL);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ON_THE_GO_CAMERA), false, this,
                    UserHandle.USER_ALL);
        }
    }

    /** ContentObserver to determine the next alarm */
    private class NextAlarmObserver extends ContentObserver {
        public NextAlarmObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onNextAlarmChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NEXT_ALARM_FORMATTED), false, this,
                    UserHandle.USER_ALL);
        }
    }

   /** ContentObserver to determine the ringer */
    private class RingerObserver extends ContentObserver {
        public RingerObserver(Handler handler) {
            super(handler);
        }

        @Override 
        public void onChange(boolean selfChange) {
            updateRingerState();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.VIBRATE_WHEN_RINGING), false, this,
                    UserHandle.USER_ALL);
            cr.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.IMMERSIVE_DEFAULT_APP_MODE), false, this);
        }
    }

    /** ContentObserver to watch adb */
    private class BugreportObserver extends ContentObserver {
        public BugreportObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onBugreportChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BUGREPORT_IN_POWER_MENU), false, this);
        }
    }

    /** ContentObserver to watch brightness **/
    private class BrightnessObserver extends ContentObserver {
        public BrightnessObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onBrightnessLevelChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, this, mUserTracker.getCurrentUserId());
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** ContentObserver to watch Network State */
    private class NetworkObserver extends ContentObserver {
        public NetworkObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onMobileNetworkChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.PREFERRED_NETWORK_MODE), false, this);
        }
    }

    /** ContentObserver to determine the Sleep Time */
    private class SleepTimeObserver extends ContentObserver {
        public SleepTimeObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshSleepTimeTile();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_OFF_TIMEOUT), false, this);
        }
    }

    /** ContentObserver to watch immersive **/
    private class ImmersiveObserver extends ContentObserver {
        public ImmersiveObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(Settings.System.PIE_STATE))) {
                boolean enablePie = Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.PIE_STATE, 0) != 0;
                if (enablePie) switchImmersiveGlobal();
            }
            onImmersiveGlobalChanged();
            onImmersiveModeChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.IMMERSIVE_MODE), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_STATE), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.IMMERSIVE_DEFAULT_APP_MODE), false, this);
        }
    }

    /** ContentObserver to watch netAdb **/
    private class NetAdbObserver extends ContentObserver {
        public NetAdbObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onNetAdbChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ADB_PORT),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** Broadcast receive to watch quitehour. */
    private BroadcastReceiver mQuietHoursIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onQuietHourChanged();
        }
    };

    /** ContentObserver to watch quitehour **/
    private class QuietHourObserver extends ContentObserver {
        public QuietHourObserver(Handler handler) {
            super(handler);
        }
   
        @Override
        public void onChange(boolean selfChange) {
            onQuietHourChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUIET_HOURS_FORCED),
                    false, this, mUserTracker.getCurrentUserId());
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUIET_HOURS_PAUSED),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** Callback for changes to remote display routes. */
    private class RemoteDisplayRouteCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
    }

    /** Broadcast receive to determine wifi ap state. */
    private BroadcastReceiver mWifiApStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.WIFI_AP_STATE_CHANGED_ACTION.equals(action)) {
                onWifiApChanged();
            }
        }
    };

    private final Context mContext;
    private final Handler mHandler;
    private final CurrentUserTracker mUserTracker;
    private final NextAlarmObserver mNextAlarmObserver;
    private final BugreportObserver mBugreportObserver;
    private final BrightnessObserver mBrightnessObserver;
    private final NetAdbObserver mNetAdbObserver;
    private final OnTheGoObserver mOnTheGoObserver;
    private final QuietHourObserver mQuietHourObserver;
    private final NetworkObserver mMobileNetworkObserver;
    private final RingerObserver mRingerObserver;
    private final SleepTimeObserver mSleepTimeObserver;
    private final ImmersiveObserver mImmersiveObserver;
    private boolean mUsbTethered = false;
    private boolean mUsbConnected = false;
    private boolean mMassStorageActive = false;
    protected boolean mUsesAospDialer = false;
    private String[] mUsbRegexs;
    private ConnectivityManager mCM;

    private boolean mTorchActive = false;

    private final MediaRouter mMediaRouter;
    private final RemoteDisplayRouteCallback mRemoteDisplayRouteCallback;

    private final boolean mHasMobileData;

    private boolean mOnTheGoRunning = false;
    private static final Ringer[] RINGERS = new Ringer[] {
        new Ringer(AudioManager.RINGER_MODE_SILENT, false, R.drawable.ic_qs_ring_off, R.string.quick_settings_ringer_off),
        new Ringer(AudioManager.RINGER_MODE_VIBRATE, true, R.drawable.ic_qs_vibrate_on, R.string.quick_settings_vibration_on),
        new Ringer(AudioManager.RINGER_MODE_NORMAL, false, R.drawable.ic_qs_ring_on, R.string.quick_settings_ringer_on),
        new Ringer(AudioManager.RINGER_MODE_NORMAL, true, R.drawable.ic_qs_ring_vibrate_on, R.string.quick_settings_ringer_normal)
    };

    private ArrayList<Ringer> mRingers;
    private int mRingerIndex;

    private AudioManager mAudioManager;
    private Vibrator mVibrator;

    private QuickSettingsTileView mUserTile;
    private RefreshCallback mUserCallback;
    private UserState mUserState = new UserState();

    private QuickSettingsTileView mTimeTile;
    private RefreshCallback mTimeCallback;
    private State mTimeState = new State();

    private QuickSettingsTileView mAlarmTile;
    private RefreshCallback mAlarmCallback;
    private State mAlarmState = new State();

    private QuickSettingsTileView mOnTheGoTile;
    private RefreshCallback mOnTheGoCallback;
    private State mOnTheGoState = new State();

    private QuickSettingsTileView mAirplaneModeTile;
    private RefreshCallback mAirplaneModeCallback;
    private State mAirplaneModeState = new State();

    private QuickSettingsTileView mUsbModeTile;
    private RefreshCallback mUsbModeCallback;
    private State mUsbModeState = new State();

    private QuickSettingsTileView mWifiTile;
    private RefreshCallback mWifiCallback;
    private WifiState mWifiState = new WifiState();

    private QuickSettingsTileView mWifiApTile;
    private RefreshCallback mWifiApCallback;
    private State mWifiApState = new State();

    private QuickSettingsTileView mRemoteDisplayTile;
    private RefreshCallback mRemoteDisplayCallback;
    private State mRemoteDisplayState = new State();

    private QuickSettingsTileView mThemeTile;
    private RefreshCallback mThemeCallback;
    private State mThemeState = new State();

    private QuickSettingsTileView mRSSITile;
    private RefreshCallback mRSSICallback;
    private RSSIState mRSSIState = new RSSIState();

    private QuickSettingsTileView mBluetoothTile;
    private RefreshCallback mBluetoothCallback;
    private BluetoothState mBluetoothState = new BluetoothState();

    private QuickSettingsTileView mBluetoothExtraTile;
    private RefreshCallback mBluetoothExtraCallback;
    private BluetoothState mBluetoothExtraState = new BluetoothState();

    private QuickSettingsTileView mRecordingTile;
    private RefreshCallback mRecordingCallback;
    public static RecordingState mRecordingState = new RecordingState();
 
    private QuickSettingsTileView mCameraTile;
    private RefreshCallback mCameraCallback;
    private State mCameraState = new State();
 
    protected QuickSettingsTileView mMusicTile;
    private RefreshCallback mMusicCallback;
    private State mMusicState = new State();
    protected ImageView background;

    private QuickSettingsTileView mBatteryTile;
    private RefreshCallback mBatteryCallback;
    private BatteryState mBatteryState = new BatteryState();

    private QuickSettingsTileView mLocationTile;
    private RefreshCallback mLocationCallback;
    private State mLocationState = new State();

    private QuickSettingsTileView mLocationExtraTile;
    private RefreshCallback mLocationExtraCallback;
    private State mLocationExtraState = new State();

    private QuickSettingsTileView mImeTile;
    private RefreshCallback mImeCallback = null;
    private State mImeState = new State();

    private QuickSettingsTileView mRotationLockTile;
    private RefreshCallback mRotationLockCallback;
    private RotationLockState mRotationLockState = new RotationLockState();

    private QuickSettingsTileView mBrightnessTile;
    private RefreshCallback mBrightnessCallback;
    private BrightnessState mBrightnessState = new BrightnessState();

    private QuickSettingsTileView mFastChargeTile;
    private RefreshCallback mFastChargeCallback;
    private State mFastChargeState = new State();

    private QuickSettingsTileView mNetAdbTile;
    private RefreshCallback mNetAdbCallback;
    private State mNetAdbState = new State();

    private QuickSettingsTileView mQuietHourTile;
    private RefreshCallback mQuietHourCallback;
    private QuietHourState mQuietHourState = new QuietHourState();

    private QuickSettingsTileView mBugreportTile;
    private RefreshCallback mBugreportCallback;
    private State mBugreportState = new State();

    private QuickSettingsTileView mSettingsTile;
    private RefreshCallback mSettingsCallback;
    private State mSettingsState = new State();

    private QuickSettingsTileView mNfcTile;
    private RefreshCallback mNfcCallback;
    private State mNfcState = new NfcState();

    private QuickSettingsTileView mSslCaCertWarningTile;
    private RefreshCallback mSslCaCertWarningCallback;
    private State mSslCaCertWarningState = new State();

    private QuickSettingsTileView mMobileNetworkTile;
    private RefreshCallback mMobileNetworkCallback;
    private State mMobileNetworkState = new State();

    private QuickSettingsTileView mSleepTimeTile;
    private RefreshCallback mSleepTimeCallback;
    private State mSleepTimeState = new State();

    private QuickSettingsTileView mImmersiveGlobalTile;
    private RefreshCallback mImmersiveGlobalCallback;
    private State mImmersiveGlobalState = new State();

    private QuickSettingsTileView mImmersiveModeTile;
    private RefreshCallback mImmersiveModeCallback;
    private State mImmersiveModeState = new State();

    private RotationLockController mRotationLockController;
    private LocationController mLocationController;

    private QuickSettingsTileView mSyncModeTile;
    private RefreshCallback mSyncModeCallback;
    private State mSyncModeState = new State();

    private QuickSettingsTileView mRingerModeTile;
    private RefreshCallback mRingerModeCallback;
    private State mRingerModeState = new State();

    private QuickSettingsTileView mTorchTile;
    private RefreshCallback mTorchCallback;
    private State mTorchState = new State();

    private Object mSyncObserverHandle = null;

    private SyncStatusObserver mSyncObserver = new SyncStatusObserver() {
        public void onStatusChanged(int which) {
            // update state/view if something happened
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateSyncState();
                }
            });
        }
    };

    public QuickSettingsModel(Context context) {
        mContext = context;
        mHandler = new Handler();
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mBrightnessObserver.startObserving();
                mSleepTimeObserver.startObserving();
                mImmersiveObserver.startObserving();
                mQuietHourObserver.startObserving();
                mRingerObserver.startObserving();
                mNetAdbObserver.startObserving();
                mOnTheGoObserver.startObserving();
                refreshRotationLockTile();
                onBrightnessLevelChanged();
                onNextAlarmChanged();
                onBugreportChanged();
                rebindMediaRouterAsCurrentUser();
                onUsbChanged();
            }
        };

        mNextAlarmObserver = new NextAlarmObserver(mHandler);
        mNextAlarmObserver.startObserving();
        mBugreportObserver = new BugreportObserver(mHandler);
        mBugreportObserver.startObserving();
        mBrightnessObserver = new BrightnessObserver(mHandler);
        mBrightnessObserver.startObserving();
        mNetAdbObserver = new NetAdbObserver(mHandler);
        mNetAdbObserver.startObserving();
        mOnTheGoObserver = new OnTheGoObserver(mHandler);
        mOnTheGoObserver.startObserving();
        mQuietHourObserver = new QuietHourObserver(mHandler);
        mQuietHourObserver.startObserving();
        mMobileNetworkObserver = new NetworkObserver(mHandler);
        mMobileNetworkObserver.startObserving();
        mRingerObserver = new RingerObserver(mHandler);
        mRingerObserver.startObserving();
        mSleepTimeObserver = new SleepTimeObserver(mHandler);
        mSleepTimeObserver.startObserving();
        mImmersiveObserver = new ImmersiveObserver(mHandler);
        mImmersiveObserver.startObserving();

        mMediaRouter = (MediaRouter)context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        rebindMediaRouterAsCurrentUser();

        mRemoteDisplayRouteCallback = new RemoteDisplayRouteCallback();

        mCM = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHasMobileData = mCM.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        IntentFilter alarmIntentFilter = new IntentFilter();
        alarmIntentFilter.addAction(Intent.ACTION_ALARM_CHANGED);
        context.registerReceiver(mAlarmIntentReceiver, alarmIntentFilter);

        IntentFilter bootFilter = new IntentFilter();
        bootFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        context.registerReceiver(mBootReceiver, bootFilter);

        IntentFilter shutdownFilter = new IntentFilter();
        shutdownFilter.addAction(Intent.ACTION_SHUTDOWN);
        context.registerReceiver(mShutdownReceiver, shutdownFilter);

        IntentFilter wifiApStateFilter = new IntentFilter();
        wifiApStateFilter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        context.registerReceiver(mWifiApStateReceiver, wifiApStateFilter);

        IntentFilter ringerIntentFilter = new IntentFilter();
        ringerIntentFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        context.registerReceiver(mRingerIntentReceiver, ringerIntentFilter);

        IntentFilter nfcIntentFilter = new IntentFilter();
        nfcIntentFilter.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        context.registerReceiver(mNfcIntentReceiver, nfcIntentFilter);

        IntentFilter torchIntentFilter = new IntentFilter();
        torchIntentFilter.addAction(OmniTorchConstants.ACTION_STATE_CHANGED);
        context.registerReceiver(mTorchIntentReceiver, torchIntentFilter);

        IntentFilter quietHoursIntentFilter = new IntentFilter();
        quietHoursIntentFilter.addAction(QuietHoursHelper.QUIET_HOURS_START);
        quietHoursIntentFilter.addAction(QuietHoursHelper.QUIET_HOURS_STOP);
        context.registerReceiver(mQuietHoursIntentReceiver, quietHoursIntentFilter);

        if(mSyncObserverHandle != null) {
            //Unregistering sync state listener
            ContentResolver.removeStatusChangeListener(mSyncObserverHandle);
            mSyncObserverHandle = null;
        } else {
            // Registering sync state listener
            mSyncObserverHandle = ContentResolver.addStatusChangeListener(
                    ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, mSyncObserver);
        }

        mRingers = new ArrayList<Ringer>();
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        // Only register for devices that support usb tethering
        if (DeviceUtils.deviceSupportsUsbTether(context)) {
            IntentFilter usbIntentFilter = new IntentFilter();
            usbIntentFilter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
            usbIntentFilter.addAction(UsbManager.ACTION_USB_STATE);
            usbIntentFilter.addAction(Intent.ACTION_MEDIA_SHARED);
            usbIntentFilter.addAction(Intent.ACTION_MEDIA_UNSHARED);
            context.registerReceiver(mUsbIntentReceiver, usbIntentFilter);
        }

        IntentFilter onTheGofilter = new IntentFilter();
        onTheGofilter.addAction(OnTheGoReceiver.ACTION_START);
        onTheGofilter.addAction(OnTheGoReceiver.ACTION_ALREADY_STOP);
        context.registerReceiver(mOnTheGoReceiver, onTheGofilter);
        mUsesAospDialer = Settings.System.getInt(context.getContentResolver(),
                Settings.System.AOSP_DIALER, 0) == 1;
    }


    void updateResources() {
        refreshSettingsTile();
        refreshBatteryTile();
        refreshBluetoothTile();
        refreshBrightnessTile();
        refreshNfcTile();
        refreshRotationLockTile();
        refreshRssiTile();
        refreshLocationTile();
        refreshMobileNetworkTile();
        refreshSleepTimeTile();
        refreshLocationExtraTile();
        refreshImmersiveGlobalTile();
        refreshQuietHourTile();
        refreshImmersiveModeTile();
        refreshWifiApTile();
        onOnTheGoChanged();
        updateRingerState();
        updateRecordingTile();
        refreshThemeTile();
    }

    // Settings
    void addSettingsTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSettingsTile = view;
        mSettingsCallback = cb;
        refreshSettingsTile();
    }
    void refreshSettingsTile() {
        if (mSettingsTile == null) {
            return;
        }
        Resources r = mContext.getResources();
        mSettingsState.label = r.getString(R.string.quick_settings_settings_label);
        mSettingsCallback.refreshView(mSettingsTile, mSettingsState);
    }

    // NFC
    void addNfcTile(QuickSettingsTileView view, RefreshCallback cb) {
        mNfcTile = view;
        mNfcCallback = cb;
        refreshNfcTile();
    }

    void refreshNfcTile() {
        if (mNfcTile == null) {
            return;
        }
        Resources r = mContext.getResources();
        try {
            if(NfcAdapter.getNfcAdapter(mContext).isEnabled()) {
                mNfcState.iconId = R.drawable.ic_qs_nfc_on;
                mNfcState.label = r.getString(R.string.quick_settings_nfc_on);
            } else {
                mNfcState.iconId = R.drawable.ic_qs_nfc_off;
                mNfcState.label = r.getString(R.string.quick_settings_nfc_off);
            }
            mNfcCallback.refreshView(mNfcTile, mNfcState);
        } catch (UnsupportedOperationException e) {
          Log.e("QUICKSETTINGSMODEL", "Error" + e);
        }
    }

    void addRecordingTile(QuickSettingsTileView view, RefreshCallback cb) {
         mRecordingTile = view;
         mRecordingCallback = cb;
         updateRecordingTile();

    }

    synchronized void updateRecordingTile() {
        int playStateName = 0;
        int playStateIcon = 0;

        switch (mRecordingState.recording) {
            case QuickSettings.QR_IDLE:
                Log.v("QUICKSETTINGSMODEL","IDLE");
                playStateName = R.string.quick_settings_quick_record_def;
                playStateIcon = R.drawable.ic_qs_quickrecord;
                break;
            case QuickSettings.QR_PLAYING:
                Log.v("QUICKSETTINGSMODEL","PLAYING");
                playStateName = R.string.quick_settings_quick_record_play;
                playStateIcon = R.drawable.ic_qs_playing;
                break;
            case QuickSettings.QR_RECORDING:
                Log.v("QUICKSETTINGSMODEL","RECORDING");
                playStateName = R.string.quick_settings_quick_record_rec;
                playStateIcon = R.drawable.ic_qs_recording;
                break;
            case QuickSettings.QR_JUST_RECORDED:
                Log.v("QUICKSETTINGSMODEL","JUST RECORDED");
                playStateName = R.string.quick_settings_quick_record_save;
                playStateIcon = R.drawable.ic_qs_saved;
                break;
            case QuickSettings.QR_NO_RECORDING:
                Log.v("QUICKSETTINGSMODEL","NO RECORDING");
                playStateName = R.string.quick_settings_quick_record_nofile;
                playStateIcon = R.drawable.ic_qs_quickrecord;
                break;
        }
        mRecordingState.iconId = playStateIcon;
        mRecordingState.label = mContext.getResources().getString(playStateName);
        mRecordingCallback.refreshView(mRecordingTile, mRecordingState);

    }

    void addCameraTile(QuickSettingsTileView view, RefreshCallback cb) {
        mCameraTile = view;
        mCameraCallback = cb;
        mCameraState.label= mContext.getResources().getString(R.string.quick_settings_camera_label);
        mCameraState.iconId = R.drawable.ic_qs_camera;

        mCameraCallback.refreshView(view, mCameraState);
    }

    void addMusicTile(QuickSettingsTileView view, RefreshCallback cb) {
        mMusicTile = view;
        background = (ImageView) mMusicTile.findViewById(R.id.background);
        mMusicCallback = cb;
 
    }

    void updateMusicTile(String title, Bitmap cover, boolean mActive) {
        if (background != null) {
            if (cover != null) {
                background.setImageDrawable(new BitmapDrawable(cover));
                background.setColorFilter(
                    Color.rgb(123,123,123), android.graphics.PorterDuff.Mode.MULTIPLY);
            } else {
                background.setImageDrawable(null);
                background.setColorFilter(null);
            }
        }
        if (mActive) {
            mMusicState.iconId = R.drawable.ic_qs_media_pause;
            mMusicState.label = title != null
                ? title : mContext.getString(R.string.quick_settings_music_pause);
        } else {
            mMusicState.iconId = R.drawable.ic_qs_media_play;
            mMusicState.label = mContext.getString(R.string.quick_settings_music_play);
        }
         mMusicCallback.refreshView(mMusicTile, mMusicState);
    }

    // User
    void addUserTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUserTile = view;
        mUserCallback = cb;
        mUserCallback.refreshView(view, mUserState);
    }

    void setUserTileInfo(String name, Drawable avatar) {
        mUserState.label = name;
        mUserState.avatar = avatar;
        mUserCallback.refreshView(mUserTile, mUserState);
    }

    // Time
    void addTimeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mTimeTile = view;
        mTimeCallback = cb;
        mTimeCallback.refreshView(view, mTimeState);
    }

    // on-the-go
    void addOnTheGoTile(QuickSettingsTileView view, RefreshCallback cb) {
        mOnTheGoTile = view;
        mOnTheGoCallback = cb;
        onOnTheGoChanged();
    }

    void onOnTheGoChanged() {
        if (mOnTheGoTile == null) {
            return;
        }

        Resources r = mContext.getResources();
        ContentResolver resolver = mContext.getContentResolver();
        int mode = Settings.System.getInt(resolver,
                               Settings.System.ON_THE_GO_CAMERA, 0);
        boolean changeCamera = (mode != 0);
        mOnTheGoState.iconId = changeCamera ? R.drawable.ic_qs_onthego_front
                        : R.drawable.ic_qs_onthego;
        mOnTheGoState.label = changeCamera ? r.getString(R.string.quick_settings_onthego_front)
                        : r.getString(R.string.quick_settings_onthego_back);
        mOnTheGoState.enabled = mOnTheGoRunning;
        mOnTheGoCallback.refreshView(mOnTheGoTile, mOnTheGoState);
    }

    // Alarm
    void addAlarmTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAlarmTile = view;
        mAlarmCallback = cb;
        mAlarmCallback.refreshView(view, mAlarmState);
    }
    void onAlarmChanged(Intent intent) {
        if (mAlarmTile == null) {
            return;
        }

        mAlarmState.enabled = intent.getBooleanExtra("alarmSet", false);
        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }
    void onNextAlarmChanged() {
        if (mAlarmTile == null) {
            return;
        }

        final String alarmText = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.NEXT_ALARM_FORMATTED,
                UserHandle.USER_CURRENT);
        mAlarmState.label = alarmText;

        // When switching users, this is the only clue we're going to get about whether the
        // alarm is actually set, since we won't get the ACTION_ALARM_CHANGED broadcast
        mAlarmState.enabled = ! TextUtils.isEmpty(alarmText);

        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }

    // Usb Mode
    void addUsbModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUsbModeTile = view;
        mUsbModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mUsbConnected) {
                    setUsbTethering(!mUsbTethered);
                }
            }
        });
        mUsbModeCallback = cb;
        onUsbChanged();
    }

    void onUsbChanged() {
        updateState();
        if (mUsbConnected && !mMassStorageActive) {
            if (mUsbTethered) {
                mUsbModeState.iconId = R.drawable.ic_qs_usb_tether_on;
                mUsbModeState.label =
                        mContext.getString(R.string.quick_settings_usb_tether_on_label);
            } else {
                mUsbModeState.iconId = R.drawable.ic_qs_usb_tether_connected;
                mUsbModeState.label =
                        mContext.getString(R.string.quick_settings_usb_tether_connected_label);
            }
            mUsbModeState.enabled = true;
        } else {
            mUsbModeState.enabled = false;
        }
        mUsbModeCallback.refreshView(mUsbModeTile, mUsbModeState);
    }

    // Torch Mode
    void addTorchTile(QuickSettingsTileView view, RefreshCallback cb) {
        mTorchTile = view;
        mTorchTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(OmniTorchConstants.ACTION_TOGGLE_STATE);
                mContext.sendBroadcast(i);
            }
        });
        mTorchCallback = cb;
        onTorchChanged();
    }

    void onTorchChanged() {
        if (mTorchActive) {
            mTorchState.iconId = R.drawable.ic_qs_torch_on;
            mTorchState.label = mContext.getString(R.string.quick_settings_torch);
        } else {
            mTorchState.iconId = R.drawable.ic_qs_torch_off;
            mTorchState.label = mContext.getString(R.string.quick_settings_torch_off);
        }
        mTorchState.enabled = mTorchActive;
        mTorchCallback.refreshView(mTorchTile, mTorchState);
    }

    // Airplane Mode
    void addAirplaneModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAirplaneModeTile = view;
        mAirplaneModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAirplaneModeState.enabled) {
                    setAirplaneModeState(false);
                } else {
                    setAirplaneModeState(true);
                }
            }
        });
        mAirplaneModeCallback = cb;
        int airplaneMode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        onAirplaneModeChanged(airplaneMode != 0);
    }

    private void setAirplaneModeState(boolean enabled) {
        // TODO: Sets the view to be "awaiting" if not already awaiting

        // Change the system setting
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                enabled ? 1 : 0);

        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enabled);
        mContext.sendBroadcast(intent);
    }

    // NetworkSignalChanged callback
    @Override
    public void onAirplaneModeChanged(boolean enabled) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mAirplaneModeState.enabled = enabled;
        mAirplaneModeState.iconId = (enabled ?
                R.drawable.ic_qs_airplane_on :
                R.drawable.ic_qs_airplane_off);
        mAirplaneModeState.label = r.getString(R.string.quick_settings_airplane_mode_label);
        if (mAirplaneModeTile != null) {
            mAirplaneModeCallback.refreshView(mAirplaneModeTile, mAirplaneModeState);
        }
    }

    // Sync Mode
    void addSyncModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSyncModeTile = view;
        mSyncModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getSyncState()) {
                    ContentResolver.setMasterSyncAutomatically(false);
                } else {
                    ContentResolver.setMasterSyncAutomatically(true);
                }
                updateSyncState();
            }
        });
        mSyncModeCallback = cb;
        updateSyncState();
    }

    private boolean getSyncState() {
        return ContentResolver.getMasterSyncAutomatically();
    }

    private void updateSyncState() {
        if (mSyncModeTile == null) {
            return;
        }

        Resources r = mContext.getResources();
        mSyncModeState.enabled = getSyncState();
        mSyncModeState.iconId = (getSyncState() ?
                R.drawable.ic_qs_sync_on :
                R.drawable.ic_qs_sync_off);
        mSyncModeState.label = (getSyncState() ?
                r.getString(R.string.quick_settings_sync) :
                r.getString(R.string.quick_settings_sync_off));
        mSyncModeCallback.refreshView(mSyncModeTile, mSyncModeState);
    }

    // Wifi
    void addWifiTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiTile = view;
        mWifiCallback = cb;
        mWifiCallback.refreshView(mWifiTile, mWifiState);

    }

    // Remove the double quotes that the SSID may contain
    public static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    // Remove the period from the network name
    public static String removeTrailingPeriod(String string) {
        if (string == null) return null;
        final int length = string.length();
        if (string.endsWith(".")) {
            return string.substring(0, length - 1);
        }
        return string;
    }

    // NetworkSignalChanged callback
    @Override
    public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
            boolean activityIn, boolean activityOut,
            String wifiSignalContentDescription, String enabledDesc) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();

        boolean wifiConnected = enabled && (wifiSignalIconId > 0) && (enabledDesc != null);
        boolean wifiNotConnected = (wifiSignalIconId > 0) && (enabledDesc == null);
        mWifiState.enabled = enabled;
        mWifiState.connected = wifiConnected;
        mWifiState.activityIn = enabled && activityIn;
        mWifiState.activityOut = enabled && activityOut;
        if (wifiConnected) {
            mWifiState.iconId = wifiSignalIconId;
            mWifiState.label = removeDoubleQuotes(enabledDesc);
            mWifiState.signalContentDescription = wifiSignalContentDescription;
        } else if (wifiNotConnected) {
            mWifiState.iconId = R.drawable.ic_qs_wifi_0;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_no_wifi);
        } else {
            mWifiState.iconId = R.drawable.ic_qs_wifi_no_network;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_off_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_wifi_off);
        }
        mWifiCallback.refreshView(mWifiTile, mWifiState);
    }

    boolean deviceHasMobileData() {
        return mHasMobileData;
    }

    boolean deviceSupportsLTE() {
        return DeviceUtils.deviceSupportsLte(mContext);
    }

    boolean deviceHasCameraFlash() {
        return DeviceUtils.deviceSupportsCameraFlash();
    }

    // RSSI
    void addRSSITile(QuickSettingsTileView view, RefreshCallback cb) {
        mRSSITile = view;
        mRSSICallback = cb;
        refreshRssiTile();
    }
    // NetworkSignalChanged callback
    @Override
    public void onMobileDataSignalChanged(
            boolean enabled, int mobileSignalIconId, String signalContentDescription,
            int dataTypeIconId, boolean activityIn, boolean activityOut,
            String dataContentDescription,String enabledDesc) {
        if (deviceHasMobileData()) {
            // TODO: If view is in awaiting state, disable
            Resources r = mContext.getResources();
            mRSSIState.signalIconId = enabled && (mobileSignalIconId > 0)
                    ? mobileSignalIconId
                    : R.drawable.ic_qs_signal_no_signal;
            mRSSIState.signalContentDescription = enabled && (mobileSignalIconId > 0)
                    ? signalContentDescription
                    : r.getString(R.string.accessibility_no_signal);
            mRSSIState.dataTypeIconId = enabled && (dataTypeIconId > 0) && !mWifiState.connected
                    ? dataTypeIconId
                    : 0;
            mRSSIState.activityIn = enabled && activityIn;
            mRSSIState.activityOut = enabled && activityOut;
            mRSSIState.dataContentDescription = enabled && (dataTypeIconId > 0) && !mWifiState.connected
                    ? dataContentDescription
                    : r.getString(R.string.accessibility_no_data);
            mRSSIState.label = enabled
                    ? removeTrailingPeriod(enabledDesc)
                    : r.getString(R.string.quick_settings_rssi_emergency_only);
            refreshRssiTile();
        }
    }

    void refreshRssiTile() {
        if (mRSSITile != null) {
            mRSSICallback.refreshView(mRSSITile, mRSSIState);
        }
    }

    // Mobile Network
    void addMobileNetworkTile(QuickSettingsTileView view, RefreshCallback cb) {
        mMobileNetworkTile = view;
        mMobileNetworkCallback = cb;
        onMobileNetworkChanged();
    }

    void onMobileNetworkChanged() {
        if (deviceHasMobileData() && mUsesAospDialer) {
            mMobileNetworkState.label = getNetworkType(mContext.getResources());
            mMobileNetworkState.iconId = getNetworkTypeIcon();
            mMobileNetworkCallback.refreshView(mMobileNetworkTile, mMobileNetworkState);
        }
    }

    void refreshMobileNetworkTile() {
        onMobileNetworkChanged();
    }

    protected void toggleMobileNetworkState() {
        TelephonyManager tm = (TelephonyManager)
            mContext.getSystemService(Context.TELEPHONY_SERVICE);
        boolean usesQcLte = SystemProperties.getBoolean(
                        "ro.config.qc_lte_network_modes", false);
        int network = getCurrentPreferredNetworkMode(mContext);
        switch(network) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_WCDMA:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
                // 2G Only
                tm.toggleMobileNetwork(Phone.NT_MODE_GSM_ONLY);
                break;
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                // 2G Only
                tm.toggleMobileNetwork(Phone.NT_MODE_CDMA_NO_EVDO);
                break;
            case Phone.NT_MODE_GSM_ONLY:
                // 3G Only
                tm.toggleMobileNetwork(Phone.NT_MODE_WCDMA_ONLY);
                break;
            case Phone.NT_MODE_WCDMA_ONLY:
                // 2G/3G
                tm.toggleMobileNetwork(Phone.NT_MODE_WCDMA_PREF);
                break;
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_CDMA_NO_EVDO:
                // 2G/3G
                tm.toggleMobileNetwork(Phone.NT_MODE_CDMA);
                break;
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_GSM_UMTS:
                // LTE
                if (deviceSupportsLTE()) {
                    if (usesQcLte) {
                        tm.toggleMobileNetwork(Phone.NT_MODE_LTE_CDMA_AND_EVDO);
                    } else {
                        tm.toggleMobileNetwork(Phone.NT_MODE_LTE_GSM_WCDMA);
                    }
                } else {
                    tm.toggleMobileNetwork(Phone.NT_MODE_GSM_ONLY);
                }
                break;
            case Phone.NT_MODE_CDMA:
                tm.toggleMobileNetwork(Phone.NT_MODE_LTE_CDMA_AND_EVDO);
                break;
        }
    }

    private String getNetworkType(Resources r) {
        int network = getCurrentPreferredNetworkMode(mContext);
        switch (network) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
            case Phone.NT_MODE_GSM_UMTS:
            case Phone.NT_MODE_WCDMA_ONLY:
            case Phone.NT_MODE_GSM_ONLY:
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_CDMA:

                return r.getString(R.string.quick_settings_network_type);
        }
        return r.getString(R.string.quick_settings_network_unknown);
    }

    private int getNetworkTypeIcon() {
        int network = getCurrentPreferredNetworkMode(mContext);
        switch (network) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
                return R.drawable.ic_qs_lte_on;
            case Phone.NT_MODE_WCDMA_ONLY:
                return R.drawable.ic_qs_3g_on;
            case Phone.NT_MODE_GSM_ONLY:
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_EVDO_NO_CDMA:
                return R.drawable.ic_qs_2g_on;
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_GSM_UMTS:
            case Phone.NT_MODE_CDMA:
                return R.drawable.ic_qs_2g3g_on;
        }
        return R.drawable.ic_qs_unexpected_network;
    }

    public static int getCurrentPreferredNetworkMode(Context context) {
        int network = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE, -1);
        return network;
    }

    public boolean isMobileDataEnabled(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getMobileDataEnabled();
    }

    // Bluetooth
    void addBluetoothTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothTile = view;
        mBluetoothCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothState.enabled = adapter.isEnabled();
        mBluetoothState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothState);
    }
    void addBluetoothExtraTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothExtraTile = view;
        mBluetoothExtraCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothExtraState.enabled = adapter.isEnabled();
        mBluetoothExtraState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothExtraState);
    }
    boolean deviceSupportsBluetooth() {
        return (BluetoothAdapter.getDefaultAdapter() != null);
    }
    // BluetoothController callback
    @Override
    public void onBluetoothStateChange(boolean on) {
        mBluetoothState.enabled = on;
        onBluetoothStateChange(mBluetoothState);
    }
    public void onBluetoothStateChange(BluetoothState bluetoothStateIn) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mBluetoothState.enabled = bluetoothStateIn.enabled;
        mBluetoothState.connected = bluetoothStateIn.connected;
        if (mBluetoothState.enabled) {
            if (mBluetoothState.connected) {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_on;
                mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_connected);
            } else {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_not_connected;
                mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_on);
            }
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_label);
        } else {
            mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_off;
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_off_label);
            mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_off);
        }

        if (mBluetoothExtraTile != null) {
            final BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter.getScanMode()
                    == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                mBluetoothExtraState.iconId = R.drawable.ic_qs_bluetooth_discoverable;
                mBluetoothExtraState.label = r.getString(R.string.quick_settings_bluetooth_label);
            } else {
                mBluetoothExtraState.iconId = R.drawable.ic_qs_bluetooth_discoverable_off;
                mBluetoothExtraState.label = r.getString(R.string.quick_settings_bluetooth_off_label);
            }
        }

        mBluetoothCallback.refreshView(mBluetoothTile, mBluetoothState);

        if (mBluetoothExtraTile != null) {
            mBluetoothExtraCallback.refreshView(mBluetoothExtraTile, mBluetoothExtraState);
        }
    }
    void refreshBluetoothTile() {
        if (mBluetoothTile != null) {
            onBluetoothStateChange(mBluetoothState.enabled);
        }
    }

    // Battery
    void addBatteryTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBatteryTile = view;
        mBatteryCallback = cb;
        refreshBatteryTile();
    }
    // BatteryController callback
    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn) {
        mBatteryState.batteryLevel = level;
        mBatteryState.pluggedIn = pluggedIn;
        refreshBatteryTile();
    }
    void refreshBatteryTile() {
        if (mBatteryCallback == null) {
            return;
        }
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }

    // Location
    void addLocationTile(QuickSettingsTileView view, RefreshCallback cb) {
        mLocationTile = view;
        mLocationCallback = cb;
        mLocationCallback.refreshView(view, mLocationState);
    }

    void refreshLocationTile() {
        if (mLocationTile != null) {
            onLocationSettingsChanged(mLocationState.enabled);
        }
    }

    @Override
    public void onLocationSettingsChanged(boolean locationEnabled) {
        int textResId = locationEnabled ? R.string.quick_settings_location_label
                : R.string.quick_settings_location_off_label;
        String label = mContext.getText(textResId).toString();
        int locationIconId = locationEnabled
                ? R.drawable.ic_qs_location_on : R.drawable.ic_qs_location_off;
        mLocationState.enabled = locationEnabled;
        mLocationState.label = label;
        mLocationState.iconId = locationIconId;
        mLocationCallback.refreshView(mLocationTile, mLocationState);
        refreshLocationExtraTile();
    }

    void addLocationExtraTile(QuickSettingsTileView view, LocationController controller, RefreshCallback cb) {
        mLocationExtraTile = view;
        mLocationController = controller;
        mLocationExtraCallback = cb;
        mLocationExtraCallback.refreshView(mLocationExtraTile, mLocationExtraState);
    }

    void refreshLocationExtraTile() {
        if (mLocationExtraTile != null) {
            onLocationExtraSettingsChanged(mLocationController.locationMode(), mLocationState.enabled);
        }
    }

    private void onLocationExtraSettingsChanged(int mode, boolean locationEnabled) {
        int locationIconId = locationEnabled
                ? R.drawable.ic_qs_location_accuracy_on : R.drawable.ic_qs_location_accuracy_off;
        mLocationExtraState.enabled = locationEnabled;
        mLocationExtraState.label = getLocationMode(mContext.getResources(), mode);
        mLocationExtraState.iconId = locationIconId;
        mLocationExtraCallback.refreshView(mLocationExtraTile, mLocationExtraState);
    }

    private String getLocationMode(Resources r, int location) {
        switch (location) {
            case Settings.Secure.LOCATION_MODE_SENSORS_ONLY:
                return r.getString(R.string.quick_settings_location_mode_sensors_label);
            case Settings.Secure.LOCATION_MODE_BATTERY_SAVING:
                return r.getString(R.string.quick_settings_location_mode_battery_label);
            case Settings.Secure.LOCATION_MODE_HIGH_ACCURACY:
                return r.getString(R.string.quick_settings_location_mode_high_label);
        }
        return r.getString(R.string.quick_settings_location_off_label);
    }

    // Bug report
    void addBugreportTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBugreportTile = view;
        mBugreportCallback = cb;
        onBugreportChanged();
    }

    // SettingsObserver callback
    public void onBugreportChanged() {
        if (mBugreportTile == null) {
            return;
        }

        final ContentResolver cr = mContext.getContentResolver();
        boolean enabled = false;
        try {
            enabled = (Settings.Global.getInt(cr, Settings.Global.BUGREPORT_IN_POWER_MENU) != 0);
        } catch (SettingNotFoundException e) {
        }

        mBugreportState.enabled = enabled && mUserTracker.isCurrentUserOwner();
        mBugreportCallback.refreshView(mBugreportTile, mBugreportState);
    }

    // Remote Display
    void addRemoteDisplayTile(QuickSettingsTileView view, RefreshCallback cb) {
        mRemoteDisplayTile = view;
        mRemoteDisplayCallback = cb;
        final int[] count = new int[1];
        mRemoteDisplayTile.setOnPrepareListener(new QuickSettingsTileView.OnPrepareListener() {
            @Override
            public void onPrepare() {
                mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                        mRemoteDisplayRouteCallback,
                        MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
                updateRemoteDisplays();
            }
            @Override
            public void onUnprepare() {
                mMediaRouter.removeCallback(mRemoteDisplayRouteCallback);
            }
        });

        updateRemoteDisplays();
    }

    private void rebindMediaRouterAsCurrentUser() {
        mMediaRouter.rebindAsUser(mUserTracker.getCurrentUserId());
    }

    private void updateRemoteDisplays() {
        MediaRouter.RouteInfo connectedRoute = mMediaRouter.getSelectedRoute(
                MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean enabled = connectedRoute != null
                && connectedRoute.matchesTypes(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean connecting;
        if (enabled) {
            connecting = connectedRoute.isConnecting();
        } else {
            connectedRoute = null;
            connecting = false;
            enabled = mMediaRouter.isRouteAvailable(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                    MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE);
        }

        mRemoteDisplayState.enabled = enabled;
        if (connectedRoute != null) {
            mRemoteDisplayState.label = connectedRoute.getName().toString();
            mRemoteDisplayState.iconId = connecting ?
                    R.drawable.ic_qs_cast_connecting : R.drawable.ic_qs_cast_connected;
        } else {
            mRemoteDisplayState.label = mContext.getString(
                    R.string.quick_settings_remote_display_no_connection_label);
            mRemoteDisplayState.iconId = R.drawable.ic_qs_cast_available;
        }
        mRemoteDisplayCallback.refreshView(mRemoteDisplayTile, mRemoteDisplayState);
    }

    // IME
    void addImeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImeTile = view;
        mImeCallback = cb;
        mImeCallback.refreshView(view, mImeState);
    }

    /* This implementation is taken from
       InputMethodManagerService.needsToShowImeSwitchOngoingNotification(). */
    private boolean needsToShowImeSwitchOngoingNotification(InputMethodManager imm) {
        List<InputMethodInfo> imis = imm.getEnabledInputMethodList();
        final int N = imis.size();
        if (N > 2) return true;
        if (N < 1) return false;
        int nonAuxCount = 0;
        int auxCount = 0;
        InputMethodSubtype nonAuxSubtype = null;
        InputMethodSubtype auxSubtype = null;
        for(int i = 0; i < N; ++i) {
            final InputMethodInfo imi = imis.get(i);
            final List<InputMethodSubtype> subtypes = imm.getEnabledInputMethodSubtypeList(imi,
                    true);
            final int subtypeCount = subtypes.size();
            if (subtypeCount == 0) {
                ++nonAuxCount;
            } else {
                for (int j = 0; j < subtypeCount; ++j) {
                    final InputMethodSubtype subtype = subtypes.get(j);
                    if (!subtype.isAuxiliary()) {
                        ++nonAuxCount;
                        nonAuxSubtype = subtype;
                    } else {
                        ++auxCount;
                        auxSubtype = subtype;
                    }
                }
            }
        }
        if (nonAuxCount > 1 || auxCount > 1) {
            return true;
        } else if (nonAuxCount == 1 && auxCount == 1) {
            if (nonAuxSubtype != null && auxSubtype != null
                    && (nonAuxSubtype.getLocale().equals(auxSubtype.getLocale())
                            || auxSubtype.overridesImplicitlyEnabledSubtype()
                            || nonAuxSubtype.overridesImplicitlyEnabledSubtype())
                    && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER)) {
                return false;
            }
            return true;
        }
        return false;
    }

    void onImeWindowStatusChanged(boolean visible) {
        InputMethodManager imm =
                (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imis = imm.getInputMethodList();

        mImeState.enabled = (visible && needsToShowImeSwitchOngoingNotification(imm));
        mImeState.label = getCurrentInputMethodName(mContext, mContext.getContentResolver(),
                imm, imis, mContext.getPackageManager());
        if ((mImeCallback != null) && (mImeTile != null)) {
            mImeCallback.refreshView(mImeTile, mImeState);
        }
    }

    private static String getCurrentInputMethodName(Context context, ContentResolver resolver,
            InputMethodManager imm, List<InputMethodInfo> imis, PackageManager pm) {
        if (resolver == null || imis == null) return null;
        final String currentInputMethodId = Settings.Secure.getString(resolver,
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (TextUtils.isEmpty(currentInputMethodId)) return null;
        for (InputMethodInfo imi : imis) {
            if (currentInputMethodId.equals(imi.getId())) {
                final InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
                final CharSequence summary = subtype != null
                        ? subtype.getDisplayName(context, imi.getPackageName(),
                                imi.getServiceInfo().applicationInfo)
                        : context.getString(R.string.quick_settings_ime_label);
                return summary.toString();
            }
        }
        return null;
    }

    // Rotation lock
    void addRotationLockTile(QuickSettingsTileView view,
            RotationLockController rotationLockController,
            RefreshCallback cb) {
        mRotationLockTile = view;
        mRotationLockCallback = cb;
        mRotationLockController = rotationLockController;
        onRotationLockChanged();
    }

    void onRotationLockChanged() {
        onRotationLockStateChanged(mRotationLockController.isRotationLocked(),
                mRotationLockController.isRotationLockAffordanceVisible());
    }

    @Override
    public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
        mRotationLockState.visible = affordanceVisible;
        mRotationLockState.enabled = rotationLocked;
        mRotationLockState.iconId = rotationLocked
                ? R.drawable.ic_qs_rotation_locked
                : R.drawable.ic_qs_auto_rotate;
        mRotationLockState.label = rotationLocked
                ? mContext.getString(R.string.quick_settings_rotation_locked_label)
                : mContext.getString(R.string.quick_settings_rotation_unlocked_label);
        mRotationLockCallback.refreshView(mRotationLockTile, mRotationLockState);
    }

    void refreshRotationLockTile() {
        if (mRotationLockTile != null) {
            onRotationLockChanged();
        }
    }

    // Brightness
    void addBrightnessTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBrightnessTile = view;
        mBrightnessCallback = cb;
        onBrightnessLevelChanged();
    }

    @Override
    public void onBrightnessLevelChanged() {
        Resources r = mContext.getResources();
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                mUserTracker.getCurrentUserId());
        mBrightnessState.autoBrightness =
                (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mBrightnessState.iconId = mBrightnessState.autoBrightness
                ? R.drawable.ic_qs_brightness_auto_on
                : R.drawable.ic_qs_brightness_auto_off;
        mBrightnessState.label = r.getString(R.string.quick_settings_brightness_label);
        if (mBrightnessTile != null) {
            mBrightnessCallback.refreshView(mBrightnessTile, mBrightnessState);
        }
    }

    void refreshBrightnessTile() {
       if (mBrightnessTile != null) {
           onBrightnessLevelChanged();
       }
    }

    // FastCharge 
    void addFastChargeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mFastChargeTile = view;
        mFastChargeCallback = cb;
        updateFastChargeTile();

    }

    public synchronized void updateFastChargeTile() {
        String label = mContext.getString(R.string.quick_settings_fcharge);

        if(isFastChargeOn()) {
            mFastChargeState.iconId = R.drawable.ic_qs_fcharge_on;
            mFastChargeState.label = label + " " + mContext.getString(R.string.quick_settings_label_enabled);
            mFastChargeState.enabled=true;
        } else {
            mFastChargeState.iconId = R.drawable.ic_qs_fcharge_off;
            mFastChargeState.label = label + " " + mContext.getString(R.string.quick_settings_label_disabled);
            mFastChargeState.enabled=false;
        }
        mFastChargeCallback.refreshView(mFastChargeTile, mFastChargeState);
    }

    public boolean isFastChargeOn() {
        try {
            String fchargePath = mContext.getResources()
                    .getString(com.android.internal.R.string.config_fastChargePath);
            if (!fchargePath.isEmpty()) {
                File fastcharge = new File(fchargePath);
                if (fastcharge.exists()) {
                    FileReader reader = new FileReader(fastcharge);
                    BufferedReader breader = new BufferedReader(reader);
                    String line = breader.readLine();
                    breader.close();
                    Settings.System.putInt(mContext.getContentResolver(),
                            Settings.System.FCHARGE_ENABLED, line.equals("1") ? 1 : 0);
                    return (line.equals("1"));
                }
            }
        } catch (IOException e) {
            Log.e("FChargeToggle", "Couldn't read fast_charge file");
            Settings.System.putInt(mContext.getContentResolver(),
                 Settings.System.FCHARGE_ENABLED, 0);
        }
        return false;
    }

    void addThemeTile(QuickSettingsTileView view, RefreshCallback cb) {
       mThemeTile = view;
       mThemeCallback = cb;
       onThemeChanged();
    }

    private void onThemeChanged() {
        Resources r = mContext.getResources();
        int themeAutoMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.UI_THEME_AUTO_MODE, QuickSettings.THEME_MODE_MANUAL,
                UserHandle.USER_CURRENT);

        switch (themeAutoMode) {
            case QuickSettings.THEME_MODE_MANUAL:
                mThemeState.iconId = R.drawable.ic_qs_theme_manual;
                break;
            case QuickSettings.THEME_MODE_LIGHT_SENSOR:
                mThemeState.iconId = R.drawable.ic_qs_theme_lightsensor;
                break;
            case QuickSettings.THEME_MODE_TWILIGHT:
                mThemeState.iconId = R.drawable.ic_qs_theme_twilight;
                break;
        }

        if (mContext.getResources().getConfiguration().uiThemeMode
                == Configuration.UI_THEME_MODE_HOLO_DARK) {
            mThemeState.label = r.getString(R.string.quick_settings_theme_switch_dark);
        } else {
            mThemeState.label = r.getString(R.string.quick_settings_theme_switch_light);
        }
        mThemeState.enabled = true; 
        mThemeCallback.refreshView(mThemeTile, mThemeState);
     }


    void refreshThemeTile() {
       if (mThemeTile != null) {
           onThemeChanged();
       }
    }

    // Network ADB Tile
    void addNetAdbTile(QuickSettingsTileView view, RefreshCallback cb) {
        mNetAdbTile = view;
        mNetAdbTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean checkModeOn = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ADB_PORT, -1) > 0;
                Settings.Secure.putInt(mContext.getContentResolver(), Settings.Secure.ADB_PORT,
                    checkModeOn ? -1 : 5555);
            }
        });
        mNetAdbCallback = cb;
        onNetAdbChanged();
    }

    private void onNetAdbChanged() {
        int port = Settings.Secure.getInt(mContext.getContentResolver(),
            Settings.Secure.ADB_PORT, 0);
        boolean netAdbOn = port > 0;

        WifiInfo wifiInfo = null;

        if (netAdbOn) {
            WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            wifiInfo = wifiManager.getConnectionInfo();

            mNetAdbState.iconId = R.drawable.ic_qs_netadb_on;

            if (wifiInfo != null) {
                mNetAdbState.label = NetworkUtils.intToInetAddress(
                    wifiInfo.getIpAddress()).getHostAddress() + ":" + String.valueOf(port);
            } else {
                mNetAdbState.label = mContext.getString(R.string.quick_settings_network_adb_on);
            }
        } else {
            mNetAdbState.iconId = R.drawable.ic_qs_netadb_off;
            mNetAdbState.label = mContext.getString(R.string.quick_settings_network_adb_off);
        }
        mNetAdbState.enabled = netAdbOn;
        mNetAdbCallback.refreshView(mNetAdbTile, mNetAdbState);
    }

    // QuietHour
    void addQuietHourTile(QuickSettingsTileView view, RefreshCallback cb) {
        mQuietHourTile = view;
        mQuietHourCallback = cb;
        onQuietHourChanged();
    }

    private void onQuietHourChanged() {
        Resources r = mContext.getResources();
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_ENABLED, 0,
                mUserTracker.getCurrentUserId());
        int paused = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_PAUSED, 0,
                mUserTracker.getCurrentUserId());
        int forced = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_FORCED, 0,
                mUserTracker.getCurrentUserId());

        mQuietHourState.isEnabled = (mode == 1);
        mQuietHourState.isPaused = (paused == 1);
        mQuietHourState.isForced = (forced == 1);
        mQuietHourState.isActive = QuietHoursHelper.inQuietHours(mContext, null);

        mQuietHourState.iconId = mQuietHourState.isActive
                ? R.drawable.ic_qs_quiet_hours_on
                : R.drawable.ic_qs_quiet_hours_off;

        mQuietHourState.label = r.getString(R.string.quick_settings_quiethours_off_label);
        if (mQuietHourState.isEnabled){
            mQuietHourState.label = r.getString(R.string.quick_settings_quiethours_label);
        }
        if (mQuietHourState.isActive){
            mQuietHourState.label = r.getString(R.string.quick_settings_quiethours_active_label);
        }
        if (mQuietHourState.isPaused){
            mQuietHourState.label = r.getString(R.string.quick_settings_quiethours_paused_label);
        }
        if (mQuietHourTile != null) {
            mQuietHourCallback.refreshView(mQuietHourTile, mQuietHourState);
        }
    }

    void refreshQuietHourTile() {
        onQuietHourChanged();
    }

    // SSL CA Cert warning.
    public void addSslCaCertWarningTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSslCaCertWarningTile = view;
        mSslCaCertWarningCallback = cb;
        // Set a sane default while we wait for the AsyncTask to finish (no cert).
        setSslCaCertWarningTileInfo(false, true);
    }

    public void setSslCaCertWarningTileInfo(boolean hasCert, boolean isManaged) {
        Resources r = mContext.getResources();
        mSslCaCertWarningState.enabled = hasCert;
        if (isManaged) {
            mSslCaCertWarningState.iconId = R.drawable.ic_qs_certificate_info;
        } else {
            mSslCaCertWarningState.iconId = android.R.drawable.stat_notify_error;
        }
        mSslCaCertWarningState.label = r.getString(R.string.ssl_ca_cert_warning);
        mSslCaCertWarningCallback.refreshView(mSslCaCertWarningTile, mSslCaCertWarningState);
    }


    private void updateState() {
        mUsbRegexs = mCM.getTetherableUsbRegexs();

        String[] available = mCM.getTetherableIfaces();
        String[] tethered = mCM.getTetheredIfaces();
        String[] errored = mCM.getTetheringErroredIfaces();
        updateState(available, tethered, errored);
    }

    private void updateState(String[] available, String[] tethered,
            String[] errored) {
        updateUsbState(available, tethered, errored);
    }

    private void updateUsbState(String[] available, String[] tethered,
            String[] errored) {

        mUsbTethered = false;
        for (String s : tethered) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) mUsbTethered = true;
            }
        }

    }

    private void setUsbTethering(boolean enabled) {
        if (mCM.setUsbTethering(enabled) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            return;
        }
    }

    // Sleep: Screen timeout sub-tile (sleep time tile)
    private static final int SCREEN_TIMEOUT_15     =   15000;
    private static final int SCREEN_TIMEOUT_30     =   30000;
    private static final int SCREEN_TIMEOUT_60     =   60000;
    private static final int SCREEN_TIMEOUT_120    =  120000;
    private static final int SCREEN_TIMEOUT_300    =  300000;
    private static final int SCREEN_TIMEOUT_600    =  600000;
    private static final int SCREEN_TIMEOUT_1800   = 1800000;

    void addSleepTimeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSleepTimeTile = view;
        mSleepTimeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                screenTimeoutChangeState();
                refreshSleepTimeTile();
            }
        });
        mSleepTimeCallback = cb;
        refreshSleepTimeTile();
    }

    private void refreshSleepTimeTile() {
        mSleepTimeState.enabled = true;
        mSleepTimeState.iconId = R.drawable.ic_qs_sleep_time;
        mSleepTimeState.label = screenTimeoutGetLabel(getScreenTimeout());
        mSleepTimeCallback.refreshView(mSleepTimeTile, mSleepTimeState);
    }

    protected int getScreenTimeout() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, SCREEN_TIMEOUT_30);
    }

    protected void screenTimeoutChangeState() {
        final int currentScreenTimeout = getScreenTimeout();
        int screenTimeout = currentScreenTimeout;

        switch(currentScreenTimeout) {
            case SCREEN_TIMEOUT_15:
                screenTimeout = SCREEN_TIMEOUT_30;
                break;
            case SCREEN_TIMEOUT_30:
                screenTimeout = SCREEN_TIMEOUT_60;
                break;
            case SCREEN_TIMEOUT_60:
                screenTimeout = SCREEN_TIMEOUT_120;
                break;
            case SCREEN_TIMEOUT_120:
                screenTimeout = SCREEN_TIMEOUT_300;
                break;
            case SCREEN_TIMEOUT_300:
                screenTimeout = SCREEN_TIMEOUT_600;
                break;
            case SCREEN_TIMEOUT_600:
                screenTimeout = SCREEN_TIMEOUT_1800;
                break;
            case SCREEN_TIMEOUT_1800:
                screenTimeout = SCREEN_TIMEOUT_15;
                break;
        }

        Settings.System.putInt(
                mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, screenTimeout);
        }

    protected String screenTimeoutGetLabel(int currentTimeout) {
        switch(currentTimeout) {
            case SCREEN_TIMEOUT_15:
                return mContext.getString(R.string.quick_settings_sleep_time_15_label);
            case SCREEN_TIMEOUT_30:
                return mContext.getString(R.string.quick_settings_sleep_time_30_label);
            case SCREEN_TIMEOUT_60:
                return mContext.getString(R.string.quick_settings_sleep_time_60_label);
            case SCREEN_TIMEOUT_120:
                return mContext.getString(R.string.quick_settings_sleep_time_120_label);
            case SCREEN_TIMEOUT_300:
                return mContext.getString(R.string.quick_settings_sleep_time_300_label);
            case SCREEN_TIMEOUT_600:
                return mContext.getString(R.string.quick_settings_sleep_time_600_label);
            case SCREEN_TIMEOUT_1800:
                return mContext.getString(R.string.quick_settings_sleep_time_1800_label);
        }
        return mContext.getString(R.string.quick_settings_sleep_time_unknown_label);
    }

    // Immersive mode
    public static final int IMMERSIVE_MODE_OFF = 0;
    public static final int IMMERSIVE_MODE_FULL = 1;
    public static final int IMMERSIVE_MODE_HIDE_ONLY_NAVBAR = 2;
    public static final int IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR = 3;

    void addImmersiveGlobalTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImmersiveGlobalTile = view;
        mImmersiveGlobalCallback = cb;
        onImmersiveGlobalChanged();
    }

    void addImmersiveModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImmersiveModeTile = view;
        mImmersiveModeCallback = cb;
        onImmersiveModeChanged();
    }

    private void onImmersiveGlobalChanged() {
        Resources r = mContext.getResources();
        final int mode = getImmersiveMode();
        final boolean isDefault = isImmersiveDefaultAppMode();
        final boolean enabled = isPieEnabled();
        if (mode == IMMERSIVE_MODE_OFF) {
            mImmersiveGlobalState.iconId = enabled ?
                  R.drawable.ic_qs_pie_global_off : R.drawable.ic_qs_immersive_global_off;
            mImmersiveGlobalState.label = r.getString(R.string.quick_settings_immersive_global_off_label);
        } else {
            mImmersiveGlobalState.iconId = enabled ?
                  R.drawable.ic_qs_pie_global_on : R.drawable.ic_qs_immersive_global_on;
            mImmersiveGlobalState.label = r.getString(R.string.quick_settings_immersive_global_on_label);
        }
        mImmersiveGlobalCallback.refreshView(mImmersiveGlobalTile, mImmersiveGlobalState);
    }

    private void onImmersiveModeChanged() {
        Resources r = mContext.getResources();
        final int mode = getImmersiveMode();
        switch(mode) {
            case IMMERSIVE_MODE_OFF:
                mImmersiveModeState.iconId = R.drawable.ic_qs_immersive_off;
                mImmersiveModeState.label = r.getString(R.string.quick_settings_immersive_mode_off_label);
                break;
            case IMMERSIVE_MODE_FULL:
                mImmersiveModeState.iconId = R.drawable.ic_qs_immersive_full;
                mImmersiveModeState.label = r.getString(R.string.quick_settings_immersive_mode_full_label);
                break;
            case IMMERSIVE_MODE_HIDE_ONLY_NAVBAR:
                mImmersiveModeState.iconId =
                        R.drawable.ic_qs_immersive_navigation_bar_off;
                mImmersiveModeState.label =
                        r.getString(R.string.quick_settings_immersive_mode_no_status_bar_label);
                break;
            case IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR:
                mImmersiveModeState.iconId =
                        R.drawable.ic_qs_immersive_status_bar_off;
                mImmersiveModeState.label =
                        r.getString(R.string.quick_settings_immersive_mode_no_navigation_bar_label);
                break;
        }
        mImmersiveModeCallback.refreshView(mImmersiveModeTile, mImmersiveModeState);
    }

    void refreshImmersiveGlobalTile() {
        onImmersiveGlobalChanged();
    }

    void refreshImmersiveModeTile() {
        onImmersiveModeChanged();
    }

    protected int getImmersiveMode() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_MODE, 0);
    }

    protected boolean isPieEnabled() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.PIE_STATE, 0) == 1;
    }

    protected boolean isImmersiveDefaultAppMode() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_DEFAULT_APP_MODE, 0) == 1;
    }

    private void setImmersiveMode(int style) {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_MODE, style);
        if (style != 0) {
            setImmersiveLastActiveState(style);
        }
    }

    private int getImmersiveLastActiveState() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_LAST_ACTIVE_STATE, 1);
    }

    private void setImmersiveLastActiveState(int style) {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_LAST_ACTIVE_STATE, style);
    }

    protected void switchImmersiveGlobal() {
        final int current = getImmersiveMode();
        final int lastState = getImmersiveLastActiveState();
        switch(current) {
            case IMMERSIVE_MODE_OFF:
                setImmersiveMode(lastState);
                break;
            case IMMERSIVE_MODE_FULL:
            case IMMERSIVE_MODE_HIDE_ONLY_NAVBAR:
            case IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR:
                setImmersiveMode(IMMERSIVE_MODE_OFF);
                break;
        }
    }

    protected void switchImmersiveMode() {
        final int current = getImmersiveMode();
        switch(current) {
            case IMMERSIVE_MODE_FULL:
                setImmersiveMode(IMMERSIVE_MODE_HIDE_ONLY_NAVBAR);
                break;
            case IMMERSIVE_MODE_HIDE_ONLY_NAVBAR:
                setImmersiveMode(IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR);
                break;
            case IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR:
                setImmersiveMode(IMMERSIVE_MODE_FULL);
                break;
        }
    }

    // Wifi Ap
    void addWifiApTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiApTile = view;
        mWifiApCallback = cb;
        onWifiApChanged();
    }

    void onWifiApChanged() {
        if (isWifiApEnabled()) {
            mWifiApState.iconId = R.drawable.ic_qs_wifi_ap_on;
            mWifiApState.label = mContext.getString(R.string.quick_settings_wifi_ap_label);
        } else {
            mWifiApState.iconId = R.drawable.ic_qs_wifi_ap_off;
            mWifiApState.label = mContext.getString(R.string.quick_settings_wifi_ap_off_label);
        }
        mWifiApState.enabled = isWifiApEnabled();
        mWifiApCallback.refreshView(mWifiApTile, mWifiApState);
    }

    void refreshWifiApTile() {
        onWifiApChanged();
    }

    protected void toggleWifiApState() {
        setWifiApEnabled(isWifiApEnabled() ? false : true);
    }

    protected boolean isWifiApEnabled() {
        WifiManager mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        int state = mWifiManager.getWifiApState();
        boolean active;
        switch (state) {
            case WifiManager.WIFI_AP_STATE_ENABLING:
            case WifiManager.WIFI_AP_STATE_ENABLED:
                active = true;
                break;
            case WifiManager.WIFI_AP_STATE_DISABLING:
            case WifiManager.WIFI_AP_STATE_DISABLED:
            default:
                active = false;
                break;
        }
        return active;
    }

    protected void setWifiApEnabled(boolean enable) {
        WifiManager mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        final ContentResolver cr = mContext.getContentResolver();
        /**
         * Disable Wifi if enabling tethering
         */
        int wifiState = mWifiManager.getWifiState();
        if (enable && ((wifiState == WifiManager.WIFI_STATE_ENABLING) ||
                (wifiState == WifiManager.WIFI_STATE_ENABLED))) {
            mWifiManager.setWifiEnabled(false);
            Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 1);
        }

        // Turn on the Wifi AP
        mWifiManager.setWifiApEnabled(null, enable);

        /**
         *  If needed, restore Wifi on tether disable
         */
        if (!enable) {
            int wifiSavedState = 0;
            try {
                wifiSavedState = Settings.Global.getInt(cr, Settings.Global.WIFI_SAVED_STATE);
            } catch (Settings.SettingNotFoundException e) {
                // Do nothing here
            }
            if (wifiSavedState == 1) {
                mWifiManager.setWifiEnabled(true);
                Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 0);
            }
        }
    }

    // Ringer Mode
    void addRingerModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mRingerModeTile = view;
        mRingerModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRingerState();
                updateRingerState();
            }
        });
        mRingerModeCallback = cb;
        updateRingerState();
    }

    private void updateRingerState() {
        Resources r = mContext.getResources();
        updateRingerSettings();
        findCurrentState();
        mRingerModeState.enabled = true;
        mRingerModeState.iconId = mRingers.get(mRingerIndex).mDrawable;
        mRingerModeState.label = r.getString(mRingers.get(mRingerIndex).mString);
        mRingerModeCallback.refreshView(mRingerModeTile, mRingerModeState);
    }


    private void findCurrentState() {
        boolean vibrateWhenRinging = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.VIBRATE_WHEN_RINGING, 0, UserHandle.USER_CURRENT) == 1;
        int ringerMode = mAudioManager.getRingerMode();

        mRingerIndex = 0;

        for (int i = 0; i < mRingers.size(); i++) {
            Ringer r = mRingers.get(i);
            if (ringerMode == r.mRingerMode && vibrateWhenRinging == r.mVibrateWhenRinging) {
                mRingerIndex = i;
                break;
            }
        }
    }

    private void updateRingerSettings() {
        boolean hasVibrator = mVibrator.hasVibrator();

        mRingers.clear();

        for (Ringer r : RINGERS) {
             if (hasVibrator || !r.mVibrateWhenRinging) {
                 mRingers.add(r);
             }
        }
        if (mRingers.isEmpty()) {
            mRingers.add(RINGERS[0]);
        }
    }

    private void toggleRingerState() {
        mRingerIndex++;
        if (mRingerIndex >= mRingers.size()) {
            mRingerIndex = 0;
        }

        Ringer r = mRingers.get(mRingerIndex);

        // If we are setting a vibrating state, vibrate to indicate it
        if (r.mVibrateWhenRinging) {
            mVibrator.vibrate(250);
        }

        // Set the desired state
        ContentResolver resolver = mContext.getContentResolver();
        Settings.System.putIntForUser(resolver, Settings.System.VIBRATE_WHEN_RINGING,
                r.mVibrateWhenRinging ? 1 : 0, UserHandle.USER_CURRENT);
        mAudioManager.setRingerMode(r.mRingerMode);
    }

    private static class Ringer {
        final boolean mVibrateWhenRinging;
        final int mRingerMode;
        final int mDrawable;
        final int mString;

        Ringer(int ringerMode, boolean vibrateWhenRinging, int drawable, int string) {
            mVibrateWhenRinging = vibrateWhenRinging;
            mRingerMode = ringerMode;
            mDrawable = drawable;
            mString = string;
        }
    }
}
