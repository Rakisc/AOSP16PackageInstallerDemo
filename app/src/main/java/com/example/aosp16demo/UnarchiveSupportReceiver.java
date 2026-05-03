package com.example.aosp16demo;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UnarchiveSupportReceiver extends BroadcastReceiver {
    private static final String TAG = "UnarchiveSupportRx";
    private static final String ACTION_INSTALL_STATUS_FROM_UNARCHIVE =
            "com.example.aosp16demo.ACTION_INSTALL_STATUS_FROM_UNARCHIVE";
    private static final String EXTRA_LOCAL_UNARCHIVE_ID =
            "com.example.aosp16demo.extra.LOCAL_UNARCHIVE_ID";
    private static final String TARGET_PACKAGE_NAME = "com.example.archiveapk";
    private static final String ARCHIVE_APK_ASSET_PATH = "apk/archiveDemo.apk";
    private static final int FLOW_IN_PROGRESS = 1;
    private static final int FLOW_DONE = 2;
    private static final int FLOW_FAILED = 3;
    private static final Map<Integer, Integer> UNARCHIVE_FLOW_STATE = new ConcurrentHashMap<>();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_UNARCHIVE_PACKAGE.equals(action)) {
            handleSystemUnarchiveRequest(context, intent);
        } else if (ACTION_INSTALL_STATUS_FROM_UNARCHIVE.equals(action)) {
            handleInstallStatusCallback(context, intent);
        }
    }

    private void handleSystemUnarchiveRequest(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }

        int unarchiveId = intent.getIntExtra(PackageInstaller.EXTRA_UNARCHIVE_ID, -1);
        String packageName = intent.getStringExtra(PackageInstaller.EXTRA_UNARCHIVE_PACKAGE_NAME);
        Log.i(TAG, "Received ACTION_UNARCHIVE_PACKAGE, id=" + unarchiveId + ", package=" + packageName);
        Toast.makeText(context, "Received unarchive request: " + packageName, Toast.LENGTH_SHORT).show();
        if (unarchiveId >= 0) {
            Integer oldState = UNARCHIVE_FLOW_STATE.get(unarchiveId);
            if (oldState != null && oldState != FLOW_FAILED) {
                String stateLabel = oldState == FLOW_IN_PROGRESS ? "in_progress" : "done";
                Log.i(TAG, "Unarchive ID already handled, skip duplicate: " + unarchiveId
                        + ", state=" + stateLabel);
                Toast.makeText(context, "Duplicate unarchive ignored, state=" + stateLabel,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            UNARCHIVE_FLOW_STATE.put(unarchiveId, FLOW_IN_PROGRESS);
        }

        try {
            context.getPackageManager().getPackageInstaller().reportUnarchivalStatus(
                    unarchiveId,
                    PackageInstaller.UNARCHIVAL_OK,
                    0L,
                    (PendingIntent) null
            );
            Log.i(TAG, "reportUnarchivalStatus(UNARCHIVAL_OK) success, id=" + unarchiveId);
            Toast.makeText(context, "Reported UNARCHIVAL_OK, start reinstall", Toast.LENGTH_SHORT).show();
            installArchiveApkAsync(context.getApplicationContext(), unarchiveId);
        } catch (IllegalStateException e) {
            // Framework already has status/session for this unarchiveId; do not fail hard.
            Log.w(TAG, "Unarchive status already set for id=" + unarchiveId + ", continue flow", e);
            Toast.makeText(context,
                    "Unarchive id already handled by system, continue reinstall",
                    Toast.LENGTH_SHORT).show();
            installArchiveApkAsync(context.getApplicationContext(), unarchiveId);
        } catch (Exception e) {
            Log.e(TAG, "reportUnarchivalStatus failed, id=" + unarchiveId, e);
            Toast.makeText(context, "Failed to report unarchive status: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            if (unarchiveId >= 0) {
                UNARCHIVE_FLOW_STATE.put(unarchiveId, FLOW_FAILED);
            }
        }
    }

    private void installArchiveApkAsync(Context context, int unarchiveId) {
        final PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL
                );
                params.setInstallerPackageName(context.getPackageName());
                int sessionId = packageInstaller.createSession(params);
                Log.i(TAG, "Created unarchive install session, id=" + sessionId);

                try (PackageInstaller.Session session = packageInstaller.openSession(sessionId)) {
                    try (InputStream input = context.getAssets().open(ARCHIVE_APK_ASSET_PATH);
                         OutputStream output = session.openWrite("base.apk", 0, -1)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        long total = 0;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                            total += read;
                        }
                        session.fsync(output);
                        Log.i(TAG, "Asset APK written, bytes=" + total);
                    }

                    Intent callbackIntent = new Intent(context, UnarchiveSupportReceiver.class)
                            .setAction(ACTION_INSTALL_STATUS_FROM_UNARCHIVE)
                            .setPackage(context.getPackageName())
                            .putExtra(EXTRA_LOCAL_UNARCHIVE_ID, unarchiveId);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
                    PendingIntent callbackPendingIntent = PendingIntent.getBroadcast(
                            context, 1001, callbackIntent, flags);
                    session.commit(callbackPendingIntent.getIntentSender());
                    Log.i(TAG, "Unarchive reinstall commit sent");
                }
            } catch (Exception e) {
                Log.e(TAG, "Unarchive reinstall failed", e);
                if (unarchiveId >= 0) {
                    UNARCHIVE_FLOW_STATE.put(unarchiveId, FLOW_FAILED);
                }
            } finally {
                pendingResult.finish();
            }
        }, "unarchive-install-thread").start();
    }

    private void handleInstallStatusCallback(Context context, Intent intent) {
        int unarchiveId = intent.getIntExtra(EXTRA_LOCAL_UNARCHIVE_ID, -1);
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "Install callback from unarchive, id=" + unarchiveId
                + ", status=" + status + ", msg=" + message);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirmIntent);
                    Toast.makeText(context, "Installer confirmation launched", Toast.LENGTH_SHORT).show();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Failed to launch installer confirm UI", e);
                }
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Unarchive reinstall success: " + TARGET_PACKAGE_NAME, Toast.LENGTH_SHORT).show();
            if (unarchiveId >= 0) {
                UNARCHIVE_FLOW_STATE.put(unarchiveId, FLOW_DONE);
            }
            return;
        }

        Toast.makeText(context, "Unarchive reinstall failed, status=" + status + ", msg=" + message,
                Toast.LENGTH_SHORT).show();
        if (unarchiveId >= 0) {
            UNARCHIVE_FLOW_STATE.put(unarchiveId, FLOW_FAILED);
        }
    }
}
