# AirGesture release 规则
# 第三方 AAR（MediaPipe / CameraX / AndroidX）自带 consumer 规则，这里只补充本项目需要。

# release 移除调试日志（v/d/i），保留 w/e 用于排障
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# 保留行号，便于用 mapping.txt 还原崩溃堆栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile