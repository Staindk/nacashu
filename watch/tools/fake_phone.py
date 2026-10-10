# Usage: with the emulator running, ~/.local/share/uv/tools/pebble-tool/bin/python fake_phone.py
# then press SELECT on the watch within 8 seconds.
# Plays the phone side over the emulator's pebble-tool port: prints the watch's
# AppMessage, then replies with STATUS=0 the way the companion app would.
import time, uuid
from libpebble2.communication import PebbleConnection
from libpebble2.communication.transports.qemu import QemuTransport
from libpebble2.services.appmessage import AppMessageService, Uint8

APP = uuid.UUID("24258afb-b38c-4325-a2ca-aad66022a81a")
pebble = PebbleConnection(QemuTransport("localhost", 12344))
pebble.connect()
pebble.run_async()
svc = AppMessageService(pebble)
got = []
svc.register_handler("appmessage", lambda tid, app_uuid, data: got.append((app_uuid, data)))
time.sleep(8)
print("received:", got)
svc.send_message(APP, {2: Uint8(0)})
time.sleep(1.5)
