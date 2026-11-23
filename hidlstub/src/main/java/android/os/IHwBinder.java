package android.os;

/** Compile-time signature of the framework class (Android 9 / 10); see hidlstub/build.gradle.kts. */
public interface IHwBinder {
    int FLAG_ONEWAY = 1;

    void transact(int code, HwParcel request, HwParcel reply, int flags);

    IHwInterface queryLocalInterface(String descriptor);

    interface DeathRecipient {
        void serviceDied(long cookie);
    }

    boolean linkToDeath(DeathRecipient recipient, long cookie);

    boolean unlinkToDeath(DeathRecipient recipient);
}
