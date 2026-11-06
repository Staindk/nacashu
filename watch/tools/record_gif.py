# Usage: record_gif.py PLATFORM OUT.gif
# Records an animated GIF of Nacashu for the Pebble appstore, which shows GIFs as screenshots.
# Run it under pebble-tool's Python after `pebble install --emulator PLATFORM`.
#
# It plays the phone itself: it stops the emulator's phone simulator (pypkjs), connects straight
# to QEMU, presses the buttons and answers each photo the way the companion app would. Frames come
# from QEMU's monitor, about 10 a second, and each screen is kept for as long as it was shown, so a
# screen that's only up briefly (like 3/3 before Done) isn't missed.
import json, os, signal, socket, sys, tempfile, threading, time
from PIL import Image
from libpebble2.communication import PebbleConnection
from libpebble2.communication.transports.qemu import QemuTransport, MessageTargetQemu
from libpebble2.communication.transports.qemu.protocol import QemuButton
from libpebble2.services.appmessage import AppMessageService, Uint8
from pebble_tool.commands.screenshot import ScreenshotCommand

APP = "24258afb-b38c-4325-a2ca-aad66022a81a"
KEY_CMD, KEY_STATUS, CMD_SHUTTER, STATUS_OK = 1, 2, 1, 0
# How long the "phone" takes to answer a photo. With 0.5 s every screen lasts a whole number of
# half seconds, so rounding the times below gives the same GIF on every run.
PHONE_DELAY = 0.5
FPS = 10
# With the backlight off, QEMU dims the panel; these map the dim values back (from pebble-tool).
UNLIT_TO_LIT = {
    'emery': {33: 85, 66: 170, 100: 255},
    'flint': {180: 255},
    'gabbro': {60: 85, 120: 170, 180: 255},
}
LIT = {0, 85, 170, 255}
LIT_COLOURS = {(r, g, b) for r in LIT for g in LIT for b in LIT}

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


monitor = socket.create_connection(('localhost', info['qemu']['monitor']))
monitor.settimeout(0.2)
dump = os.path.join(tempfile.mkdtemp(), 'frame.ppm')
lut = [UNLIT_TO_LIT.get(platform, {}).get(v, v) for v in range(256)] * 3


def grab():
    if os.path.exists(dump):
        os.remove(dump)
    monitor.sendall('screendump {}\n'.format(dump).encode())
    deadline = time.time() + 1
    while time.time() < deadline:
        try:
            monitor.recv(4096)
        except socket.timeout:
            pass
        if os.path.exists(dump) and os.path.getsize(dump) > 0:
            try:
                return Image.open(dump).convert('RGB').point(lut)
            except (OSError, SyntaxError):
                pass  # Still being written.
    raise RuntimeError('No screendump from QEMU')


# Start from the default choices (timer 0, 1 photo) and wait for "Reset" to clear.
click(QemuButton.Button.Up, hold=1)
time.sleep(6)

# Steps, in seconds from the start: show the time, timer 0 -> 3, photos 1 -> 3, shutter.
steps = [(2, QemuButton.Button.Up), (3, QemuButton.Button.Down), (4, QemuButton.Button.Select)]
length = 13  # Countdown, 3 photos and "Done" for a couple of seconds.
frames = []  # [image, seconds shown]
start = time.time()
tick = 0
while tick < length * FPS:
    t = start + tick / FPS
    time.sleep(max(0, t - time.time()))
    if steps and time.time() - start >= steps[0][0]:
        click(steps.pop(0)[1])
    image = grab()
    tick += 1
    if not set(image.getdata()) <= LIT_COLOURS:
        image = None  # The backlight was fading, so this frame is between two brightnesses.
    if frames and (image is None or image.tobytes() == frames[-1][0].tobytes()):
        frames[-1][1] += 1 / FPS
    elif image is not None:
        frames.append([image, 1 / FPS])

# Match `pebble screenshot`'s colours, and round times to half seconds so reruns give the same GIF.
images = []
for image, _ in frames:
    w, h = image.size
    data = image.tobytes()
    rows = [list(data[y * w * 3:(y + 1) * w * 3]) for y in range(h)]
    fixed = ScreenshotCommand._correct_colours(None, rows)
    images.append(Image.frombytes('RGB', (w, h), bytes(v for row in fixed for v in row)))
durations = [max(500, round(seconds * 2) * 500) for _, seconds in frames]
images[0].save(out, save_all=True, append_images=images[1:], duration=durations, loop=0)
print('{} screens, {:.1f} s: {}'.format(len(images), sum(durations) / 1000, durations))
os._exit(0)  # Don't wait for libpebble2's reader thread.
