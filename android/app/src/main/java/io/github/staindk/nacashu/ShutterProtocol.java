package io.github.staindk.nacashu;

import java.util.UUID;

/** AppMessage keys and values. Must match watch/src/c/main.c. */
final class ShutterProtocol {
    static final UUID WATCHAPP_UUID = UUID.fromString("24258afb-b38c-4325-a2ca-aad66022a81a");

    static final int KEY_CMD = 1;
    static final int KEY_STATUS = 2;

    static final int CMD_SHUTTER = 1;
    static final int CMD_OPEN_CAMERA = 2;

    static final int STATUS_OK = 0;
    static final int STATUS_NO_ACCESSIBILITY = 1;
    static final int STATUS_CAMERA_NOT_OPEN = 2;
    static final int STATUS_SHUTTER_NOT_FOUND = 3;
    static final int STATUS_GESTURE_FALLBACK = 4;

    private ShutterProtocol() {
    }

    static String statusName(int status) {
        switch (status) {
            case STATUS_OK: return "OK";
            case STATUS_NO_ACCESSIBILITY: return "Accessibility service is off";
            case STATUS_CAMERA_NOT_OPEN: return "Camera is not on screen";
            case STATUS_SHUTTER_NOT_FOUND: return "Shutter button not found";
            case STATUS_GESTURE_FALLBACK: return "OK (tapped by position)";
            default: return "Unknown (" + status + ")";
        }
    }

    interface StatusCallback {
        void onStatus(int status);
    }

    /** Runs a watch command. Must be called on the main thread. */
    static void run(int cmd, StatusCallback callback) {
        ShutterAccessibilityService service = ShutterAccessibilityService.instance;
        if (service == null) {
            callback.onStatus(STATUS_NO_ACCESSIBILITY);
        } else if (cmd == CMD_SHUTTER) {
            service.pressShutter(callback);
        } else if (cmd == CMD_OPEN_CAMERA) {
            callback.onStatus(service.openCamera());
        } else {
            callback.onStatus(STATUS_SHUTTER_NOT_FOUND);
        }
    }
}
