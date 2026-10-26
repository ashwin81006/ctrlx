package com.ctrlx.server;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Windows Media Controller translating remote media commands
 * (volume adjustment, playback control) into native Windows virtual key events.
 */
public class MediaController {

    public interface User32Library extends Library {
        User32Library INSTANCE = Native.load("user32", User32Library.class);
        void keybd_event(byte bVk, byte bScan, int dwFlags, Pointer dwExtraInfo);
    }

    // Windows Virtual Key codes for multimedia
    public static final byte VK_VOLUME_MUTE = (byte) 0xAD;
    public static final byte VK_VOLUME_DOWN = (byte) 0xAE;
    public static final byte VK_VOLUME_UP = (byte) 0xAF;
    public static final byte VK_MEDIA_NEXT_TRACK = (byte) 0xB0;
    public static final byte VK_MEDIA_PREV_TRACK = (byte) 0xB1;
    public static final byte VK_MEDIA_STOP = (byte) 0xB2;
    public static final byte VK_MEDIA_PLAY_PAUSE = (byte) 0xB3;

    private static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
    private static final int KEYEVENTF_KEYUP = 0x0002;

    private boolean available = true;

    public MediaController() {
        try {
            User32Library lib = User32Library.INSTANCE;
        } catch (Throwable t) {
            System.err.println("Notice: Windows User32 media controller unavailable: " + t.getMessage());
            available = false;
        }
    }

    public synchronized void executeCommand(String command) {
        if (!available || command == null) {
            return;
        }
        byte vk = getVirtualKey(command.toUpperCase().trim());
        if (vk != 0) {
            sendKey(vk);
        }
    }

    private void sendKey(byte vk) {
        try {
            User32Library.INSTANCE.keybd_event(vk, (byte) 0, KEYEVENTF_EXTENDEDKEY, null);
            User32Library.INSTANCE.keybd_event(vk, (byte) 0, KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP, null);
        } catch (Throwable t) {
            System.err.println("MediaController keybd_event failed: " + t.getMessage());
        }
    }

    private byte getVirtualKey(String cmd) {
        switch (cmd) {
            case "VOL_UP":
            case "VOLUME_UP":
                return VK_VOLUME_UP;
            case "VOL_DOWN":
            case "VOLUME_DOWN":
                return VK_VOLUME_DOWN;
            case "MUTE":
            case "VOLUME_MUTE":
                return VK_VOLUME_MUTE;
            case "PLAY_PAUSE":
            case "PLAY":
            case "PAUSE":
                return VK_MEDIA_PLAY_PAUSE;
            case "NEXT_TRACK":
            case "NEXT":
                return VK_MEDIA_NEXT_TRACK;
            case "PREV_TRACK":
            case "PREV":
            case "PREVIOUS":
                return VK_MEDIA_PREV_TRACK;
            case "STOP":
                return VK_MEDIA_STOP;
            default:
                return 0;
        }
    }
}
