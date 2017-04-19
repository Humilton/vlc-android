package org.videolan.vlc;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import org.videolan.medialibrary.Medialibrary;
import org.videolan.medialibrary.interfaces.DevicesDiscoveryCb;
import org.videolan.vlc.gui.DialogActivity;
import org.videolan.vlc.util.AndroidDevices;
import org.videolan.vlc.util.FileUtils;
import org.videolan.vlc.util.Strings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MediaParsingService extends Service implements DevicesDiscoveryCb {
    public final static String TAG = "VLC/MediaParsingService";


    public final static String ACTION_INIT = "medialibrary_init";
    public final static String ACTION_RELOAD = "medialibrary_reload";
    public final static String ACTION_DISCOVER = "medialibrary_discover";
    public final static String ACTION_DISCOVER_DEVICE = "medialibrary_discover_device";

    public final static String EXTRA_PATH = "extra_path";
    public final static String EXTRA_UUID = "extra_uuid";

    public final static String ACTION_RESUME_SCAN = "action_resume_scan";
    public final static String ACTION_PAUSE_SCAN = "action_pause_scan";
    public final static String ACTION_SERVICE_STARTED = "action_service_started";
    public final static String ACTION_SERVICE_ENDED = "action_service_ended";
    public static final long NOTIFICATION_DELAY = 1000L;
    private PowerManager.WakeLock mWakeLock;

    private final IBinder mBinder = new LocalBinder();
    private Medialibrary mMedialibrary;
    private int mParsing = 0, mReload = 0;
    private String mCurrentDiscovery = null;
    private long mLastNotificationTime = 0L;

    private final ThreadPoolExecutor mThreadPool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), VLCApplication.THREAD_FACTORY);

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case ACTION_PAUSE_SCAN:
                    if (mWakeLock.isHeld())
                        mWakeLock.release();
                    mMedialibrary.pauseBackgroundOperations();
                    break;
                case ACTION_RESUME_SCAN:
                    if (!mWakeLock.isHeld())
                        mWakeLock.acquire();
                    mMedialibrary.resumeBackgroundOperations();
                    break;
                default:
                    return;
            }
            synchronized (this) {
                mLastNotificationTime = 0L;
            }
            showNotification();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mMedialibrary = VLCApplication.getMLInstance();
        mMedialibrary.addDeviceDiscoveryCb(MediaParsingService.this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PAUSE_SCAN);
        filter.addAction(ACTION_RESUME_SCAN);
        registerReceiver(mReceiver, filter);
        PowerManager pm = (PowerManager) VLCApplication.getAppContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.acquire();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (this) {
            if (mLastNotificationTime <= 0L)
                mLastNotificationTime = System.currentTimeMillis();
        }
        switch (intent.getAction()) {
            case ACTION_INIT:
                setupMedialibrary(intent.getBooleanExtra(StartActivity.EXTRA_UPGRADE, false));
                break;
            case ACTION_RELOAD:
                reload(intent.getStringExtra(EXTRA_PATH));
                break;
            case ACTION_DISCOVER:
                discover(intent.getStringExtra(EXTRA_PATH));
                break;
            case ACTION_DISCOVER_DEVICE:
                discoverStorage(intent.getStringExtra(EXTRA_PATH));
                break;
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_SERVICE_STARTED));
        return START_NOT_STICKY;
    }

    private void discoverStorage(final String path) {
        if (BuildConfig.DEBUG) Log.d(TAG, "discoverStorage: "+path);
        mThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                for (String folder : Medialibrary.getBlackList())
                    mMedialibrary.banFolder(path + folder);
                mMedialibrary.discover(path);
            }
        });
    }

    private void discover(final String path) {
        if (TextUtils.isEmpty(path))
            return;
        mThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                mMedialibrary.discover(path);
            }
        });
    }

    private void addDeviceIfNeeded(String path) {
        for (String devicePath : mMedialibrary.getDevices()) {
            if (path.startsWith(devicePath))
                return;
        }
        for (String storagePath : AndroidDevices.getExternalStorageDirectories()) {
            if (path.startsWith(storagePath)) {
                String uuid = FileUtils.getFileNameFromPath(path);
                mMedialibrary.addDevice(uuid, path, true);
            }
        }
    }

    private void reload(String path) {
        if (mReload > 0)
            return;
        if (TextUtils.isEmpty(path))
            mMedialibrary.reload();
        else
            mMedialibrary.reload(path);
    }

    private void setupMedialibrary(final boolean upgrade) {
        if (mMedialibrary.isInitiated())
            mMedialibrary.resumeBackgroundOperations();
        else
            mThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    mMedialibrary.setup();
                    if (mMedialibrary.init(MediaParsingService.this)) {
                        boolean shouldInit = !(new File(MediaParsingService.this.getCacheDir()+Medialibrary.VLC_MEDIA_DB_NAME).exists());
                        List<String> devices = new ArrayList<>();
                        devices.add(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY);
                        devices.addAll(AndroidDevices.getExternalStorageDirectories());
                        for (String device : devices) {
                            boolean isMainStorage = TextUtils.equals(device, AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY);
                            boolean isNew = mMedialibrary.addDevice(isMainStorage ? "main-storage" : FileUtils.getFileNameFromPath(device), device, !isMainStorage);
                            if (isMainStorage) {
                                if (shouldInit) {
                                    for (String folder : Medialibrary.getBlackList())
                                        mMedialibrary.banFolder(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + folder);
                                }
                            } else if (isNew) {
                                    startActivity(new Intent(MediaParsingService.this, DialogActivity.class)
                                            .setAction(DialogActivity.KEY_STORAGE)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            .putExtra(EXTRA_PATH, device));
                            }

                        }
                        mMedialibrary.start();
                        for (String storage : AndroidDevices.getMediaDirectories())
                            mMedialibrary.discover(storage);
                        LocalBroadcastManager.getInstance(MediaParsingService.this).sendBroadcast(new Intent(VLCApplication.ACTION_MEDIALIBRARY_READY));
                        if (upgrade) {
                            mMedialibrary.forceParserRetry();
                        } else if (!shouldInit)
                            reload(null);
                    }
                }
            });
    }

    private NotificationCompat.Builder builder;
    private boolean wasWorking;
    final StringBuilder sb = new StringBuilder();
    private void showNotification() {
        final long currentTime = System.currentTimeMillis();
        synchronized (this) {
            if (mLastNotificationTime == -1L || currentTime-mLastNotificationTime < NOTIFICATION_DELAY)
                return;
            mLastNotificationTime = currentTime;
        }
        VLCApplication.runBackground(new Runnable() {
            @Override
            public void run() {
                sb.setLength(0);
                if (mParsing > 0)
                    sb.append(getString(R.string.ml_parse_media)).append(' ').append(mParsing).append("%");
                else if (mCurrentDiscovery != null)
                    sb.append(getString(R.string.ml_discovering)).append(' ').append(Uri.decode(Strings.removeFileProtocole(mCurrentDiscovery)));
                else
                    sb.append(getString(R.string.ml_parse_media));
                if (builder == null) {
                    builder = new NotificationCompat.Builder(MediaParsingService.this)
                            .setContentIntent(PendingIntent.getActivity(MediaParsingService.this, 0, new Intent(MediaParsingService.this, StartActivity.class), PendingIntent.FLAG_UPDATE_CURRENT))
                            .setSmallIcon(R.drawable.ic_notif_scan)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                            .setContentTitle(getString(R.string.ml_scanning))
                            .setAutoCancel(false)
                            .setOngoing(true);
                }
                builder.setContentText(sb.toString());

                boolean isWorking = mMedialibrary.isWorking();
                if (wasWorking != isWorking) {
                    wasWorking = isWorking;
                    PendingIntent pi = PendingIntent.getBroadcast(MediaParsingService.this, 0, new Intent(isWorking ? ACTION_PAUSE_SCAN : ACTION_RESUME_SCAN), PendingIntent.FLAG_UPDATE_CURRENT);
                    NotificationCompat.Action playpause = isWorking ? new NotificationCompat.Action(R.drawable.ic_pause, getString(R.string.pause), pi)
                            : new NotificationCompat.Action(R.drawable.ic_play, getString(R.string.resume), pi);
                    builder.mActions.clear();
                    builder.addAction(playpause);
                }
                final Notification notification = builder.build();
                synchronized (MediaParsingService.this) {
                    if (mLastNotificationTime != -1L) {
                        try {
                            NotificationManagerCompat.from(MediaParsingService.this).notify(43, notification);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            }
        });
    }

    private void hideNotification() {
        synchronized (this) {
            mLastNotificationTime = -1L;
            NotificationManagerCompat.from(MediaParsingService.this).cancel(43);
        }
    }

    @Override
    public void onDiscoveryStarted(String entryPoint) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onDiscoveryStarted: "+entryPoint);
    }

    @Override
    public void onDiscoveryProgress(String entryPoint) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onDiscoveryProgress: "+entryPoint);
        mCurrentDiscovery = entryPoint;
        showNotification();
    }

    @Override
    public void onDiscoveryCompleted(String entryPoint) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onDiscoveryCompleted: "+entryPoint);
        if (!mMedialibrary.isWorking())
            stopSelf();
    }

    @Override
    public void onParsingStatsUpdated(int percent) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onParsingStatsUpdated: "+percent);
        mParsing = percent;
        if (mParsing == 100)
            stopSelf();
        else
            showNotification();
    }

    @Override
    public void onReloadStarted(String entryPoint) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onReloadStarted: "+entryPoint);
        if (TextUtils.isEmpty(entryPoint))
            ++mReload;
    }

    @Override
    public void onReloadCompleted(String entryPoint) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onReloadCompleted "+entryPoint);
        if (TextUtils.isEmpty(entryPoint))
            --mReload;
        if (!mMedialibrary.isWorking())
            stopSelf();
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_SERVICE_ENDED));
        hideNotification();
        mMedialibrary.removeDeviceDiscoveryCb(this);
        unregisterReceiver(mReceiver);
        if (mWakeLock.isHeld())
            mWakeLock.release();
        super.onDestroy();
    }

    private class LocalBinder extends Binder {
        MediaParsingService getService() {
            return MediaParsingService.this;
        }
    }
}
