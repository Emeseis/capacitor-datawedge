# capacitor-datawedge

Zebra DataWedge plugin for Capacitor.
This plugin allows you to receive and handle barcode scan data on Zebra mobile computers (e.g., MC33, TC77, etc.) using the _Zebra DataWedge API_ and native Android Intent broadcasts.

## Installation

```bash
npm install git+https://github.com/Emeseis/capacitor-datawedge
npx cap sync android
```

## Automatic Configuration

This version of the plugin includes a **Profile Auto-Registration** feature. You no longer need to manually create a profile in the Zebra DataWedge app.

The `registerProfile()` method automatically:

1. Creates the profile if it doesn't exist.
2. Configures **Intent Output** to "Broadcast Intent".
3. **Disables Keystroke Output** to prevent duplicate data or keyboard simulation.
4. Links the profile specifically to your application's package name.
5. Restricts Intent Output delivery to that package.

## API

All methods are fully asynchronous and return a `Promise` after DataWedge confirms the command result. Profile registration validates the result of every configured module. The promise is rejected with the DataWedge result code whenever DataWedge reports a failure, including `SCANNER_ALREADY_*` responses, or with `DATAWEDGE_TIMEOUT` when DataWedge does not respond within 10 seconds.

### registerProfile(options?: { name?: string; intentAction?: string })

Creates or updates the Zebra DataWedge profile for the current app. Default name is `CapacitorDataWedgeProfile`.
Use a package-specific profile name and intent action when multiple variants of the same app can be installed together.

```typescript
registerProfile({
  name: 'MyCustomProfile.com.example.app',
  intentAction: 'com.example.app.datawedge.SCAN',
}) => Promise<void>
```

### addListener('scan', ...)

Registers a listener for incoming barcode data.

```typescript
addListener(eventName: 'scan', listenerFunc: (event: { data: string, type: string | null }) => void) => Promise<PluginListenerHandle>
```

**Event Data:**

- `data`: The decoded barcode string.
- `type`: The symbology (e.g., EAN13, Code128).

### addListener('scannerStatus', ...)

Registers a listener for scanner status changes. The profile name allows apps to ignore notifications from unrelated DataWedge profiles.

```typescript
addListener(
  eventName: 'scannerStatus',
  listenerFunc: (event: {
    status: 'WAITING' | 'SCANNING' | 'IDLE' | 'DISABLED' | 'CONNECTED' | 'DISCONNECTED',
    profileName: string | null
  }) => void
) => Promise<PluginListenerHandle>
```

### enableScanner() / disableScanner()

Enables or disables the Scanner Input Plug-in for the active DataWedge profile. This changes only its runtime state and does not persistently modify the profile.

```typescript
enableScanner() => Promise<void>
disableScanner() => Promise<void>
```

### suspendScanner() / resumeScanner()

Temporarily pauses or resumes scanner input without fully disabling and re-enabling the scanner plug-in. These methods are intended for fast scanner state changes between app screens.

The scanner plug-in must be enabled before it can be suspended. Call `suspendScanner()` only after a `scannerStatus` event reports `WAITING` or `SCANNING`, and use `resumeScanner()` to restore scanning.

```typescript
suspendScanner() => Promise<void>
resumeScanner() => Promise<void>
```

### startScanning() / stopScanning()

Triggers a "Soft Scan" (programmatic trigger) or stops it.

```typescript
startScanning() => Promise<void>
stopScanning() => Promise<void>
```

### enable() / disable()

Enables or disables the DataWedge service on the device.

```typescript
enable() => Promise<void>
disable() => Promise<void>
```

## Usage Example

```typescript
import { DataWedge } from 'capacitor-datawedge';

async function example() {
  try {
    // 1. Auto-register the profile on startup
    await DataWedge.registerProfile({ name: 'DataWedgeProfileName' });

    // 2. Setup the scan listener
    await DataWedge.addListener('scan', (event) => {
      console.log('Barcode Scanned:', event.data);
      console.log('Format:', event.type);
    });

    // 3. Ensure scanner input is enabled for the active profile
    await DataWedge.enableScanner();
  } catch (err) {
    console.error('DataWedge Initialization Error:', err);
  }
}

example();
```

## Hardware Requirements

- Zebra devices (MC33, TC77, etc.) running Android.
- DataWedge service v8.0 or newer installed and active. Secure Intent Output targeting through `intent_component_info` requires DataWedge 8.0+.
