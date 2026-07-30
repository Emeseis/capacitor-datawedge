import type { PluginListenerHandle } from '@capacitor/core';

export interface ScanListenerEvent {
  /**
   * Data of barcode
   *
   * @since 0.1.0
   */
  data: string;

  /**
   * Type of barcode
   *
   * @since 0.2.1
   */
  type: string | null;
}

export type ScanListener = (state: ScanListenerEvent) => void;

export type ScannerStatus = 'WAITING' | 'SCANNING' | 'IDLE' | 'DISABLED' | 'CONNECTED' | 'DISCONNECTED';

export interface ScannerStatusListenerEvent {
  /**
   * Current state reported by DataWedge for the scanner in the active profile.
   */
  status: ScannerStatus;

  /**
   * Name of the DataWedge profile that emitted the status.
   */
  profileName: string | null;
}

export type ScannerStatusListener = (state: ScannerStatusListenerEvent) => void;

export type RegisterOptions = {
  /**
   * Intent action name to listen for
   *
   * @since 0.3.1
   */
  intent?: string;
};

export interface RegisterProfileOptions {
  /**
   * DataWedge profile name.
   */
  name?: string;

  /**
   * Intent action used exclusively to deliver scans to this application.
   */
  intentAction?: string;
}

export interface DataWedgePlugin {
  /**
   * Automatically register and configure a profile in DataWedge for the current application.
   */
  registerProfile(options?: RegisterProfileOptions): Promise<void>;

  /**
   * Enables DataWedge
   *
   * Broadcasts intent action with `.ENABLE_DATAWEDGE` extra set to `true`
   *
   * @since 0.0.3
   */
  enable(): Promise<void>;

  /**
   * Disables DataWedge
   *
   * Broadcasts intent action with `.ENABLE_DATAWEDGE` extra set to `false`
   *
   * @since 0.0.3
   */
  disable(): Promise<void>;

  /**
   * Enables the scanner input plug-in for the active DataWedge profile.
   *
   * This changes only the scanner's runtime state and does not persistently update the profile.
   *
   * Broadcasts intent action with `.SCANNER_INPUT_PLUGIN` extra set to `ENABLE_PLUGIN`
   *
   * @since 0.0.3
   */
  enableScanner(): Promise<void>;

  /**
   * Disables the scanner input plug-in for the active DataWedge profile.
   *
   * This changes only the scanner's runtime state and does not persistently update the profile.
   *
   * Broadcasts intent action with `.SCANNER_INPUT_PLUGIN` extra set to `DISABLE_PLUGIN`
   *
   * @since 0.0.3
   */
  disableScanner(): Promise<void>;

  /**
   * Temporarily suspends scanner input for the active DataWedge profile.
   *
   * Call only after scanner status reports `WAITING` or `SCANNING`.
   *
   * Broadcasts intent action with `.SCANNER_INPUT_PLUGIN` extra set to `SUSPEND_PLUGIN`
   */
  suspendScanner(): Promise<void>;

  /**
   * Resumes scanner input after it was suspended for the active DataWedge profile.
   *
   * Broadcasts intent action with `.SCANNER_INPUT_PLUGIN` extra set to `RESUME_PLUGIN`
   */
  resumeScanner(): Promise<void>;

  /**
   * Starts software scanning trigger
   *
   * Broadcasts intent action with `.SOFT_SCAN_TRIGGER` extra set to `START_SCANNING`
   *
   * @since 0.1.2
   */
  startScanning(): Promise<void>;

  /**
   * Stops software scanning trigger
   *
   * Broadcasts intent action with `.SOFT_SCAN_TRIGGER` extra set to `STOP_SCANNING`
   *
   * @since 0.1.2
   */
  stopScanning(): Promise<void>;

  /**
   * Listen for successful barcode readings
   *
   * ***Notice:*** Requires intent action to be set to `com.capacitor.datawedge.RESULT_ACTION` in current DataWedge profile (it may change in the future)
   *
   * @since 0.1.0
   */
  addListener(eventName: 'scan', listenerFunc: ScanListener): Promise<PluginListenerHandle>;

  /**
   * Listen for scanner status changes reported by DataWedge.
   */
  addListener(eventName: 'scannerStatus', listenerFunc: ScannerStatusListener): Promise<PluginListenerHandle>;

  /**
   * Internal method to register intent broadcast receiver
   *
   * THIS METHOD IS FOR INTERNAL USE ONLY
   *
   * @since 0.1.3
   * @private
   */
  __registerReceiver(options?: RegisterOptions): Promise<void>;
}
