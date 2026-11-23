package android.os;

import java.util.ArrayList;

/** Compile-time signature of the framework class (Android 9 / 10); see hidlstub/build.gradle.kts. */
public class HwParcel {
    public static final int STATUS_SUCCESS = 0;

    public HwParcel() {
        throw new RuntimeException("stub");
    }

    public final void writeInterfaceToken(String interfaceName) {
        throw new RuntimeException("stub");
    }

    public final void enforceInterface(String interfaceName) {
        throw new RuntimeException("stub");
    }

    public final void writeInt32(int val) {
        throw new RuntimeException("stub");
    }

    public final void writeString(String val) {
        throw new RuntimeException("stub");
    }

    public final void writeStringVector(ArrayList<String> val) {
        throw new RuntimeException("stub");
    }

    public final void writeStrongBinder(IHwBinder binder) {
        throw new RuntimeException("stub");
    }

    public final void writeStatus(int status) {
        throw new RuntimeException("stub");
    }

    public final int readInt32() {
        throw new RuntimeException("stub");
    }

    public final HwBlob readBuffer(long expectedSize) {
        throw new RuntimeException("stub");
    }

    public final void verifySuccess() {
        throw new RuntimeException("stub");
    }

    public final void releaseTemporaryStorage() {
        throw new RuntimeException("stub");
    }

    public final void send() {
        throw new RuntimeException("stub");
    }
}
