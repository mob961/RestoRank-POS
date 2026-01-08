# Keep ESC/POS library
-keep class com.dantsu.escposprinter.** { *; }

# Keep JavaScript interface methods
-keepclassmembers class ai.restorank.bridge.PrinterBridge {
    public *;
}

# Keep Bluetooth classes
-keep class android.bluetooth.** { *; }
