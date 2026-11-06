# Usage: record_gif.py PLATFORM OUT.gif
# Records an animated GIF of Nacashu for the Pebble appstore, which shows GIFs as screenshots.
# Run it under pebble-tool's Python after `pebble install --emulator PLATFORM`.
#
# It plays the phone itself: it stops the emulator's phone simulator (pypkjs), connects straight
# to QEMU, presses the buttons and answers each photo the way the companion app would. It takes
# screenshots the way `pebble screenshot` does, as fast as it can, and keeps each screen for as long
# as it was shown, so a screen that's only up briefly (like 3/3 before Done) isn't missed.
import json, os, signal, sys, tempfile, threading, time
from PIL import Image
from libpebble2.communication import PebbleConnection
from libpebble2.communication.transports.qemu import QemuTransport, MessageTargetQemu
from libpebble2.communication.transports.qemu.protocol import QemuButton
from libpebble2.services.appmessage import AppMessageService, Uint8
from libpebble2.services.screenshot import Screenshot
from pebble_tool.commands.screenshot import ScreenshotCommand

APP = "24258afb-b38c-4325-a2ca-aad66022a81a"
KEY_CMD, KEY_STATUS, CMD_SHUTTER, STATUS_OK = 1, 2, 1, 0
# How long the "phone" takes to answer a photo. With 0.5 s every screen lasts a whole number of
# half seconds, so rounding the times below gives the same GIF on every run.
PHONE_DELAY = 0.5

platform, out = sys.argv[1], sys.argv[2]
info = json.load(open(os.path.join(tempfile.gettempdir(), 'pb-emulator.json')))[platform]
info = next(iter(info.values()))
os.kill(info['pypkjs']['pid'], signal.SIGKILL)  # Frees QEMU's phone port for us.
time.sleep(1)

pebble = PebbleConnection(QemuTransport('localhost', info['qemu']['port']))
pebble.connect()
pebble.run_async()
appmsg = AppMessageService(pebble)


def answer(tid, app_uuid, data):
    if str(app_uuid) == APP and data.get(KEY_CMD) == CMD_SHUTTER:
        threading.Timer(PHONE_DELAY, appmsg.send_message, (app_uuid, {KEY_STATUS: Uint8(STATUS_OK)})).start()


appmsg.register_handler('appmessage', answer)


def button(state):
    pebble.transport.send_packet(QemuButton(state=state), target=MessageTargetQemu())


def click(b, hold=0.1):
    button(b)
    time.sleep(hold)
    button(0)


screenshot = Screenshot(pebble)


def grab():
    # Read from the watch's frame buffer, like `pebble screenshot`, rather than QEMU's display,
    # which shakes while the watch vibrates and dims with the backlight.
    rows = ScreenshotCommand._correct_colours(None, screenshot.grab_image())
    return Image.frombytes('RGB', (len(rows[0]) // 3, len(rows)), bytes(v for row in rows for v in row))


# Start from the default choices (timer 0, 1 photo) and wait for "Reset" to clear.
click(QemuButton.Button.Up, hold=1)
time.sleep(6)

# Steps, in seconds from the start: show the time, timer 0 -> 3, photos 1 -> 3, shutter.
steps = [(2, QemuButton.Button.Up), (3, QemuButton.Button.Down), (4, QemuButton.Button.Select)]
length = 13  # Countdown, 3 photos and "Done" for a couple of seconds.
frames = []  # [image, seconds shown]
start = time.time()
last = start
while last - start < length:
    if steps and last - start >= steps[0][0]:
        click(steps.pop(0)[1])
    image = grab()
    now = time.time()
    if frames and image.tobytes() == frames[-1][0].tobytes():
        frames[-1][1] += now - last
    else:
        frames.append([image, now - last])
    last = now
print('{} screenshots in {:.1f} s'.format(len(frames), last - start))

# Round times to half seconds so reruns give the same GIF.
images = [image for image, _ in frames]
durations = [max(500, round(seconds * 2) * 500) for _, seconds in frames]
images[0].save(out, save_all=True, append_images=images[1:], duration=durations, loop=0)
print('{} screens, {:.1f} s: {}'.format(len(images), sum(durations) / 1000, durations))
os._exit(0)  # Don't wait for libpebble2's reader thread.
