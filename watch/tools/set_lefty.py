# Usage: set_lefty.py PLATFORM 0|1, run with pebble-tool's Python while the watchapp is open in
# that platform's emulator. Sends the Lefty setting (AppMessage key 3) the way the phone app does,
# so CI can take a lefty screenshot.
import sys, time, uuid
from libpebble2.communication import PebbleConnection
from libpebble2.services.appmessage import AppMessageService, Uint8
from pebble_tool.sdk.emulator import ManagedEmulatorTransport

APP = uuid.UUID("24258afb-b38c-4325-a2ca-aad66022a81a")
pebble = PebbleConnection(ManagedEmulatorTransport(sys.argv[1]))
pebble.connect()
pebble.run_async()
AppMessageService(pebble).send_message(APP, {3: Uint8(int(sys.argv[2]))})
time.sleep(1)  # Let the message reach the watch before the connection closes.
