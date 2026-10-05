package org.librehu.service.bt;

import org.librehu.service.bt.BtCallInfo;
import org.librehu.service.bt.BtContact;
import org.librehu.service.bt.BtDeviceInfo;
import org.librehu.service.bt.BtMediaInfo;
import org.librehu.service.bt.BtStatus;
import org.librehu.service.bt.ILibreHuBluetoothCallback;

/**
 * Bluetooth of the head unit (hands-free, music, phone book), the counterpart of Jancar's btservice
 * (com.jancar.btservice.bluetooth.IBluetooth). Obtained with ILibreHuService.getBluetooth().
 *
 * Built on Android's car Bluetooth profiles (HFP client, A2DP sink + AVRCP controller, PBAP client). Commands are
 * asynchronous: the result arrives through ILibreHuBluetoothCallback. New methods go at the end.
 */
interface ILibreHuBluetooth {
    BtStatus getStatus();

    // --- Adapter and devices ---
    void setEnabled(boolean on);
    void setName(String name);
    void setPin(String pin);
    /** Visible to phones searching for the car for [seconds] (0 = stop). */
    void setDiscoverable(int seconds);
    void setAutoConnect(boolean on);
    void setAutoAnswer(boolean on);
    List<BtDeviceInfo> getBondedDevices();
    List<BtDeviceInfo> getFoundDevices();
    void startDiscovery();
    void stopDiscovery();
    void pair(String address);
    void unpair(String address);
    void confirmPairing(String address, boolean accept, String pinOrPasskey);
    /** Connects hands-free, music and phone book of this phone (and disconnects the other one). */
    void connect(String address);
    void disconnect(String address);

    // --- Calls ---
    List<BtCallInfo> getCalls();
    void dial(String number);
    void redial();
    /** Answers the ringing call (a waiting call puts the current one on hold). */
    void answer();
    /** Rejects the ringing or waiting call. */
    void reject();
    /** Ends the call being talked to or placed. */
    void hangup();
    /** Swaps the active and the held call. */
    void swapCalls();
    /** Ends the active call and takes the waiting or held one. */
    void endAndAccept();
    void sendDtmf(char key);
    /** true: call audio in the car, false: on the phone. */
    void setAudioInCar(boolean inCar);
    void setMicMuted(boolean muted);
    void startVoiceAssistant();
    void stopVoiceAssistant();

    // --- Music ---
    BtMediaInfo getMedia();
    void mediaPlay();
    void mediaPause();
    void mediaPlayPause();
    void mediaNext();
    void mediaPrevious();
    void mediaStop();

    // --- Phone book (downloaded by PBAP into Android's contacts and call log) ---
    int getContactCount();
    /** Contacts sorted by name; page through them (offset, limit) to stay under the binder size limit. */
    List<BtContact> getContacts(int offset, int limit);
    List<BtContact> searchContacts(String query, int limit);
    /** type: 0 all, 1 incoming, 2 outgoing, 3 missed. Most recent first. */
    List<BtContact> getCallLog(int type, int limit);
    /** Contact name for a number, empty when unknown. */
    String lookupName(String number);
    /** Downloads the phone book and call log again. */
    void syncPhonebook();

    void registerCallback(ILibreHuBluetoothCallback callback);
    void unregisterCallback(ILibreHuBluetoothCallback callback);
}
