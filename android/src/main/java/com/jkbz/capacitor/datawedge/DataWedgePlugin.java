package com.jkbz.capacitor.datawedge;

import com.getcapacitor.Plugin;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import static android.content.Context.RECEIVER_EXPORTED;

import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;

import android.os.Build;
import android.os.Bundle;

import android.util.Log;
import java.util.ArrayList;

@CapacitorPlugin(name = "DataWedge")
public class DataWedgePlugin extends Plugin {

    private final DataWedge implementation = new DataWedge();

    private String scanIntent = "com.capacitor.datawedge.RESULT_ACTION";

    @PluginMethod
    public void registerProfile(PluginCall call) {
        String profileName = call.getString("name", "CapacitorDataWedgeProfile");
        Context context = getBridge().getContext();
        String packageName = context.getPackageName();

        Intent createIntent = new Intent();
        createIntent.setAction("com.symbol.datawedge.api.ACTION");
        createIntent.putExtra("com.symbol.datawedge.api.CREATE_PROFILE", profileName);
        context.sendBroadcast(createIntent);

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
        context.sendBroadcast(configIntent);

        call.resolve();
    }

    @PluginMethod
    public void enable(PluginCall call) {
        Intent intent = implementation.enable();

        try {
            broadcast(intent);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("DataWedge is not installed or not running");
        }
    }
    @PluginMethod
    public void disable(PluginCall call) {
        Intent intent = implementation.disable();

        try {
            broadcast(intent);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("DataWedge is not installed or not running");
        }
    }

    @PluginMethod
    public void enableScanner(PluginCall call) {
        Intent intent = implementation.enableScanner();

        try {
            broadcast(intent);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("DataWedge is not installed or not running");
        }
    }

    @PluginMethod
    public void disableScanner(PluginCall call) {
        Intent intent = implementation.disableScanner();

        try {
            broadcast(intent);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("DataWedge is not installed or not running");
        }
    }

    @PluginMethod
    public void startScanning(PluginCall call) {
         Intent intent = implementation.startScanning();

         try {
            broadcast(intent);
            call.resolve();
         } catch (ActivityNotFoundException e) {
            call.reject("DataWedge is not installed or not running");
         }
    }

    @PluginMethod
    public void stopScanning(PluginCall call) {
        Intent intent = implementation.stopScanning();

        try {
            broadcast(intent);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("DataWedge is not installed or not running");
        }
    }

    @PluginMethod
    public void __registerReceiver(PluginCall call) { 
        Context context = getBridge().getContext();

        if (isReceiverRegistered) {
          context.unregisterReceiver(broadcastReceiver);
        }

        final String intentName = call.getString("intent");
        if (intentName != null) this.scanIntent = intentName;

        try {
            IntentFilter filter = new IntentFilter(this.scanIntent);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              context.registerReceiver(broadcastReceiver, filter, RECEIVER_EXPORTED);
            } else {
              context.registerReceiver(broadcastReceiver, filter);
            }

            isReceiverRegistered = true;
            call.resolve();
        } catch(Exception e) {
            Log.d("Capacitor/DataWedge", "Failed to register event receiver");
        }
    }

    private void broadcast(Intent intent) {
        Context context = getBridge().getContext();
        context.sendBroadcast(intent);
    }

    private boolean isReceiverRegistered = false;
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (!action.equals(scanIntent)) return;

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
}
