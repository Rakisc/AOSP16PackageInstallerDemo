package com.example.aosp16demo;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "InstallerDemo";
    private static final String ACTION_INSTALL_STATUS =
            "com.example.aosp16demo.ACTION_INSTALL_STATUS";

    private static final String PREAPPROVAL_PACKAGE_NAME = "com.example.apktoinstall";
    private static final String PREAPPROVAL_APP_LABEL = "ApkToInstall";
    private static final String TARGET_APK_ASSET_PATH = "apk/target.apk";
    private static final String ARCHIVE_SEED_APK_ASSET_PATH = "apk/archiveDemo.apk";
    private static final String ARCHIVE_TARGET_PACKAGE = "com.example.archiveapk";
    private static final String ARCHIVE_TARGET_LABEL = "archiveApk";

    private static final int MAX_LOG_CHARS = 12000;
    private static final long PREAPPROVAL_TIMEOUT_MS = 15000L;
    private static final long INSTALL_TIMEOUT_MS = 20000L;
    private static final long ARCHIVE_TIMEOUT_MS = 15000L;
    private static final long UNARCHIVE_TIMEOUT_MS = 15000L;

    private PackageInstaller.Session installSession;
    private IntentSender statusReceiver;
    private TextView logOutputView;
    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean waitingPreapprovalResult;
    private boolean waitingInstallResult;
    private boolean waitingArchiveResult;
    private boolean waitingUnarchiveResult;
    private String waitingInstallTag = "unknown";

    private final Runnable preapprovalTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (waitingPreapprovalResult) {
                showToastAndLog("15s 内未收到 pre-approval 回调，可能被系统/安装器拦截或静默处理");
            }
        }
    };

    private final Runnable installTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (waitingInstallResult) {
                showToastAndLog("20s 内未收到 install commit 回调，可能被系统/安装器拦截或静默处理");
            }
        }
    };

    private final Runnable archiveTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (waitingArchiveResult) {
                showToastAndLog("15s 内未收到 archive 回调，可能被系统/安装器拦截或静默处理");
            }
        }
    };

    private final Runnable unarchiveTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (waitingUnarchiveResult) {
                showToastAndLog("15s 内未收到 unarchive 回调，可能被系统/安装器拦截或静默处理");
            }
        }
    };

    private final BroadcastReceiver installStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleInstallStatus(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_main);

        registerReceiver(
                installStatusReceiver,
                new IntentFilter(ACTION_INSTALL_STATUS),
                Context.RECEIVER_NOT_EXPORTED
        );
        statusReceiver = createStatusReceiver();

        Button preapprovalButton = findViewById(R.id.btn_request_preapproval);
        Button commitButton = findViewById(R.id.btn_commit_install);
        Button installArchiveSeedButton = findViewById(R.id.btn_install_archive_seed);
        Button archiveButton = findViewById(R.id.btn_request_archive);
        Button unarchiveButton = findViewById(R.id.btn_request_unarchive);
        logOutputView = findViewById(R.id.tv_log_output);

        preapprovalButton.setOnClickListener(v -> requestUserPreapproval());
        commitButton.setOnClickListener(v -> commitInstall());
        installArchiveSeedButton.setOnClickListener(v -> installArchiveSeedApk());
        archiveButton.setOnClickListener(v -> requestArchive());
        unarchiveButton.setOnClickListener(v -> requestUnarchive());

        showToastAndLog("应用已启动，等待操作");
        showToastAndLog("pre-approval 目标包: " + PREAPPROVAL_PACKAGE_NAME);
        showToastAndLog("archive 目标包: " + ARCHIVE_TARGET_PACKAGE + ", label=" + ARCHIVE_TARGET_LABEL);
        logUnarchiveReceiverSupport();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(installStatusReceiver);
        waitingPreapprovalResult = false;
        waitingInstallResult = false;
        waitingArchiveResult = false;
        waitingUnarchiveResult = false;
        mainHandler.removeCallbacks(preapprovalTimeoutRunnable);
        mainHandler.removeCallbacks(installTimeoutRunnable);
        mainHandler.removeCallbacks(archiveTimeoutRunnable);
        mainHandler.removeCallbacks(unarchiveTimeoutRunnable);
        closeSessionQuietly();
    }

    private IntentSender createStatusReceiver() {
        Intent callbackIntent = new Intent(ACTION_INSTALL_STATUS).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, callbackIntent, flags);
        return pendingIntent.getIntentSender();
    }

    private void requestUserPreapproval() {
        closeSessionQuietly();
        logEnvironmentForDiagnostics("pre-approval", PREAPPROVAL_PACKAGE_NAME);
        try {
            PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
            );
            int sessionId = packageInstaller.createSession(params);
            installSession = packageInstaller.openSession(sessionId);
            showToastAndLog("创建安装 Session 成功，sessionId=" + sessionId);

            PackageInstaller.PreapprovalDetails details =
                    new PackageInstaller.PreapprovalDetails.Builder()
                            .setPackageName(PREAPPROVAL_PACKAGE_NAME)
                            .setLabel(PREAPPROVAL_APP_LABEL)
                            .setLocale(ULocale.getDefault())
                            .build();

            showToastAndLog("开始调用 requestUserPreapproval()");
            installSession.requestUserPreapproval(details, statusReceiver);
            waitingPreapprovalResult = true;
            mainHandler.removeCallbacks(preapprovalTimeoutRunnable);
            mainHandler.postDelayed(preapprovalTimeoutRunnable, PREAPPROVAL_TIMEOUT_MS);
            showToastAndLog("已发起 pre-approval 请求");
        } catch (IOException e) {
            closeSessionQuietly();
            showToastAndLog("创建安装会话失败: " + e.getMessage());
            Log.e(TAG, "Failed to create install session", e);
        } catch (NoSuchMethodError e) {
            closeSessionQuietly();
            showToastAndLog("系统缺少 requestUserPreapproval 接口实现（疑似厂商裁剪）");
            Log.e(TAG, "requestUserPreapproval() missing at runtime", e);
        } catch (SecurityException e) {
            closeSessionQuietly();
            showToastAndLog("调用 pre-approval 被系统拒绝: " + e.getMessage());
            Log.e(TAG, "preapproval security exception", e);
        } catch (RuntimeException e) {
            closeSessionQuietly();
            showToastAndLog("pre-approval 请求失败: " + e.getMessage());
            Log.e(TAG, "Failed to request preapproval", e);
        }
    }

    private void commitInstall() {
        if (installSession == null) {
            showToastAndLog("请先点击 pre-approval 按钮");
            return;
        }

        try {
            writeAssetApkToSession(installSession, TARGET_APK_ASSET_PATH);
            showToastAndLog("开始调用 session.commit()");
            installSession.commit(statusReceiver);
            waitingInstallTag = "preapproval_install";
            waitingInstallResult = true;
            mainHandler.removeCallbacks(installTimeoutRunnable);
            mainHandler.postDelayed(installTimeoutRunnable, INSTALL_TIMEOUT_MS);
            showToastAndLog("已写入 APK 并提交安装");
        } catch (IOException e) {
            showToastAndLog("写入 APK 失败: " + e.getMessage());
            Log.e(TAG, "Failed to write APK into session", e);
            installSession.abandon();
        } catch (RuntimeException e) {
            showToastAndLog("提交安装失败: " + e.getMessage());
            Log.e(TAG, "Failed to commit install session", e);
            installSession.abandon();
        } finally {
            installSession.close();
            installSession = null;
        }
    }

    private void installArchiveSeedApk() {
        installArchiveApkFromAssets("archive_seed_install", "准备安装归档测试应用");
    }

    private void installArchiveApkFromAssets(String flowTag, String entryMessage) {
        logEnvironmentForDiagnostics(flowTag, ARCHIVE_TARGET_PACKAGE);
        showToastAndLog(entryMessage + "，label=" + ARCHIVE_TARGET_LABEL);
        try {
            PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
            );
            // 使用当前应用作为 installer，确保后续 requestArchive 可用。
            params.setInstallerPackageName(getPackageName());
            int sessionId = packageInstaller.createSession(params);
            showToastAndLog("创建归档测试安装 Session 成功，sessionId=" + sessionId);

            try (PackageInstaller.Session session = packageInstaller.openSession(sessionId)) {
                writeAssetApkToSession(session, ARCHIVE_SEED_APK_ASSET_PATH);
                showToastAndLog("开始提交归档测试应用安装");
                session.commit(statusReceiver);
            }

            waitingInstallTag = flowTag;
            waitingInstallResult = true;
            mainHandler.removeCallbacks(installTimeoutRunnable);
            mainHandler.postDelayed(installTimeoutRunnable, INSTALL_TIMEOUT_MS);
            showToastAndLog("已提交安装请求, flow=" + flowTag);
        } catch (IOException e) {
            showToastAndLog("安装失败(IO), flow=" + flowTag + ", msg=" + e.getMessage());
            Log.e(TAG, "Failed to install archive seed apk", e);
        } catch (SecurityException e) {
            showToastAndLog("安装被系统拒绝, flow=" + flowTag + ", msg=" + e.getMessage());
            Log.e(TAG, "install archive seed security exception", e);
        } catch (RuntimeException e) {
            showToastAndLog("安装失败, flow=" + flowTag + ", msg=" + e.getMessage());
            Log.e(TAG, "install archive seed runtime exception", e);
        }
    }

    private void writeAssetApkToSession(PackageInstaller.Session session, String assetPath) throws IOException {
        showToastAndLog("开始从 assets 读取 APK: " + assetPath);
        try (InputStream input = getAssets().open(assetPath);
             OutputStream output = session.openWrite("base.apk", 0, -1)) {
            byte[] buffer = new byte[8192];
            long totalBytes = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                totalBytes += read;
            }
            session.fsync(output);
            showToastAndLog("APK 写入完成，asset=" + assetPath + ", bytes=" + totalBytes);
        }
    }

    private void requestArchive() {
        logEnvironmentForDiagnostics("archive", ARCHIVE_TARGET_PACKAGE);
        diagnoseArchiveTargetPackage();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            showToastAndLog("当前系统 API < 35，不支持 requestArchive");
            return;
        }

        try {
            PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
            showToastAndLog("开始调用 requestArchive()");
            packageInstaller.requestArchive(ARCHIVE_TARGET_PACKAGE, statusReceiver);
            waitingArchiveResult = true;
            mainHandler.removeCallbacks(archiveTimeoutRunnable);
            mainHandler.postDelayed(archiveTimeoutRunnable, ARCHIVE_TIMEOUT_MS);
            showToastAndLog("已发起 archive 请求");
        } catch (NoSuchMethodError e) {
            showToastAndLog("系统缺少 requestArchive 接口实现（疑似厂商裁剪）");
            Log.e(TAG, "requestArchive() missing at runtime", e);
        } catch (PackageManager.NameNotFoundException e) {
            showToastAndLog("archive 失败: 包不存在或不可见，msg=" + e.getMessage());
            Log.e(TAG, "requestArchive NameNotFound", e);
        } catch (SecurityException e) {
            showToastAndLog("调用 requestArchive 被系统拒绝: " + e.getMessage());
            Log.e(TAG, "archive security exception", e);
        } catch (RuntimeException e) {
            showToastAndLog("archive 请求失败: " + e.getMessage());
            Log.e(TAG, "Failed to request archive", e);
        }
    }

    private void diagnoseArchiveTargetPackage() {
        PackageManager packageManager = getPackageManager();
        try {
            PackageInfo info = packageManager.getPackageInfo(ARCHIVE_TARGET_PACKAGE, 0);
            showToastAndLog("archive 目标包可见: pkg=" + info.packageName
                    + ", versionCode=" + info.getLongVersionCode()
                    + ", versionName=" + info.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            showToastAndLog("archive 目标包对当前应用不可见或未安装: " + ARCHIVE_TARGET_PACKAGE);
            showToastAndLog("提示: 如确认已安装，检查包名/用户空间/manifest queries");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                InstallSourceInfo sourceInfo = packageManager.getInstallSourceInfo(ARCHIVE_TARGET_PACKAGE);
                String installing = sourceInfo.getInstallingPackageName();
                String initiating = sourceInfo.getInitiatingPackageName();
                String updateOwner = sourceInfo.getUpdateOwnerPackageName();
                showToastAndLog("installSource: installing=" + installing
                        + ", initiating=" + initiating
                        + ", updateOwner=" + updateOwner
                        + ", self=" + getPackageName());
            } catch (PackageManager.NameNotFoundException e) {
                showToastAndLog("读取 installSource 失败: NameNotFound");
            } catch (RuntimeException e) {
                showToastAndLog("读取 installSource 失败: " + e.getMessage());
            }
        }
    }

    private void logUnarchiveReceiverSupport() {
        Intent checkIntent = new Intent(Intent.ACTION_UNARCHIVE_PACKAGE).setPackage(getPackageName());
        int receiverCount;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            receiverCount = getPackageManager()
                    .queryBroadcastReceivers(checkIntent, PackageManager.ResolveInfoFlags.of(0))
                    .size();
        } else {
            receiverCount = getPackageManager().queryBroadcastReceivers(checkIntent, 0).size();
        }
        showToastAndLog("UNARCHIVE receiver 数量(self)=" + receiverCount);
    }

    private void requestUnarchive() {
        logEnvironmentForDiagnostics("unarchive", ARCHIVE_TARGET_PACKAGE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            showToastAndLog("当前系统 API < 35，不支持 requestUnarchive");
            return;
        }

        try {
            PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
            showToastAndLog("开始调用 requestUnarchive()");
            packageInstaller.requestUnarchive(ARCHIVE_TARGET_PACKAGE, statusReceiver);
            waitingUnarchiveResult = true;
            mainHandler.removeCallbacks(unarchiveTimeoutRunnable);
            mainHandler.postDelayed(unarchiveTimeoutRunnable, UNARCHIVE_TIMEOUT_MS);
            showToastAndLog("已发起 unarchive 请求");
        } catch (PackageManager.NameNotFoundException e) {
            showToastAndLog("unarchive 失败: 包不存在或不可见，msg=" + e.getMessage());
            Log.e(TAG, "requestUnarchive NameNotFound", e);
        } catch (IOException e) {
            showToastAndLog("unarchive 失败: 参数不满足(可能存储不足)，msg=" + e.getMessage());
            Log.e(TAG, "requestUnarchive IOException", e);
        } catch (NoSuchMethodError e) {
            showToastAndLog("系统缺少 requestUnarchive 接口实现（疑似厂商裁剪）");
            Log.e(TAG, "requestUnarchive() missing at runtime", e);
        } catch (SecurityException e) {
            showToastAndLog("调用 requestUnarchive 被系统拒绝: " + e.getMessage());
            Log.e(TAG, "unarchive security exception", e);
        } catch (RuntimeException e) {
            showToastAndLog("unarchive 请求失败: " + e.getMessage());
            Log.e(TAG, "Failed to request unarchive", e);
        }
    }

    private void handleInstallStatus(Intent intent) {
        boolean hasLegacyStatus = intent.hasExtra(PackageInstaller.EXTRA_STATUS);
        int legacyStatus = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        boolean hasUnarchiveStatus = intent.hasExtra(PackageInstaller.EXTRA_UNARCHIVE_STATUS);
        int unarchiveStatus = intent.getIntExtra(
                PackageInstaller.EXTRA_UNARCHIVE_STATUS, Integer.MIN_VALUE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        boolean hasPreapprovalExtra = intent.hasExtra(PackageInstaller.EXTRA_PRE_APPROVAL);
        boolean isPreApproval = intent.getBooleanExtra(PackageInstaller.EXTRA_PRE_APPROVAL, false);

        showToastAndLog("收到回调: hasLegacyStatus=" + hasLegacyStatus
                + ", status=" + statusToString(legacyStatus)
                + ", hasUnarchiveStatus=" + hasUnarchiveStatus
                + ", unarchiveStatus=" + unarchiveStatusToString(unarchiveStatus)
                + ", hasPreapprovalExtra=" + hasPreapprovalExtra
                + ", isPreApproval=" + isPreApproval
                + ", msg=" + message);
        showToastAndLog("回调 extras: " + dumpExtras(intent));

        if (hasUnarchiveStatus) {
            waitingUnarchiveResult = false;
            mainHandler.removeCallbacks(unarchiveTimeoutRunnable);
            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            boolean needUserAction = (legacyStatus == PackageInstaller.STATUS_PENDING_USER_ACTION)
                    || (unarchiveStatus == PackageInstaller.UNARCHIVAL_ERROR_USER_ACTION_NEEDED)
                    || (confirmIntent != null);
            if (needUserAction) {
                if (confirmIntent != null) {
                    showToastAndLog("unarchive 需要用户确认，尝试拉起系统确认页");
                    try {
                        startActivity(confirmIntent);
                        showToastAndLog("unarchive 系统确认页已拉起");
                    } catch (RuntimeException e) {
                        showToastAndLog("unarchive 确认页拉起失败: " + e.getMessage());
                        Log.e(TAG, "Failed to start unarchive confirm intent", e);
                    }
                } else {
                    showToastAndLog("unarchive 提示需用户操作，但 EXTRA_INTENT 为空");
                }
                return;
            }
            if (unarchiveStatus == PackageInstaller.UNARCHIVAL_OK) {
                showToastAndLog("unarchive 请求已被 installer 接受");
                installArchiveApkFromAssets("unarchive_reinstall", "开始执行解归档后的重新安装");
            } else {
                String text = "unarchive 返回: "
                        + unarchiveStatusToString(unarchiveStatus)
                        + ", msg=" + message;
                showToastAndLog(text);
                Log.e(TAG, text);
            }
            return;
        }

        if (hasLegacyStatus && legacyStatus == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (isPreApproval) {
                waitingPreapprovalResult = false;
                mainHandler.removeCallbacks(preapprovalTimeoutRunnable);
            } else if (waitingArchiveResult) {
                waitingArchiveResult = false;
                mainHandler.removeCallbacks(archiveTimeoutRunnable);
            } else if (waitingInstallResult) {
                waitingInstallResult = false;
                mainHandler.removeCallbacks(installTimeoutRunnable);
            }

            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirmIntent != null) {
                showToastAndLog("收到待用户确认状态，尝试拉起系统确认页");
                try {
                    startActivity(confirmIntent);
                    showToastAndLog("系统确认页已拉起");
                } catch (RuntimeException e) {
                    showToastAndLog("系统确认页拉起失败: " + e.getMessage());
                    Log.e(TAG, "Failed to start confirm intent", e);
                }
            } else {
                showToastAndLog("STATUS_PENDING_USER_ACTION 但 EXTRA_INTENT 为空");
            }
            return;
        }

        if (hasLegacyStatus && legacyStatus == PackageInstaller.STATUS_SUCCESS) {
            if (isPreApproval) {
                waitingPreapprovalResult = false;
                mainHandler.removeCallbacks(preapprovalTimeoutRunnable);
                showToastAndLog("pre-approval 已通过");
            } else if (waitingInstallResult) {
                waitingInstallResult = false;
                mainHandler.removeCallbacks(installTimeoutRunnable);
                showToastAndLog("安装成功, flow=" + waitingInstallTag);
                waitingInstallTag = "unknown";
            } else if (waitingArchiveResult) {
                waitingArchiveResult = false;
                mainHandler.removeCallbacks(archiveTimeoutRunnable);
                showToastAndLog("archive 请求成功被系统接受");
            } else {
                showToastAndLog("收到 SUCCESS（未匹配到等待中的流程）");
            }
            return;
        }

        if (isPreApproval) {
            waitingPreapprovalResult = false;
            mainHandler.removeCallbacks(preapprovalTimeoutRunnable);
        } else if (waitingInstallResult) {
            waitingInstallResult = false;
            mainHandler.removeCallbacks(installTimeoutRunnable);
            waitingInstallTag = "unknown";
        } else if (waitingArchiveResult) {
            waitingArchiveResult = false;
            mainHandler.removeCallbacks(archiveTimeoutRunnable);
        }

        String errorMessage = "请求失败或状态未知, status="
                + statusToString(legacyStatus)
                + ", unarchiveStatus=" + unarchiveStatusToString(unarchiveStatus)
                + ", msg=" + message;
        showToastAndLog(errorMessage);
        Log.e(TAG, errorMessage);
    }

    private void logEnvironmentForDiagnostics(String flow, String targetPackage) {
        showToastAndLog("[" + flow + "] 设备信息: sdk=" + Build.VERSION.SDK_INT
                + ", release=" + Build.VERSION.RELEASE);
        showToastAndLog("[" + flow + "] 厂商信息: brand=" + Build.BRAND
                + ", manufacturer=" + Build.MANUFACTURER);
        showToastAndLog("[" + flow + "] 安装权限: canRequestPackageInstalls="
                + getPackageManager().canRequestPackageInstalls());
        showToastAndLog("[" + flow + "] 目标包名: " + targetPackage);
    }

    private String statusToString(int status) {
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) return "PENDING_USER_ACTION";
        if (status == PackageInstaller.STATUS_SUCCESS) return "SUCCESS";
        if (status == PackageInstaller.STATUS_FAILURE) return "FAILURE";
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return "FAILURE_ABORTED";
        if (status == PackageInstaller.STATUS_FAILURE_BLOCKED) return "FAILURE_BLOCKED";
        if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) return "FAILURE_CONFLICT";
        if (status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) return "FAILURE_INCOMPATIBLE";
        if (status == PackageInstaller.STATUS_FAILURE_INVALID) return "FAILURE_INVALID";
        if (status == PackageInstaller.STATUS_FAILURE_STORAGE) return "FAILURE_STORAGE";
        return "UNKNOWN(" + status + ")";
    }

    private String unarchiveStatusToString(int status) {
        if (status == PackageInstaller.UNARCHIVAL_OK) return "UNARCHIVAL_OK";
        if (status == PackageInstaller.UNARCHIVAL_ERROR_USER_ACTION_NEEDED) {
            return "UNARCHIVAL_ERROR_USER_ACTION_NEEDED";
        }
        if (status == PackageInstaller.UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE) {
            return "UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE";
        }
        if (status == PackageInstaller.UNARCHIVAL_ERROR_NO_CONNECTIVITY) {
            return "UNARCHIVAL_ERROR_NO_CONNECTIVITY";
        }
        if (status == PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_DISABLED) {
            return "UNARCHIVAL_ERROR_INSTALLER_DISABLED";
        }
        if (status == PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED) {
            return "UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED";
        }
        if (status == PackageInstaller.UNARCHIVAL_GENERIC_ERROR) {
            return "UNARCHIVAL_GENERIC_ERROR";
        }
        return "UNARCHIVAL_UNKNOWN(" + status + ")";
    }

    private String dumpExtras(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null || extras.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            sb.append(key).append("=").append(value).append(", ");
        }
        if (sb.length() > 1) {
            sb.setLength(sb.length() - 2);
        }
        sb.append("}");
        return sb.toString();
    }

    private void showToastAndLog(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        appendLog(message);
    }

    private void appendLog(String message) {
        if (message == null) {
            message = "(null)";
        }
        String entry = timeFormat.format(new Date()) + " | " + message;
        if (logBuffer.length() > 0) {
            entry = entry + "\n";
        }
        logBuffer.insert(0, entry);
        if (logBuffer.length() > MAX_LOG_CHARS) {
            logBuffer.setLength(MAX_LOG_CHARS);
        }
        if (logOutputView != null) {
            logOutputView.setText(logBuffer.toString());
        }
    }

    private void closeSessionQuietly() {
        waitingPreapprovalResult = false;
        waitingInstallResult = false;
        mainHandler.removeCallbacks(preapprovalTimeoutRunnable);
        mainHandler.removeCallbacks(installTimeoutRunnable);
        if (installSession != null) {
            installSession.close();
            installSession = null;
        }
    }
}
