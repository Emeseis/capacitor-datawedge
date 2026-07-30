package com.haeb.datawedge;

import com.getcapacitor.Plugin;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import androidx.core.content.ContextCompat;

import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import android.util.Log;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(name = "DataWedge")
public class DataWedgePlugin extends Plugin {

    private static final String TAG = "Capacitor/DataWedge";
    private static final String DATAWEDGE_API_ACTION = "com.symbol.datawedge.api.ACTION";
    private static final String DATAWEDGE_NOTIFICATION_ACTION = "com.symbol.datawedge.api.NOTIFICATION_ACTION";
    private static final String DATAWEDGE_RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION";
    private static final String EXTRA_COMMAND_IDENTIFIER = "COMMAND_IDENTIFIER";
    private static final String EXTRA_NOTIFICATION = "com.symbol.datawedge.api.NOTIFICATION";
    private static final String EXTRA_RESULT_GET_ACTIVE_PROFILE = "com.symbol.datawedge.api.RESULT_GET_ACTIVE_PROFILE";
    private static final String EXTRA_SEND_RESULT = "SEND_RESULT";
    private static final String EXTRA_RESULT_LIST = "RESULT_LIST";
    private static final String NOTIFICATION_TYPE_SCANNER_STATUS = "SCANNER_STATUS";
    private static final String SEND_RESULT_LAST = "LAST_RESULT";
    private static final String SEND_RESULT_COMPLETE = "COMPLETE_RESULT";
    private static final long COMMAND_TIMEOUT_MS = 10_000;
    private static final long ACTIVE_PROFILE_RETRY_DELAY_MS = 100;
    private static final long SCANNER_RECONCILE_DELAY_MS = 50;

    private final DataWedge implementation = new DataWedge();
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();
    private final Object activeProfileLock = new Object();
    private PendingActiveProfile pendingActiveProfile;

    private String scanIntent = "com.capacitor.datawedge.RESULT_ACTION";
    private String managedProfileName;
    private String scannerStatus;
    private boolean scannerDesiredSuspended;
    private boolean scannerDesiredStateSet;
    private boolean scannerCommandInFlight;
    private boolean scannerSuspendedClaimed;
    private boolean scannerResetRequired;
    private PluginCall pendingScannerStateCall;
    private boolean pendingScannerState;
    private Runnable scannerReconcileRunnable;

    @Override
    public void load() {
        super.load();

        try {
            Context context = getBridge().getContext();
            registerBroadcastReceiver(context);
            setScannerStatusNotifications(context, true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register event receiver during plugin initialization", e);
        }
    }

    @Override
    protected void handleOnDestroy() {
        Context context = getBridge().getContext();

        try {
            setScannerStatusNotifications(context, false);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister scanner status notifications", e);
        }

        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(broadcastReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Event receiver was already unregistered", e);
            } finally {
                isReceiverRegistered = false;
            }
        }

        for (PendingCommand pending : pendingCommands.values()) {
            timeoutHandler.removeCallbacks(pending.timeout);
        }
        pendingCommands.clear();

        synchronized (activeProfileLock) {
            if (pendingActiveProfile != null) {
                timeoutHandler.removeCallbacks(pendingActiveProfile.timeout);
                pendingActiveProfile = null;
            }
        }

        if (scannerReconcileRunnable != null) {
            timeoutHandler.removeCallbacks(scannerReconcileRunnable);
            scannerReconcileRunnable = null;
        }
        pendingScannerStateCall = null;

        super.handleOnDestroy();
    }

    @PluginMethod
    public void registerProfile(PluginCall call) {
        String profileName = call.getString("name", "CapacitorDataWedgeProfile");
        Context context = getBridge().getContext();
        String packageName = context.getPackageName();
        String intentAction = call.getString("intentAction");

        if (profileName == null || profileName.trim().isEmpty()) {
            call.reject("DataWedge profile name must not be empty", "PROFILE_NAME_EMPTY");
            return;
        }
        profileName = profileName.trim();
        managedProfileName = profileName;

        if (intentAction != null && intentAction.trim().isEmpty()) {
            call.reject("DataWedge scan intent action must not be empty", "PARAMETER_INVALID");
            return;
        }

        try {
            setScanIntent(context, intentAction);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update scan intent receiver", e);
            call.reject("Failed to update DataWedge scan intent receiver", e);
            return;
        }

        Intent configIntent = new Intent();
        configIntent.setAction("com.symbol.datawedge.api.ACTION");

        Bundle profileConfig = new Bundle();
        profileConfig.putString("PROFILE_NAME", profileName);
        profileConfig.putString("PROFILE_ENABLED", "true");
        profileConfig.putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST");

        Bundle appConfig = new Bundle();
        appConfig.putString("PACKAGE_NAME", packageName);
        appConfig.putStringArray("ACTIVITY_LIST", new String[]{"*"});
        profileConfig.putParcelableArray("APP_LIST", new Bundle[]{appConfig});

        Bundle intentConfig = new Bundle();
        intentConfig.putString("PLUGIN_NAME", "INTENT");
        intentConfig.putString("RESET_CONFIG", "true");
        
        Bundle intentProps = new Bundle();
        intentProps.putString("intent_output_enabled", "true");
        intentProps.putString("intent_action", scanIntent);
        intentProps.putString("intent_category", Intent.CATEGORY_DEFAULT);
        intentProps.putInt("intent_delivery", 2);

        Bundle intentComponent = new Bundle();
        intentComponent.putString("PACKAGE_NAME", packageName);
        ArrayList<Bundle> intentComponents = new ArrayList<>();
        intentComponents.add(intentComponent);
        intentProps.putParcelableArrayList("intent_component_info", intentComponents);
        intentConfig.putBundle("PARAM_LIST", intentProps);

        Bundle barcodeConfig = new Bundle();
        barcodeConfig.putString("PLUGIN_NAME", "BARCODE");
        barcodeConfig.putString("RESET_CONFIG", "true");
        
        Bundle barcodeProps = new Bundle();
        barcodeProps.putString("scanner_selection", "auto");
        barcodeProps.putString("scanner_input_enabled", "true");
        barcodeConfig.putBundle("PARAM_LIST", barcodeProps);

        Bundle keystrokeConfig = new Bundle();
        keystrokeConfig.putString("PLUGIN_NAME", "KEYSTROKE");
        keystrokeConfig.putString("RESET_CONFIG", "true");
        
        Bundle keystrokeProps = new Bundle();
        keystrokeProps.putString("keystroke_output_enabled", "false");
        keystrokeConfig.putBundle("PARAM_LIST", keystrokeProps);

        ArrayList<Bundle> pluginConfigs = new ArrayList<>();
        pluginConfigs.add(intentConfig);
        pluginConfigs.add(barcodeConfig);
        pluginConfigs.add(keystrokeConfig);

        profileConfig.putParcelableArrayList("PLUGIN_CONFIG", pluginConfigs);

        configIntent.putExtra("com.symbol.datawedge.api.SET_CONFIG", profileConfig);
        sendCompleteCommand(call, configIntent, "SET_CONFIG");
    }

    @PluginMethod
    public void enable(PluginCall call) {
        sendCommand(call, implementation.enable(), "ENABLE_DATAWEDGE");
    }

    @PluginMethod
    public void deleteProfile(PluginCall call) {
        String profileName = call.getString("name");
        if (profileName == null || profileName.trim().isEmpty()) {
            call.reject("DataWedge profile name must not be empty", "PROFILE_NAME_EMPTY");
            return;
        }

        sendCommand(call, implementation.deleteProfile(profileName.trim()), "DELETE_PROFILE");
    }

    @PluginMethod
    public void getActiveProfile(PluginCall call) {
        if (!isReceiverRegistered) {
            try {
                registerBroadcastReceiver(getBridge().getContext());
            } catch (Exception e) {
                call.reject(
                    "Failed to register DataWedge result receiver",
                    "RESULT_RECEIVER_REGISTRATION_FAILED",
                    e
                );
                return;
            }
        }

        synchronized (activeProfileLock) {
            if (pendingActiveProfile != null) {
                call.reject("A DataWedge active profile query is already pending", "QUERY_ALREADY_PENDING");
                return;
            }

            Runnable timeout = () -> {
                PluginCall timedOutCall;
                synchronized (activeProfileLock) {
                    if (pendingActiveProfile == null || pendingActiveProfile.call != call) return;
                    timedOutCall = pendingActiveProfile.call;
                    pendingActiveProfile = null;
                }

                timedOutCall.reject(
                    "DataWedge did not return the active profile within " + COMMAND_TIMEOUT_MS + " ms",
                    "DATAWEDGE_TIMEOUT"
                );
            };
            pendingActiveProfile = new PendingActiveProfile(call, timeout);
            timeoutHandler.postDelayed(timeout, COMMAND_TIMEOUT_MS);
        }

        try {
            broadcast(implementation.getActiveProfile());
        } catch (Exception e) {
            synchronized (activeProfileLock) {
                if (pendingActiveProfile != null && pendingActiveProfile.call == call) {
                    timeoutHandler.removeCallbacks(pendingActiveProfile.timeout);
                    pendingActiveProfile = null;
                }
            }
            call.reject("Failed to query the active DataWedge profile", "BROADCAST_FAILED", e);
        }
    }

    @PluginMethod
    public void disable(PluginCall call) {
        sendCommand(call, implementation.disable(), "ENABLE_DATAWEDGE");
    }

    @PluginMethod
    public void enableScanner(PluginCall call) {
        sendCommand(call, implementation.enableScanner(), "SCANNER_INPUT_PLUGIN");
    }

    @PluginMethod
    public void disableScanner(PluginCall call) {
        sendCommand(call, implementation.disableScanner(), "SCANNER_INPUT_PLUGIN");
    }

    @PluginMethod
    public void suspendScanner(PluginCall call) {
        setScannerDesiredState(call, true);
    }

    @PluginMethod
    public void resumeScanner(PluginCall call) {
        setScannerDesiredState(call, false);
    }

    @PluginMethod
    public void setScannerSuspended(PluginCall call) {
        Boolean suspended = call.getBoolean("suspended");
        if (suspended == null) {
            call.reject("The suspended option is required", "PARAMETER_MISSING");
            return;
        }

        setScannerDesiredState(call, suspended);
    }

    @PluginMethod
    public void startScanning(PluginCall call) {
        sendCommand(call, implementation.startScanning(), "SOFT_SCAN_TRIGGER");
    }

    @PluginMethod
    public void stopScanning(PluginCall call) {
        sendCommand(call, implementation.stopScanning(), "SOFT_SCAN_TRIGGER");
    }

    @PluginMethod
    public void __registerReceiver(PluginCall call) { 
        Context context = getBridge().getContext();

        try {
            setScanIntent(context, call.getString("intent"));
            call.resolve();
        } catch(Exception e) {
            Log.e(TAG, "Failed to register event receiver", e);
            call.reject("Failed to register DataWedge event receiver", e);
        }
    }

    private void setScanIntent(Context context, String intentAction) {
        if (intentAction == null || intentAction.trim().isEmpty()) {
            return;
        }

        String normalizedIntentAction = intentAction.trim();
        if (normalizedIntentAction.equals(scanIntent)) return;

        if (isReceiverRegistered) {
            context.unregisterReceiver(broadcastReceiver);
            isReceiverRegistered = false;
        }

        scanIntent = normalizedIntentAction;
        registerBroadcastReceiver(context);
    }

    private void registerBroadcastReceiver(Context context) {
        if (isReceiverRegistered) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(this.scanIntent);
        filter.addAction(DATAWEDGE_NOTIFICATION_ACTION);
        filter.addAction(DATAWEDGE_RESULT_ACTION);
        filter.addCategory(Intent.CATEGORY_DEFAULT);

        ContextCompat.registerReceiver(context, broadcastReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        isReceiverRegistered = true;
    }

    private void setScannerStatusNotifications(Context context, boolean register) {
        Bundle notificationConfig = new Bundle();
        notificationConfig.putString(
            "com.symbol.datawedge.api.APPLICATION_NAME",
            context.getPackageName()
        );
        notificationConfig.putString(
            "com.symbol.datawedge.api.NOTIFICATION_TYPE",
            NOTIFICATION_TYPE_SCANNER_STATUS
        );

        Intent intent = new Intent();
        intent.setAction(DATAWEDGE_API_ACTION);
        intent.putExtra(
            register
                ? "com.symbol.datawedge.api.REGISTER_FOR_NOTIFICATION"
                : "com.symbol.datawedge.api.UNREGISTER_FOR_NOTIFICATION",
            notificationConfig
        );
        broadcast(intent);
    }

    private void handleNotification(Intent intent) {
        Bundle notification = intent.getBundleExtra(EXTRA_NOTIFICATION);
        if (notification == null) return;

        String notificationType = notification.getString("NOTIFICATION_TYPE");
        if (!NOTIFICATION_TYPE_SCANNER_STATUS.equals(notificationType)) return;

        String status = notification.getString("STATUS");
        if (status == null) {
            Log.w(TAG, "Ignoring scanner status notification without status");
            return;
        }

        String profileName = notification.getString("PROFILE_NAME");
        JSObject ret = new JSObject();
        ret.put("status", status);
        ret.put("profileName", profileName);
        notifyListeners("scannerStatus", ret, true);

        if (managedProfileName == null || !managedProfileName.equals(profileName)) return;

        String previousStatus = scannerStatus;
        scannerStatus = status;

        if (scannerDesiredStateSet && scannerDesiredSuspended && canSuspendScanner(status)) {
            scannerResetRequired = scannerResetRequired
                || scannerSuspendedClaimed
                || "IDLE".equals(previousStatus);
            reconcileScannerState();
        } else if (scannerDesiredStateSet && !scannerDesiredSuspended && canSuspendScanner(status)) {
            scannerSuspendedClaimed = false;
            resolvePendingScannerState(false);
        } else if (scannerDesiredStateSet && scannerDesiredSuspended && "IDLE".equals(status)) {
            resolvePendingScannerState(true);
        }
    }

    private void setScannerDesiredState(PluginCall call, boolean suspended) {
        if (pendingScannerStateCall != null) {
            pendingScannerStateCall.resolve();
        }

        pendingScannerStateCall = call;
        pendingScannerState = suspended;
        scannerDesiredSuspended = suspended;
        scannerDesiredStateSet = true;

        if (!suspended) {
            scannerResetRequired = false;
            if (scannerReconcileRunnable != null) {
                timeoutHandler.removeCallbacks(scannerReconcileRunnable);
                scannerReconcileRunnable = null;
            }
        }

        reconcileScannerState();
    }

    private void reconcileScannerState() {
        if (!scannerDesiredStateSet || scannerCommandInFlight) return;

        if (scannerDesiredSuspended) {
            if (!canSuspendScanner(scannerStatus)) {
                resolvePendingScannerState(true);
                return;
            }

            if (scannerResetRequired) {
                scannerResetRequired = false;
                sendScannerResume(true);
            } else {
                sendScannerSuspend();
            }
            return;
        }

        if (pendingScannerStateCall != null && !pendingScannerState) {
            sendScannerResume(false);
        }
    }

    private void sendScannerSuspend() {
        scannerCommandInFlight = true;
        sendCommand(
            implementation.suspendScanner(),
            "SCANNER_INPUT_PLUGIN",
            SEND_RESULT_LAST,
            result -> {
                scannerCommandInFlight = false;
                if (isAcceptedScannerResult(result, "SCANNER_ALREADY_SUSPENDED")) {
                    scannerSuspendedClaimed = true;
                    if ("SCANNER_ALREADY_SUSPENDED".equals(result.resultCode)
                        && canSuspendScanner(scannerStatus)) {
                        scannerResetRequired = true;
                    } else {
                        resolvePendingScannerState(true);
                    }
                    if (!scannerDesiredSuspended || scannerResetRequired) {
                        reconcileScannerState();
                    }
                } else {
                    rejectPendingScannerState(true, result);
                    Log.e(TAG, "Failed to suspend scanner: " + result.resultCode);
                }
            }
        );
    }

    private void sendScannerResume(boolean resumeBeforeSuspend) {
        scannerCommandInFlight = true;
        sendCommand(
            implementation.resumeScanner(),
            "SCANNER_INPUT_PLUGIN",
            SEND_RESULT_LAST,
            result -> {
                scannerCommandInFlight = false;
                if (isAcceptedScannerResult(result, "SCANNER_ALREADY_RESUMED")) {
                    scannerSuspendedClaimed = false;
                    if (resumeBeforeSuspend && scannerDesiredSuspended) {
                        scheduleScannerReconcile();
                    } else {
                        resolvePendingScannerState(false);
                        if (scannerDesiredSuspended) {
                            reconcileScannerState();
                        }
                    }
                } else {
                    rejectPendingScannerState(scannerDesiredSuspended, result);
                    Log.e(TAG, "Failed to resume scanner: " + result.resultCode);
                }
            }
        );
    }

    private void scheduleScannerReconcile() {
        if (scannerReconcileRunnable != null) {
            timeoutHandler.removeCallbacks(scannerReconcileRunnable);
        }

        scannerReconcileRunnable = () -> {
            scannerReconcileRunnable = null;
            reconcileScannerState();
        };
        timeoutHandler.postDelayed(scannerReconcileRunnable, SCANNER_RECONCILE_DELAY_MS);
    }

    private boolean canSuspendScanner(String status) {
        return "WAITING".equals(status) || "SCANNING".equals(status);
    }

    private boolean isAcceptedScannerResult(CommandResult result, String acceptedResultCode) {
        return result.success || acceptedResultCode.equals(result.resultCode);
    }

    private void resolvePendingScannerState(boolean suspended) {
        if (pendingScannerStateCall == null || pendingScannerState != suspended) return;

        PluginCall call = pendingScannerStateCall;
        pendingScannerStateCall = null;
        call.resolve();
    }

    private void rejectPendingScannerState(boolean suspended, CommandResult result) {
        if (pendingScannerStateCall == null || pendingScannerState != suspended) return;

        PluginCall call = pendingScannerStateCall;
        pendingScannerStateCall = null;
        rejectCall(call, result);
    }

    private void broadcast(Intent intent) {
        Context context = getBridge().getContext();
        context.sendBroadcast(intent);
    }

    private void sendCommand(PluginCall call, Intent intent, String commandName) {
        sendCommand(intent, commandName, SEND_RESULT_LAST, result -> {
            if (result.success) {
                call.resolve();
            } else {
                rejectCall(call, result);
            }
        });
    }

    private void sendCompleteCommand(PluginCall call, Intent intent, String commandName) {
        sendCommand(intent, commandName, SEND_RESULT_COMPLETE, result -> {
            if (result.success) {
                call.resolve();
            } else {
                rejectCall(call, result);
            }
        });
    }

    private void sendCommand(
        Intent intent,
        String commandName,
        String sendResultMode,
        CommandResultCallback callback
    ) {
        if (!isReceiverRegistered) {
            try {
                registerBroadcastReceiver(getBridge().getContext());
            } catch (Exception e) {
                JSObject details = new JSObject();
                details.put("command", commandName);
                details.put("resultCode", "RESULT_RECEIVER_REGISTRATION_FAILED");
                callback.onResult(
                    new CommandResult(false, commandName, "RESULT_RECEIVER_REGISTRATION_FAILED", details)
                );
                return;
            }
        }

        String commandIdentifier = UUID.randomUUID().toString();
        Runnable timeout = () -> {
            PendingCommand pending = pendingCommands.remove(commandIdentifier);
            if (pending == null) return;

            JSObject details = new JSObject();
            details.put("command", pending.commandName);
            details.put("commandIdentifier", commandIdentifier);
            details.put("resultCode", "DATAWEDGE_TIMEOUT");
            details.put("timeoutMs", COMMAND_TIMEOUT_MS);
            pending.callback.onResult(
                new CommandResult(false, pending.commandName, "DATAWEDGE_TIMEOUT", details)
            );
        };

        PendingCommand pending = new PendingCommand(commandName, callback, timeout);
        pendingCommands.put(commandIdentifier, pending);

        intent.putExtra(EXTRA_SEND_RESULT, sendResultMode);
        intent.putExtra(EXTRA_COMMAND_IDENTIFIER, commandIdentifier);
        timeoutHandler.postDelayed(timeout, COMMAND_TIMEOUT_MS);

        try {
            broadcast(intent);
        } catch (Exception e) {
            pendingCommands.remove(commandIdentifier);
            timeoutHandler.removeCallbacks(timeout);

            JSObject details = new JSObject();
            details.put("command", commandName);
            details.put("commandIdentifier", commandIdentifier);
            details.put("resultCode", "BROADCAST_FAILED");
            callback.onResult(new CommandResult(false, commandName, "BROADCAST_FAILED", details));
        }
    }

    private void handleCommandResult(Intent intent) {
        String commandIdentifier = intent.getStringExtra(EXTRA_COMMAND_IDENTIFIER);
        if (commandIdentifier == null) return;

        PendingCommand pending = pendingCommands.remove(commandIdentifier);
        if (pending == null) return;

        timeoutHandler.removeCallbacks(pending.timeout);

        if (intent.hasExtra(EXTRA_RESULT_LIST)) {
            pending.callback.onResult(parseCompleteCommandResult(intent, pending.commandName, commandIdentifier));
            return;
        }

        String result = intent.getStringExtra("RESULT");
        String resultCode = getResultCode(intent.getBundleExtra("RESULT_INFO"));
        boolean success = "SUCCESS".equalsIgnoreCase(result);

        if (!success && resultCode == null) {
            resultCode = "DATAWEDGE_COMMAND_FAILED";
        }

        JSObject details = new JSObject();
        details.put("command", pending.commandName);
        details.put("commandIdentifier", commandIdentifier);
        details.put("result", result);
        details.put("resultCode", resultCode);

        pending.callback.onResult(new CommandResult(success, pending.commandName, resultCode, details));
    }

    private void handleActiveProfileResult(Intent intent) {
        String profileName = intent.getStringExtra(EXTRA_RESULT_GET_ACTIVE_PROFILE);
        PendingActiveProfile pending;

        synchronized (activeProfileLock) {
            pending = pendingActiveProfile;
            if (pending == null) return;
        }

        if (profileName == null || profileName.trim().isEmpty()) {
            Log.d(TAG, "DataWedge active profile is temporarily empty; retrying");
            timeoutHandler.postDelayed(() -> retryActiveProfileQuery(pending), ACTIVE_PROFILE_RETRY_DELAY_MS);
            return;
        }

        synchronized (activeProfileLock) {
            if (pendingActiveProfile != pending) return;
            pendingActiveProfile = null;
        }

        timeoutHandler.removeCallbacks(pending.timeout);
        Log.d(TAG, "Active DataWedge profile: " + profileName);
        JSObject result = new JSObject();
        result.put("name", profileName);
        pending.call.resolve(result);
    }

    private void retryActiveProfileQuery(PendingActiveProfile pending) {
        synchronized (activeProfileLock) {
            if (pendingActiveProfile != pending) return;
        }

        try {
            broadcast(implementation.getActiveProfile());
        } catch (Exception e) {
            synchronized (activeProfileLock) {
                if (pendingActiveProfile != pending) return;
                pendingActiveProfile = null;
            }

            timeoutHandler.removeCallbacks(pending.timeout);
            pending.call.reject("Failed to query the active DataWedge profile", "BROADCAST_FAILED", e);
        }
    }

    private CommandResult parseCompleteCommandResult(
        Intent intent,
        String commandName,
        String commandIdentifier
    ) {
        List<Bundle> resultList = getCompleteResultList(intent);
        JSArray results = new JSArray();
        boolean success = !resultList.isEmpty();
        String resultCode = null;

        for (Bundle moduleResult : resultList) {
            String module = moduleResult.getString("MODULE");
            String result = moduleResult.getString("RESULT");
            String moduleResultCode = getResultCode(moduleResult);
            String subResultCode = moduleResult.getString("SUB_RESULT_CODE");
            boolean moduleSuccess = "SUCCESS".equalsIgnoreCase(result);

            JSObject moduleDetails = new JSObject();
            moduleDetails.put("module", module);
            moduleDetails.put("result", result);
            moduleDetails.put("resultCode", moduleResultCode);
            moduleDetails.put("subResultCode", subResultCode);
            results.put(moduleDetails);

            if (!moduleSuccess) {
                success = false;
                if (resultCode == null) {
                    resultCode = moduleResultCode != null
                        ? moduleResultCode
                        : "DATAWEDGE_COMMAND_FAILED";
                }
            }
        }

        if (resultList.isEmpty()) {
            resultCode = "DATAWEDGE_INVALID_RESULT";
        }

        JSObject details = new JSObject();
        details.put("command", commandName);
        details.put("commandIdentifier", commandIdentifier);
        details.put("result", success ? "SUCCESS" : "FAILURE");
        details.put("resultCode", resultCode);
        details.put("results", results);

        return new CommandResult(success, commandName, resultCode, details);
    }

    @SuppressWarnings("deprecation")
    private List<Bundle> getCompleteResultList(Intent intent) {
        Serializable serializedResults;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            serializedResults = intent.getSerializableExtra(EXTRA_RESULT_LIST, ArrayList.class);
        } else {
            serializedResults = intent.getSerializableExtra(EXTRA_RESULT_LIST);
        }

        if (!(serializedResults instanceof List<?>)) {
            return new ArrayList<>();
        }

        ArrayList<Bundle> results = new ArrayList<>();
        for (Object result : (List<?>) serializedResults) {
            if (result instanceof Bundle) {
                results.add((Bundle) result);
            }
        }

        return results;
    }

    private String getResultCode(Bundle resultInfo) {
        if (resultInfo == null) return null;

        String resultCode = resultInfo.getString("RESULT_CODE");
        if (resultCode != null) return resultCode;

        String[] resultCodes = resultInfo.getStringArray("RESULT_CODE");
        return resultCodes == null ? null : String.join(",", resultCodes);
    }

    private void rejectCall(PluginCall call, CommandResult result) {
        String message;
        if ("DATAWEDGE_TIMEOUT".equals(result.resultCode)) {
            message = "DataWedge did not respond to " + result.commandName + " within " + COMMAND_TIMEOUT_MS + " ms";
        } else {
            message = "DataWedge command " + result.commandName + " failed";
            if (result.resultCode != null) {
                message += ": " + result.resultCode;
            }
        }

        call.reject(message, result.resultCode, result.details);
    }

    private boolean isReceiverRegistered = false;
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (DATAWEDGE_RESULT_ACTION.equals(action)) {
                if (intent.hasExtra(EXTRA_RESULT_GET_ACTIVE_PROFILE)) {
                    handleActiveProfileResult(intent);
                    return;
                }
                handleCommandResult(intent);
                return;
            }

            if (DATAWEDGE_NOTIFICATION_ACTION.equals(action)) {
                handleNotification(intent);
                return;
            }

            if (!scanIntent.equals(action)) return;

            try {
                String data = intent.getStringExtra("com.symbol.datawedge.data_string");
                String type = intent.getStringExtra("com.symbol.datawedge.label_type");

                if (data == null) {
                    Log.w(TAG, "Ignoring scan intent without barcode data");
                    return;
                }

                JSObject ret = new JSObject();
                ret.put("data", data);
                ret.put("type", type);

                notifyListeners("scan", ret);
            } catch(Exception e) {
                Log.e(TAG, "Failed to process scan intent", e);
            }
        }
    };

    private interface CommandResultCallback {
        void onResult(CommandResult result);
    }

    private static final class PendingCommand {
        private final String commandName;
        private final CommandResultCallback callback;
        private final Runnable timeout;

        private PendingCommand(String commandName, CommandResultCallback callback, Runnable timeout) {
            this.commandName = commandName;
            this.callback = callback;
            this.timeout = timeout;
        }
    }

    private static final class PendingActiveProfile {
        private final PluginCall call;
        private final Runnable timeout;

        private PendingActiveProfile(PluginCall call, Runnable timeout) {
            this.call = call;
            this.timeout = timeout;
        }
    }

    private static final class CommandResult {
        private final boolean success;
        private final String commandName;
        private final String resultCode;
        private final JSObject details;

        private CommandResult(boolean success, String commandName, String resultCode, JSObject details) {
            this.success = success;
            this.commandName = commandName;
            this.resultCode = resultCode;
            this.details = details;
        }
    }
}
