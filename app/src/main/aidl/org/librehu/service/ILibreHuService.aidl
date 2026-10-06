package org.librehu.service;

import android.os.Bundle;

import org.librehu.service.ILibreHuCallback;
import org.librehu.service.bt.ILibreHuBluetooth;

/**
 * Public API of LibreHU-service (bind with action org.librehu.service.BIND, package org.librehu.service,
 * permission org.librehu.permission.HEADUNIT).
 *
 * Vehicle flags: see the FLAG_* constants of org.librehu.service.LibreHu.
 * Ranges: volume 0..getMaxVolume(), tone 0..20 (10 = flat), balance/fade 0..60 (30 = centre, balance 0 = left,
 * fade 0 = front), loudness 0..15, subwoofer level 0..12 (-5..+7 dB).
 */
interface ILibreHuService {
    int getApiVersion();

    /** Human readable state of the hardware link (for diagnostics). */
    String getStatus();

    String getMcuVersion();
    int getVehicleFlags();

    int getVolume();
    int getMaxVolume();
    void setVolume(int step);
    boolean isMuted();
    void setMuted(boolean muted);

    /** [bass, middle, treble] */
    int[] getTone();
    void setTone(int bass, int middle, int treble);

    /** [balance, fade] */
    int[] getBalanceFade();
    void setBalanceFade(int balance, int fade);

    int getLoudness();
    void setLoudness(int level);

    boolean isSubwooferOn();
    int getSubwooferLevel();
    void setSubwoofer(boolean on, int level);

    boolean isExternalAmpEnabled();
    void setExternalAmpEnabled(boolean enabled);

    /** Raw frame to the MCU (JAC_V1 command and data, framing and checksum added by the service). */
    void sendMcuFrame(int cmd, in byte[] data);

    /** Bytes to the CAN box (MCU command 0x10). */
    void sendCanData(in byte[] data);

    void registerCallback(ILibreHuCallback callback);
    void unregisterCallback(ILibreHuCallback callback);

    // --- API 2 (new methods go at the end to keep the transaction numbers of older clients) ---

    /** Radio antenna power (GPIO 110 + MCU 0x43), requested by the radio app while it plays. Cut at ACC off. */
    void setRadioAntenna(boolean on);
    boolean isRadioAntennaOn();

    // --- API 3 ---

    /** Bluetooth (hands-free, music, phone book): see org.librehu.service.bt.ILibreHuBluetooth. */
    ILibreHuBluetooth getBluetooth();

    // --- API 4 ---

    /**
     * Last OBD-II values of the ELM327 adapter: keys = org.librehu.core.obd.ObdPid names (RPM, SPEED,
     * COOLANT_TEMP…) plus "BATTERY" (adapter voltage), values = doubles. Empty when not connected.
     */
    Bundle getObdValues();
    /** 0 off, 1 connecting, 2 initialising, 3 connected, 4 error. */
    int getObdState();
    /** Name of the MCU protocol profile in use. */
    String getMcuProtocol();

    // --- API 5 ---

    /** Restarts the head unit through the MCU (power cycle of the SoC, MCU command 0E): works even when Android hangs. */
    void resetSoc();

    // --- API 6 ---

    /** Input of the sound processor: 0 = Android (default at each start), 1 = AUX jack. */
    void setAudioSource(int source);
    int getAudioSource();
}
