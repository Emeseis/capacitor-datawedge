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
    private static final String EXTRA_SEND_RESULT = "SEND_RESULT";
    private static final String EXTRA_RESULT_LIST = "RESULT_LIST";
    private static final String NOTIFICATION_TYPE_SCANNER_STATUS = "SCANNER_STATUS";
    private static final String SEND_RESULT_LAST = "LAST_RESULT";
    private static final String SEND_RESULT_COMPLETE = "COMPLETE_RESULT";
    private static final long COMMAND_TIMEOUT_MS = 10_000;

    private final DataWedge implementation = new DataWedge();
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    private String scanIntent = "com.capacitor.datawedge.RESULT_ACTION";

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

        super.handleOnDestroy();
    }

    @PluginMethod
    public void registerProfile(PluginCall call) {
        String profileName = call.getString("name", "CapacitorDataWedgeProfile");
        Context context = getBridge().getContext();
        String packageName = context.getPackageName();
        String intentAction = call.getString("intentAction");

        try {
            setScanIntent(context, intentAction);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update scan intent receiver", e);
            call.reject("Failed to update DataWedge scan intent receiver", e);
            return;
        }

        Intent createIntent = new Intent();
        createIntent.setAction("com.symbol.datawedge.api.ACTION");
        createIntent.putExtra("com.symbol.datawedge.api.CREATE_PROFILE", profileName);

        Intent configIntent = new Intent();
        configIntent.setAction("com.symbol.datawedge.api.ACTION");

        Bundle profileConfig = new Bundle();
        profileConfig.putString("PROFILE_NAME", profileName);
        profileConfig.putString("PROFILE_ENABLED", "true");
        profileConfig.putString("CONFIG_MODE", "UPDATE");

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
        intentProps.putString("intent_delivery", "2");

        Bundle intentComponent = new Bundle();
        intentComponent.putString("PACKAGE_NAME", packageName);
        intentProps.putParcelableArray("intent_component_info", new Bundle[]{intentComponent});
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

        sendCommand(createIntent, "CREATE_PROFILE", result -> {
            if (result.success) {
                sendCompleteCommand(call, configIntent, "SET_CONFIG");
            } else if (isResultCode(result, "PROFILE_ALREADY_EXIST", "PROFILE_ALREADY_EXISTS")) {
                profileConfig.remove("APP_LIST");
                sendCompleteCommand(call, configIntent, "SET_CONFIG");
            } else {
                rejectCall(call, result);
            }
        });
    }

    @PluginMethod
    public void enable(PluginCall call) {
        sendCommand(call, implementation.enable(), "ENABLE_DATAWEDGE", "DATAWEDGE_ALREADY_ENABLED");
    }

    @PluginMethod
    public void disable(PluginCall call) {
        sendCommand(call, implementation.disable(), "ENABLE_DATAWEDGE", "DATAWEDGE_ALREADY_DISABLED");
    }

    @PluginMethod
    public void enableScanner(PluginCall call) {
        sendCommand(call, implementation.enableScanner(), "SCANNER_INPUT_PLUGIN", "SCANNER_ALREADY_ENABLED");
    }

    @PluginMethod
    public void disableScanner(PluginCall call) {
        sendCommand(call, implementation.disableScanner(), "SCANNER_INPUT_PLUGIN", "SCANNER_ALREADY_DISABLED");
    }

    @PluginMethod
    public void suspendScanner(PluginCall call) {
        sendCommand(call, implementation.suspendScanner(), "SCANNER_INPUT_PLUGIN", "SCANNER_ALREADY_SUSPENDED");
    }

    @PluginMethod
    public void resumeScanner(PluginCall call) {
        sendCommand(call, implementation.resumeScanner(), "SCANNER_INPUT_PLUGIN", "SCANNER_ALREADY_RESUMED");
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
        if (intentAction == null || intentAction.trim().isEmpty() || intentAction.equals(scanIntent)) {
            return;
        }

        if (isReceiverRegistered) {
            context.unregisterReceiver(broadcastReceiver);
            isReceiverRegistered = false;
        }

        scanIntent = intentAction;
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

        JSObject ret = new JSObject();
        ret.put("status", status);
        ret.put("profileName", notification.getString("PROFILE_NAME"));
        notifyListeners("scannerStatus", ret, true);
    }

    private void broadcast(Intent intent) {
        Context context = getBridge().getContext();
        context.sendBroadcast(intent);
    }

    private void sendCommand(PluginCall call, Intent intent, String commandName, String... acceptedResultCodes) {
        sendCommand(intent, commandName, SEND_RESULT_LAST, result -> {
            if (result.success || isResultCode(result, acceptedResultCodes)) {
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

    private void sendCommand(Intent intent, String commandName, CommandResultCallback callback) {
        sendCommand(intent, commandName, SEND_RESULT_LAST, callback);
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

    private boolean isResultCode(CommandResult result, String... expectedCodes) {
        if (result.resultCode == null) return false;

        for (String expectedCode : expectedCodes) {
            if (expectedCode.equals(result.resultCode)) return true;
        }

        return false;
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
