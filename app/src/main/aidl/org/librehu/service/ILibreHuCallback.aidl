package org.librehu.service;

oneway interface ILibreHuCallback {
    void onVehicleFlags(int flags);
    void onAudioChanged();
    /** Every MCU frame, both directions (fromMcu = false for frames sent by the service). */
    void onMcuFrame(int cmd, in byte[] data, boolean fromMcu);
    /** Steering wheel / front panel key: channel 5-6 wheel, 3-4 front panel, 2 knob; values = ADC readings. */
    void onKey(int channel, in int[] values, boolean released, boolean learning);
    void onCanData(in byte[] data);
}
