package android.os;

import java.util.NoSuchElementException;

/** Compile-time signature of the framework class (Android 9 / 10); see hidlstub/build.gradle.kts. */
public abstract class HwBinder implements IHwBinder {
    public HwBinder() {
        throw new RuntimeException("stub");
    }

    public final void transact(int code, HwParcel request, HwParcel reply, int flags) {
        throw new RuntimeException("stub");
    }

    public abstract void onTransact(int code, HwParcel request, HwParcel reply, int flags);

    public final boolean linkToDeath(DeathRecipient recipient, long cookie) {
        throw new RuntimeException("stub");
    }

    public final boolean unlinkToDeath(DeathRecipient recipient) {
        throw new RuntimeException("stub");
    }

    public static final IHwBinder getService(String iface, String serviceName) throws NoSuchElementException {
        throw new RuntimeException("stub");
    }

    public static final void configureRpcThreadpool(long maxThreads, boolean callerWillJoin) {
        throw new RuntimeException("stub");
    }

    public static final void joinRpcThreadpool() {
        throw new RuntimeException("stub");
    }
}
