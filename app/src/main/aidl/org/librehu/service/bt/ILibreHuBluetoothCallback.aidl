package org.librehu.service.bt;

import org.librehu.service.bt.BtCallInfo;
import org.librehu.service.bt.BtDeviceInfo;
import org.librehu.service.bt.BtMediaInfo;
import org.librehu.service.bt.BtStatus;

/** Bluetooth events. All on one ordered oneway channel: apply them in arrival order. */
oneway interface ILibreHuBluetoothCallback {
    void onStatusChanged(in BtStatus status);
    /** Every change of the call list (empty list = no call). */
    void onCallsChanged(in List<BtCallInfo> calls);
    void onMediaChanged(in BtMediaInfo media);
    /** Paired devices or their connection states changed. */
    void onDevicesChanged();
    /** A device found while discovering. */
    void onDeviceFound(in BtDeviceInfo device);
    /** Phone book or call log downloaded again. */
    void onPhonebookChanged();
    /**
     * A phone asks to pair and the service does not answer by itself (not in active mode, or passkey to type):
     * answer with confirmPairing(). variant = BluetoothDevice.PAIRING_VARIANT_*, passkey = -1 when none.
     */
    void onPairingRequest(String address, String name, int variant, int passkey);
}
