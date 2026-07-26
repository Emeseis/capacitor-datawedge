package com.haeb.datawedge;

import com.getcapacitor.Plugin;
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
import android.os.Handler;
import android.os.Looper;

import android.util.Log;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(name = "DataWedge")
public class DataWedgePlugin extends Plugin {

    private static final String DATAWEDGE_RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION";
    private static final String EXTRA_COMMAND_IDENTIFIER = "COMMAND_IDENTIFIER";
    private static final String EXTRA_SEND_RESULT = "SEND_RESULT";
    private static final long COMMAND_TIMEOUT_MS = 10_000;

    private final DataWedge implementation = new DataWedge();
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    private String scanIntent = "com.capacitor.datawedge.RESULT_ACTION";

    @Override
    public void load() {
        super.load();

        try {
            registerBroadcastReceiver(getBridge().getContext());
        } catch (Exception e) {
            Log.e("Capacitor/DataWedge", "Failed to register event receiver during plugin initialization", e);
        }
    }

    @Override
    protected void handleOnDestroy() {
        Context context = getBridge().getContext();

        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(broadcastReceiver);
            } catch (IllegalArgumentException e) {
                Log.w("Capacitor/DataWedge", "Event receiver was already unregistered", e);
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

        Intent createIntent = new Intent();
        createIntent.setAction("com.symbol.datawedge.api.ACTION");
        createIntent.putExtra("com.symbol.datawedge.api.CREATE_PROFILE", profileName);

        Intent configIntent = new Intent();
        configIntent.setAction("com.symbol.datawedge.api.ACTION");

        Bundle profileConfig = new Bundle();
        profileConfig.putString("PROFILE_NAME", profileName);
        profileConfig.putString("PROFILE_ENABLED", "true");
        profileConfig.putString("CONFIG_MODE", "OVERWRITE");

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
            if (result.success || isResultCode(result, "PROFILE_ALREADY_EXIST", "PROFILE_ALREADY_EXISTS")) {
                sendCommand(call, configIntent, "SET_CONFIG");
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

        final String intentName = call.getString("intent");
        if (intentName != null) this.scanIntent = intentName;

        try {
            if (isReceiverRegistered) {
                context.unregisterReceiver(broadcastReceiver);
                isReceiverRegistered = false;
            }

            registerBroadcastReceiver(context);
            call.resolve();
        } catch(Exception e) {
            Log.e("Capacitor/DataWedge", "Failed to register event receiver", e);
            call.reject("Failed to register DataWedge event receiver", e);
        }
    }

    private void registerBroadcastReceiver(Context context) {
        if (isReceiverRegistered) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(this.scanIntent);
        filter.addAction(DATAWEDGE_RESULT_ACTION);
        filter.addCategory(Intent.CATEGORY_DEFAULT);

        ContextCompat.registerReceiver(context, broadcastReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        isReceiverRegistered = true;
    }

    private void broadcast(Intent intent) {
        Context context = getBridge().getContext();
        context.sendBroadcast(intent);
    }

    private void sendCommand(PluginCall call, Intent intent, String commandName, String... acceptedResultCodes) {
        sendCommand(intent, commandName, result -> {
            if (result.success || isResultCode(result, acceptedResultCodes)) {
                call.resolve();
            } else {
                rejectCall(call, result);
            }
        });
    }

    private void sendCommand(Intent intent, String commandName, CommandResultCallback callback) {
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

        intent.putExtra(EXTRA_SEND_RESULT, "true");
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

    private String getResultCode(Bundle resultInfo) {
        if (resultInfo == null) return null;

        Object resultCode = resultInfo.get("RESULT_CODE");
        if (resultCode instanceof String[]) {
            return String.join(",", (String[]) resultCode);
        }

        return resultCode == null ? null : resultCode.toString();
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

            if (!scanIntent.equals(action)) return;

            try {
                String data = intent.getStringExtra("com.symbol.datawedge.data_string");
                String type = intent.getStringExtra("com.symbol.datawedge.label_type");

                JSObject ret = new JSObject();
                ret.put("data", data);
                ret.put("type", type);

                notifyListeners("scan", ret);
            } catch(Exception e) {}
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
