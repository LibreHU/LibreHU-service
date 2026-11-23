package android.os;

/** Compile-time signature of the framework class (Android 9 / 10); see hidlstub/build.gradle.kts. */
public interface IHwInterface {
    IHwBinder asBinder();
}
