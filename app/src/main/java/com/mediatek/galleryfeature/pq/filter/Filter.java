package com.mediatek.galleryfeature.pq.filter;

/**
 * LibreHU: binding to the ROM's MediaTek picture quality library ({@code libPQjni.so}, the one MediaTek's gallery and
 * Jancar's {@code com.jancar.tftdisplay} load), whose JNI functions belong to this class name. Same package, name and
 * native methods (all of them: RegisterNatives fails on a missing one); no MediaTek code. Only the root helper loads it
 * ({@code AtcHelper pq}). An index goes from 0 to range - 1.
 */
public class Filter {
    static {
        System.loadLibrary("PQjni");
    }

    public native int nativeGetBrightnessAdjIndex();

    public native int nativeGetBrightnessAdjRange();

    public native int nativeGetContrastAdjIndex();

    public native int nativeGetContrastAdjRange();

    public native int nativeGetGrassToneHIndex();

    public native int nativeGetGrassToneHRange();

    public native int nativeGetGrassToneSIndex();

    public native int nativeGetGrassToneSRange();

    public native int nativeGetHueAdjIndex();

    public native int nativeGetHueAdjRange();

    public native int nativeGetSatAdjIndex();

    public native int nativeGetSatAdjRange();

    public native int nativeGetSharpAdjIndex();

    public native int nativeGetSharpAdjRange();

    public native int nativeGetSkinToneHIndex();

    public native int nativeGetSkinToneHRange();

    public native int nativeGetSkinToneSIndex();

    public native int nativeGetSkinToneSRange();

    public native int nativeGetSkyToneHIndex();

    public native int nativeGetSkyToneHRange();

    public native int nativeGetSkyToneSIndex();

    public native int nativeGetSkyToneSRange();

    public native int nativeGetXAxisIndex();

    public native int nativeGetXAxisRange();

    public native int nativeGetYAxisIndex();

    public native int nativeGetYAxisRange();

    public native boolean nativeSetBrightnessAdjIndex(int index);

    public native boolean nativeSetContrastAdjIndex(int index);

    public native boolean nativeSetGrassToneHIndex(int index);

    public native boolean nativeSetGrassToneSIndex(int index);

    public native boolean nativeSetHueAdjIndex(int index);

    public native boolean nativeSetSatAdjIndex(int index);

    public native boolean nativeSetSharpAdjIndex(int index);

    public native boolean nativeSetSkinToneHIndex(int index);

    public native boolean nativeSetSkinToneSIndex(int index);

    public native boolean nativeSetSkyToneHIndex(int index);

    public native boolean nativeSetSkyToneSIndex(int index);

    public native boolean nativeSetXAxisIndex(int index);

    public native boolean nativeSetYAxisIndex(int index);
}
