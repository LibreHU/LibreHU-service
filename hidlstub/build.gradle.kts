// Signatures of Android's HIDL Java classes (android.os.HwBinder, HwParcel, HwBlob, IHwBinder, IHwInterface), system
// APIs left out of the public SDK. compileOnly in :app: never packaged, the device's framework.jar provides them at run
// time to the root helpers that app_process runs (ril/MtkCallHelper).
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
