-keep class org.spongycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.spongycastle.jcajce.provider.** { *; }
-keep class org.spongycastle.crypto.** { *; }
-dontwarn org.spongycastle.**

-dontwarn com.upokecenter.**

-keep public class org.omnione.did.proximity.sdk.core.ProximityException { *; }
-keep public interface org.omnione.did.proximity.sdk.core.transport.Transport { *; }
-keep public interface org.omnione.did.proximity.sdk.core.transport.Transport$Listener { *; }

-keep public class org.omnione.did.proximity.sdk.core.logger.ProximityLogger { public *; }

-keep public class org.omnione.did.proximity.sdk.core.message.Document { public *; }
-keep public class org.omnione.did.proximity.sdk.core.message.DeviceResponse { public *; }
-keep public class org.omnione.did.proximity.sdk.core.message.ItemsRequest { public *; }

-keep public class org.omnione.did.proximity.sdk.core.transport.ble.GattClientTransport { public *; }
