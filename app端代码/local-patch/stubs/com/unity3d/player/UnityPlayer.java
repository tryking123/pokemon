package com.unity3d.player;

/** 仅用于编译，运行时由原 APK 的 DEX 提供实现。 */
public class UnityPlayer {
    /** 原生 Unity 消息接口签名。 */
    public static void UnitySendMessage(String objectName, String methodName, String message) {
        throw new UnsupportedOperationException();
    }
}
