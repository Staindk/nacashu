// Copied from https://github.com/pebble-dev/PebbleKitAndroid2 (Apache License 2.0).
package io.rebble.pebblekit2.common;

import io.rebble.pebblekit2.common.SendDataCallback;

interface UniversalRequestResponse {
    void request(in Bundle data, in SendDataCallback callback);
}
