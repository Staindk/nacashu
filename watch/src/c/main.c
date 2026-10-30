#include <pebble.h>

// AppMessage keys and values. Must match ShutterProtocol.kt in the Android app.
#define KEY_CMD    1
#define KEY_STATUS 2
#define KEY_LEFTY  3
#define KEY_SOUNDS 4
#define KEY_REMEMBER 5
// Temporary debug timings for issue 35. Watch to phone with each photo, except KEY_FAST_BT.
#define KEY_SEQ      6   // Photo number in the run, from 1. The phone echoes it with the status.
#define KEY_T_SENT   7   // ms from the SELECT press to sending this photo.
#define KEY_RETRIES  8   // Busy retries before this photo went out.
#define KEY_PREV_RTT 9   // ms from sending the previous photo to its status arriving.
#define KEY_PREV_ACK 10  // ms from sending the previous photo to the outbox being acked.
#define KEY_FAST_BT  11  // Watch to phone: keep-awake pings were on. Phone to watch: allow them.

#define CMD_SHUTTER     1
#define CMD_OPEN_CAMERA 2
#define CMD_QUICK_LAUNCH 3
#define CMD_RUN_END     4  // Debug: the last photo's round trip, after a run.
#define CMD_PING        5  // Keeps Bluetooth quick; the phone ignores it.

#define STATUS_OK               0
#define STATUS_NO_ACCESSIBILITY 1
#define STATUS_CAMERA_NOT_OPEN  2
#define STATUS_SHUTTER_NOT_FOUND 3
#define STATUS_GESTURE_FALLBACK 4

// Timer delays cycled by pressing UP, in seconds. 0 takes the photo straight away.
// Key 1 held an index into an older list of options, so it's no longer read.
#define PERSIST_KEY_TIMER_SECONDS 3
static const int TIMER_OPTIONS[] = {0, 3, 5, 10, 30};
#define TIMER_OPTION_COUNT ((int)ARRAY_LENGTH(TIMER_OPTIONS))

// How many photos SELECT takes, cycled by pressing DOWN. 0 means no limit: it keeps going until
// a button is pressed (or BURST_MAX photos).
#define PERSIST_KEY_PHOTOS 5
static const int PHOTO_OPTIONS[] = {1, 3, 5, 10, 0};
#define PHOTO_OPTION_COUNT ((int)ARRAY_LENGTH(PHOTO_OPTIONS))
#define BURST_MAX 999
// The next photo is sent this long after the phone says it took the last one. Timing from the
// phone's answer, not from sending, keeps the gaps even when Bluetooth is slow to wake up.
#define SHOT_GAP_MS 1000
// Stop the run if the phone hasn't answered a photo by then.
#define ANSWER_TIMEOUT_MS 5000
// When the last photo's message hasn't left the watch yet, try again this often, this many times.
#define BUSY_RETRY_MS 100
#define BUSY_RETRIES 5

// Keep the timer and photo count for next time. Set in the phone app and sent like lefty.
#define PERSIST_KEY_REMEMBER 6

// Lefty layout, set in the phone app and sent when the watchapp opens or the setting changes.
// PebbleOS has its own Left-Handed setting (Settings > Display) that turns the screen and
// buttons round, but apps can't read it, so the labels move left to stay next to the buttons.
#define PERSIST_KEY_LEFTY 2

// Sounds on or off, set in the phone app the same way as lefty. Defaults to on.
#define PERSIST_KEY_SOUNDS 4

// Status messages are wiped after this long, so an old "Done" doesn't look like a new one.
#define STATUS_CLEAR_MS 5000

static Window *s_window;
static TextLayer *s_up_layer;
static Layer *s_timer_picker;
static Layer *s_photo_picker;
static Layer *s_shutter_layer;
static BitmapLayer *s_name_layer;
static GBitmap *s_name_down;
static GBitmap *s_name_up;
static TextLayer *s_down_layer;
static TextLayer *s_status_layer;
static TextLayer *s_time_layer;
static GRect s_status_box;
static AppTimer *s_countdown_timer;
static AppTimer *s_burst_timer;
static AppTimer *s_status_clear_timer;
static int s_countdown;
static int s_timer_index;
static int s_photo_index;
static int s_shots;
static int s_busy_retries;
// True from sending a photo in a run until the phone answers. The next photo is only sent after
// that answer.
static bool s_waiting;
static bool s_lefty;
static bool s_sounds = true;
static bool s_remember = true;
// The last command sent, so a "Done" for Open camera doesn't play the shutter.
static uint8_t s_last_cmd;
// Debug timings (issue 35), in ms from time_ms().
static uint32_t s_press_ms;
static uint32_t s_sent_ms;
static uint32_t s_prev_rtt;
static uint32_t s_prev_ack;
static bool s_fast_bt_allowed = true;
static bool s_fast_bt_on;
static AppTimer *s_fast_bt_timer;
static uint32_t s_awake_until;
static uint32_t s_last_msg_ms;
static bool s_ping_out;
static char s_status_buf[32];
static char s_time_buf[8];
static char s_count_buf[4];
// Fonts draw with space above the glyphs. These move the text up so its middle lines up with the
// shutter's; the values were measured on emulator screenshots.
static int16_t s_status_nudge;
static int16_t s_time_nudge;

// Centres a text layer on the shutter, inside the status box.
static void prv_centre_text(TextLayer *text, int16_t nudge) {
  Layer *layer = text_layer_get_layer(text);
  layer_set_frame(layer, s_status_box);
  const int16_t h = text_layer_get_content_size(text).h;
  GRect frame = s_status_box;
  frame.origin.y += (frame.size.h - h) / 2 + nudge;
  frame.size.h = h + 8;
  layer_set_frame(layer, frame);
}

// The status sits beside the shutter and wraps to two lines when it's long. When there's no
// status, the same spot shows the countdown, or else the time.
static void prv_show_status_text(void) {
  text_layer_set_text(s_status_layer, s_status_buf);
  prv_centre_text(s_status_layer, s_status_nudge);
  text_layer_set_text(s_time_layer, s_count_buf[0] ? s_count_buf : s_time_buf);
  prv_centre_text(s_time_layer, s_time_nudge);
  layer_set_hidden(text_layer_get_layer(s_time_layer), s_status_buf[0] != '\0');
}

static void prv_update_time(void) {
  const time_t now = time(NULL);
  strftime(s_time_buf, sizeof(s_time_buf), clock_is_24h_style() ? "%H:%M" : "%I:%M",
           localtime(&now));
  if (!clock_is_24h_style() && s_time_buf[0] == '0') {
    memmove(s_time_buf, s_time_buf + 1, sizeof(s_time_buf) - 1);  // 9:41, not 09:41.
  }
  prv_show_status_text();
}

static void prv_tick(struct tm *tick_time, TimeUnits units_changed) {
  prv_update_time();
}

// Sounds play through the speaker on watches that have one (not the Round 2). The volume is a
// share of the watch's own speaker volume, and the watch's mute and Quiet Time silence them.
#if defined(PBL_SPEAKER)
#define SOUND_VOLUME 100

// Countdown: a short A5 blip each second, and a higher E6 for the last two.
static const SpeakerNote TICK_NOTES[] = {
  {.midi_note = 81, .waveform = SpeakerWaveformSine, .duration_ms = 50},
};
static const SpeakerNote CLOSE_NOTES[] = {
  {.midi_note = 88, .waveform = SpeakerWaveformSine, .duration_ms = 50},
};
// Error: two falling square-wave notes, E5 then A4.
static const SpeakerNote ERROR_NOTES[] = {
  {.midi_note = 76, .waveform = SpeakerWaveformSquare, .duration_ms = 120},
  {.midi_note = 0, .duration_ms = 30},
  {.midi_note = 69, .waveform = SpeakerWaveformSquare, .duration_ms = 200},
};

static void prv_play_notes(const SpeakerNote *notes, uint32_t count) {
  if (!s_sounds) {
    return;
  }
  speaker_stop();  // An app's sound can't cut off its own, so the newest one wins this way.
  speaker_play_notes(notes, count, SOUND_VOLUME);
}

// The shutter is a short PCM click made by tools/make_shutter_sound.py, played once at its own pitch.
static void prv_play_shutter(void) {
  if (!s_sounds) {
    return;
  }
  const ResHandle handle = resource_get_handle(RESOURCE_ID_SHUTTER_SOUND);
  const size_t size = resource_size(handle);
  uint8_t *data = malloc(size);
  if (!data) {
    return;
  }
  const size_t loaded = resource_load(handle, data, size);
  if (loaded == 0) {
    free(data);
    return;
  }
  const SpeakerSample sample = {
    .data = data,
    .num_bytes = loaded,
    .format = SpeakerPcmFormat_16kHz_8bit,
    .base_midi_note = 60,
  };
  // 16 kHz, one byte per sample. The note just has to outlast the sample.
  const SpeakerNote note = {.midi_note = 60, .duration_ms = loaded / 16 + 1};
  const SpeakerTrack track = {.notes = &note, .num_notes = 1, .sample = &sample};
  speaker_stop();
  speaker_play_tracks(&track, 1, SOUND_VOLUME);
  free(data);  // The OS copies the track, note and sample data before this returns.
}

static void prv_play_tick(bool close) {
  prv_play_notes(close ? CLOSE_NOTES : TICK_NOTES, 1);
}

static void prv_play_error(void) {
  prv_play_notes(ERROR_NOTES, ARRAY_LENGTH(ERROR_NOTES));
}
#else
static void prv_play_shutter(void) {}
static void prv_play_tick(bool close) {}
static void prv_play_error(void) {}
#endif

// Quick Launch only answers when the phone took a photo.
static void prv_play_shutter_if_photo(void) {
  if (s_last_cmd == CMD_SHUTTER || s_last_cmd == CMD_QUICK_LAUNCH) {
    prv_play_shutter();
  }
}

static void prv_cancel_status_clear(void) {
  if (s_status_clear_timer) {
    app_timer_cancel(s_status_clear_timer);
    s_status_clear_timer = NULL;
  }
}

static void prv_clear_status(void *context) {
  s_status_clear_timer = NULL;
  s_status_buf[0] = '\0';
  prv_show_status_text();
}

static void prv_set_status(const char *text) {
  snprintf(s_status_buf, sizeof(s_status_buf), "%s", text);
  prv_show_status_text();
  prv_cancel_status_clear();
  s_status_clear_timer = app_timer_register(STATUS_CLEAR_MS, prv_clear_status, NULL);
}

static uint32_t prv_now_ms(void) {
  time_t s;
  uint16_t ms;
  time_ms(&s, &ms);
  return (uint32_t)s * 1000 + ms;
}

typedef enum { SEND_OK, SEND_BUSY, SEND_NO_PHONE } SendResult;

static SendResult prv_try_send(uint8_t cmd) {
  if (!connection_service_peek_pebble_app_connection()) {
    return SEND_NO_PHONE;
  }
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return SEND_BUSY;
  }
  dict_write_uint8(iter, KEY_CMD, cmd);
  if (cmd == CMD_SHUTTER) {
    // Debug timings (issue 35).
    const uint32_t now = prv_now_ms();
    dict_write_uint16(iter, KEY_SEQ, s_shots + 1);
    dict_write_uint32(iter, KEY_T_SENT, now - s_press_ms);
    dict_write_uint8(iter, KEY_RETRIES, s_busy_retries);
    dict_write_uint32(iter, KEY_PREV_RTT, s_shots ? s_prev_rtt : 0);
    dict_write_uint32(iter, KEY_PREV_ACK, s_shots ? s_prev_ack : 0);
    dict_write_uint8(iter, KEY_FAST_BT, s_fast_bt_on);
    s_sent_ms = now;
  }
  s_last_cmd = cmd;
  s_last_msg_ms = prv_now_ms();
  app_message_outbox_send();
  return SEND_OK;
}

// Debug (issue 35): after a run, send the last photo's round trip, which no later photo carries.
static void prv_send_run_end(void) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }
  dict_write_uint8(iter, KEY_CMD, CMD_RUN_END);
  dict_write_uint32(iter, KEY_PREV_RTT, s_prev_rtt);
  dict_write_uint32(iter, KEY_PREV_ACK, s_prev_ack);
  app_message_outbox_send();
}

static void prv_send_failed(SendResult result) {
  if (result == SEND_NO_PHONE) {
    prv_set_status("No phone");
    vibes_double_pulse();
  } else {
    prv_set_status("Busy");  // When the timer ends here, no photo is taken.
  }
  prv_play_error();
}

static void prv_send_cmd(uint8_t cmd) {
  const SendResult result = prv_try_send(cmd);
  if (result != SEND_OK) {
    prv_send_failed(result);
    return;
  }
  prv_set_status(cmd == CMD_SHUTTER ? "Snap..." : "Opening camera");
}

// Keeping Bluetooth quick. Normally the watch only listens for the phone about every 1.4 s
// (30 skipped connection events of up to 45 ms), so the phone's answer to a first photo could
// take that long. PebbleOS listens on every event, every 15 ms, for 10 s after any AppMessage,
// so while Nacashu is in use it pings the phone whenever nothing else has been sent for a while.
// app_comm_set_sniff_interval() doesn't help: on BLE its "reduced" mode uses the normal settings.
// This costs some battery, so it stops after AWAKE_IDLE_MS without a button press, but never
// during a countdown or a run of photos.
#define AWAKE_IDLE_MS (5 * 60 * 1000)
#define KEEPALIVE_CHECK_MS 2000
#define KEEPALIVE_AFTER_MS 7000

static void prv_ping(void) {
  DictionaryIterator *iter;
  if (!connection_service_peek_pebble_app_connection() ||
      app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;  // Something else is being sent, which does the same job.
  }
  dict_write_uint8(iter, KEY_CMD, CMD_PING);
  s_ping_out = true;
  s_last_msg_ms = prv_now_ms();
  app_message_outbox_send();
}

static void prv_keepalive(void *context) {
  s_fast_bt_timer = NULL;
  const uint32_t now = prv_now_ms();
  const bool busy = s_countdown_timer || s_burst_timer;
  if (!s_fast_bt_allowed || (!busy && (int32_t)(now - s_awake_until) > 0)) {
    s_fast_bt_on = false;
    return;
  }
  s_fast_bt_on = true;
  if (now - s_last_msg_ms >= KEEPALIVE_AFTER_MS) {
    prv_ping();
  }
  s_fast_bt_timer = app_timer_register(KEEPALIVE_CHECK_MS, prv_keepalive, NULL);
}

// Called when Nacashu opens and on every button press.
static void prv_keep_fast_bluetooth(void) {
  s_awake_until = prv_now_ms() + AWAKE_IDLE_MS;
  if (!s_fast_bt_timer) {
    prv_keepalive(NULL);
  }
}

static void prv_no_answer(void *context) {
  s_burst_timer = NULL;
  s_waiting = false;
  prv_set_status("No answer");
  vibes_double_pulse();
  prv_play_error();
}

// Sends one photo of a run. When the phone answers that it took it, prv_inbox_received sends the
// next one a second later. An error answer stops the run.
static void prv_shot(void *context) {
  s_burst_timer = NULL;
  const SendResult result = prv_try_send(CMD_SHUTTER);
  if (result == SEND_BUSY && s_busy_retries < BUSY_RETRIES) {
    s_busy_retries++;
    s_burst_timer = app_timer_register(BUSY_RETRY_MS, prv_shot, NULL);
    return;
  }
  if (result != SEND_OK) {
      prv_send_failed(result);
    return;
  }
  s_busy_retries = 0;
  s_shots++;

  const int total = PHOTO_OPTIONS[s_photo_index];
  char text[24];
  if (total == 1) {
    snprintf(text, sizeof(text), "Snap...");
  } else if (total == 0) {
    snprintf(text, sizeof(text), "%d", s_shots);
  } else {
    snprintf(text, sizeof(text), "%d/%d", s_shots, total);
  }
  prv_set_status(text);

  if (s_shots < (total == 0 ? BURST_MAX : total)) {
    s_waiting = true;
    s_burst_timer = app_timer_register(ANSWER_TIMEOUT_MS, prv_no_answer, NULL);
  }
}

static void prv_start_burst(void) {
  s_shots = 0;
  s_busy_retries = 0;
  prv_keep_fast_bluetooth();
  prv_shot(NULL);
}

static void prv_countdown_tick(void *context) {
  s_countdown--;
  if (s_countdown <= 0) {
    s_countdown_timer = NULL;
    s_count_buf[0] = '\0';
    prv_start_burst();
    return;
  }
  // The countdown shows in the time's place, in its big font, until it ends.
  prv_cancel_status_clear();
  s_status_buf[0] = '\0';
  snprintf(s_count_buf, sizeof(s_count_buf), "%d", s_countdown);
  prv_show_status_text();
  vibes_short_pulse();
  if (s_countdown <= 10) {  // A 30 second timer only ticks for the last 10.
    prv_play_tick(s_countdown <= 2);
  }
  s_countdown_timer = app_timer_register(1000, prv_countdown_tick, NULL);
}

// Stops a countdown or a run of photos. Returns false if neither was going.
static bool prv_stop(void) {
  bool stopped = false;
  if (s_countdown_timer) {
    app_timer_cancel(s_countdown_timer);
    s_countdown_timer = NULL;
    s_count_buf[0] = '\0';
    stopped = true;
  }
  if (s_burst_timer) {
    app_timer_cancel(s_burst_timer);
    s_burst_timer = NULL;
    stopped = true;
  }
  s_waiting = false;
  return stopped;
}

// A row of choices next to UP or DOWN, with the chosen one bold on a white pill.
typedef struct {
  const int *options;
  int count;
  int *index;
  bool zero_is_endless;  // Draw 0 as an infinity sign.
} Picker;

// Gothic 24's own infinity sign, which sits well beside Gothic 18's digits, and a bold copy for
// the chosen one (Gothic 24 Bold has none). Gothic 18's own is too small next to them.
static const char *const INFINITY[] = {
  " XXXX   XXXX ",
  "XX  XX XX  XX",
  "X    XXX    X",
  "X     X     X",
  "X    XXX    X",
  "XX  XX XX  XX",
  " XXXX   XXXX ",
};
static const char *const INFINITY_BOLD[] = {
  " XXXX    XXXX ",
  "XXXXXX  XXXXXX",
  "XX  XXXXXX  XX",
  "XX   XXXX   XX",
  "XX  XXXXXX  XX",
  "XXXXXX  XXXXXX",
  " XXXX    XXXX ",
};
#define INFINITY_H ((int)ARRAY_LENGTH(INFINITY))

static int16_t prv_infinity_w(bool bold) {
  return strlen(bold ? INFINITY_BOLD[0] : INFINITY[0]);
}

static void prv_draw_infinity(GContext *ctx, GRect box, GColor color, bool bold) {
  const char *const *rows = bold ? INFINITY_BOLD : INFINITY;
  const int16_t x0 = box.origin.x + (box.size.w - prv_infinity_w(bold)) / 2;
  const int16_t y0 = box.origin.y + (box.size.h - INFINITY_H) / 2;
  graphics_context_set_stroke_color(ctx, color);
  for (int y = 0; y < INFINITY_H; y++) {
    for (int x = 0; rows[y][x]; x++) {
      if (rows[y][x] == 'X') {
        graphics_draw_pixel(ctx, GPoint(x0 + x, y0 + y));
      }
    }
  }
}

static void prv_picker_update(Layer *layer, GContext *ctx) {
  const Picker *picker = layer_get_data(layer);
  const GRect b = layer_get_bounds(layer);
  const GFont font = fonts_get_system_font(FONT_KEY_GOTHIC_18);
  const GFont bold = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
  const int16_t gap = b.size.w > 150 ? 9 : 6;
  const int16_t pad = 4;
  char text[TIMER_OPTION_COUNT > PHOTO_OPTION_COUNT ? TIMER_OPTION_COUNT : PHOTO_OPTION_COUNT][4];
  int16_t widths[ARRAY_LENGTH(text)];
  int16_t total = gap * (picker->count - 1);
  for (int i = 0; i < picker->count; i++) {
    const bool chosen = i == *picker->index;
    if (picker->zero_is_endless && picker->options[i] == 0) {
      text[i][0] = '\0';
      widths[i] = prv_infinity_w(chosen);
    } else {
      snprintf(text[i], sizeof(text[i]), "%d", picker->options[i]);
      widths[i] = graphics_text_layout_get_content_size(
          text[i], chosen ? bold : font, b, GTextOverflowModeFill, GTextAlignmentLeft).w;
    }
    if (chosen) {
      widths[i] += 2 * pad;
    }
    total += widths[i];
  }

  graphics_context_set_antialiased(ctx, true);
  int16_t x = s_lefty ? 0 : b.size.w - total;
  for (int i = 0; i < picker->count; i++) {
    const bool chosen = i == *picker->index;
    GRect box = GRect(x, 0, widths[i], b.size.h);
    const GColor color = chosen ? GColorBlack : GColorWhite;
    if (chosen) {
      graphics_context_set_fill_color(ctx, GColorWhite);
      graphics_fill_rect(ctx, box, 4, GCornersAll);
    }
    if (text[i][0] == '\0') {
      prv_draw_infinity(ctx, box, color, chosen);
    } else {
      graphics_context_set_text_color(ctx, color);
      box.origin.y -= 3;  // Gothic has extra space above the digits.
      graphics_draw_text(ctx, text[i], chosen ? bold : font, box, GTextOverflowModeFill,
                         GTextAlignmentCenter, NULL);
    }
    x += widths[i] + gap;
  }
}

static Layer *prv_picker_create(Layer *parent, GRect frame, const int *options, int count,
                                int *index, bool zero_is_endless) {
  Layer *layer = layer_create_with_data(frame, sizeof(Picker));
  *(Picker *)layer_get_data(layer) = (Picker) {
    .options = options,
    .count = count,
    .index = index,
    .zero_is_endless = zero_is_endless,
  };
  layer_set_update_proc(layer, prv_picker_update);
  layer_add_child(parent, layer);
  return layer;
}

static void prv_save_choices(void) {
  if (s_remember) {
    persist_write_int(PERSIST_KEY_TIMER_SECONDS, TIMER_OPTIONS[s_timer_index]);
    persist_write_int(PERSIST_KEY_PHOTOS, PHOTO_OPTIONS[s_photo_index]);
  }
}

static int prv_find_option(const int *options, int count, int value) {
  for (int i = 0; i < count; i++) {
    if (options[i] == value) {
      return i;
    }
  }
  return 0;
}

// Pressed again while a countdown or run of photos is going, SELECT stops it. It has no hold
// action, so it fires as soon as it's pressed.
static void prv_select_click(ClickRecognizerRef recognizer, void *context) {
  prv_keep_fast_bluetooth();
  if (prv_stop()) {
    prv_set_status("Stopped");
    return;
  }
  s_press_ms = prv_now_ms();
  const int seconds = TIMER_OPTIONS[s_timer_index];
  if (seconds == 0) {
    prv_start_burst();
    return;
  }
  s_countdown = seconds + 1;
  prv_countdown_tick(NULL);
}

// UP and DOWN stop whatever is going first, so a press can always stop it.
static void prv_up_click(ClickRecognizerRef recognizer, void *context) {
  prv_keep_fast_bluetooth();
  if (prv_stop()) {
    prv_set_status("Stopped");
    return;
  }
  s_timer_index = (s_timer_index + 1) % TIMER_OPTION_COUNT;
  prv_save_choices();
  layer_mark_dirty(s_timer_picker);
}

static void prv_up_long_click(ClickRecognizerRef recognizer, void *context) {
  prv_keep_fast_bluetooth();
  prv_stop();
  s_timer_index = 0;
  s_photo_index = 0;
  prv_save_choices();
  layer_mark_dirty(s_timer_picker);
  layer_mark_dirty(s_photo_picker);
  prv_set_status("Reset");
  vibes_short_pulse();
}

static void prv_down_click(ClickRecognizerRef recognizer, void *context) {
  prv_keep_fast_bluetooth();
  if (prv_stop()) {
    prv_set_status("Stopped");
    return;
  }
  s_photo_index = (s_photo_index + 1) % PHOTO_OPTION_COUNT;
  prv_save_choices();
  layer_mark_dirty(s_photo_picker);
}

static void prv_down_long_click(ClickRecognizerRef recognizer, void *context) {
  prv_keep_fast_bluetooth();
  prv_stop();
  prv_send_cmd(CMD_OPEN_CAMERA);
}

// Pixel Camera style shutter: a white disc inside a white ring.
static void prv_shutter_update(Layer *layer, GContext *ctx) {
  const int16_t r = layer_get_bounds(layer).size.w / 2;
  graphics_context_set_antialiased(ctx, true);
  graphics_context_set_stroke_color(ctx, GColorWhite);
  graphics_context_set_stroke_width(ctx, 3);
  graphics_draw_circle(ctx, GPoint(r, r), r - 2);
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_circle(ctx, GPoint(r, r), r - 6);
}

// Buttons and their labels on one side, the app name along the other edge.
static void prv_apply_lefty(void) {
  const GTextAlignment align = s_lefty ? GTextAlignmentLeft : GTextAlignmentRight;
  text_layer_set_text_alignment(s_up_layer, align);
  layer_mark_dirty(s_timer_picker);
  layer_mark_dirty(s_photo_picker);
  text_layer_set_text_alignment(s_down_layer, align);

  const GRect b = layer_get_bounds(window_get_root_layer(s_window));
  const int16_t mid = b.size.h / 2;
  // The middle row is the widest part of a round screen, so the shutter can hug the edge there.
  const int16_t edge = PBL_IF_ROUND_ELSE(10, 4);
  const int16_t d = b.size.w > 144 ? 40 : 32;
  const int16_t shutter_x = s_lefty ? edge : b.size.w - edge - d;
  layer_set_frame(s_shutter_layer, GRect(shutter_x, mid - d / 2, d, d));

  GBitmap *name = s_lefty ? s_name_up : s_name_down;
  const GSize size = gbitmap_get_bounds(name).size;
  // The name is a full-height white spine on the edge; on round screens the curve trims it.
  bitmap_layer_set_bitmap(s_name_layer, name);
  layer_set_frame(bitmap_layer_get_layer(s_name_layer),
                  GRect(s_lefty ? b.size.w - size.w : 0, (b.size.h - size.h) / 2, size.w, size.h));

  // Status goes in the gap between the name and the shutter.
  const int16_t gap = 4;
  const int16_t box_w = b.size.w - edge - size.w - d - 2 * gap;
  const int16_t box_x = s_lefty ? edge + d + gap : size.w + gap;
  s_status_box = GRect(box_x, mid - 30, box_w, 60);
  prv_show_status_text();
}

static void prv_click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click);
  window_single_click_subscribe(BUTTON_ID_UP, prv_up_click);
  window_long_click_subscribe(BUTTON_ID_UP, 0, prv_up_long_click, NULL);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_down_click);
  window_long_click_subscribe(BUTTON_ID_DOWN, 0, prv_down_long_click, NULL);
}

static void prv_inbox_received(DictionaryIterator *iter, void *context) {
  s_last_msg_ms = prv_now_ms();  // Receiving keeps Bluetooth quick too.
  Tuple *lefty = dict_find(iter, KEY_LEFTY);
  if (lefty && (lefty->value->uint8 != 0) != s_lefty) {
    s_lefty = lefty->value->uint8 != 0;
    persist_write_bool(PERSIST_KEY_LEFTY, s_lefty);
    prv_apply_lefty();
  }

  Tuple *sounds = dict_find(iter, KEY_SOUNDS);
  if (sounds) {
    s_sounds = sounds->value->uint8 != 0;
    persist_write_bool(PERSIST_KEY_SOUNDS, s_sounds);
  }

  Tuple *remember = dict_find(iter, KEY_REMEMBER);
  if (remember) {
    s_remember = remember->value->uint8 != 0;
    persist_write_bool(PERSIST_KEY_REMEMBER, s_remember);
    if (s_remember) {
      prv_save_choices();
    } else {
      persist_delete(PERSIST_KEY_TIMER_SECONDS);
      persist_delete(PERSIST_KEY_PHOTOS);
    }
  }

  Tuple *fast_bt = dict_find(iter, KEY_FAST_BT);
  if (fast_bt) {
    s_fast_bt_allowed = fast_bt->value->uint8 != 0;
    if (s_fast_bt_allowed) {
      prv_keep_fast_bluetooth();
    }
  }

  Tuple *status = dict_find(iter, KEY_STATUS);
  if (!status) {
    return;
  }
  if (s_last_cmd == CMD_SHUTTER) {
    s_prev_rtt = prv_now_ms() - s_sent_ms;  // Debug timings (issue 35).
  }
  if (status->value->uint8 != STATUS_OK && status->value->uint8 != STATUS_GESTURE_FALLBACK) {
    prv_stop();  // No point taking the rest of the photos.
  }
  switch (status->value->uint8) {
    case STATUS_OK:
    case STATUS_GESTURE_FALLBACK:
      // While photos are still being taken, the counter stays up instead of "Done".
      if (s_waiting) {
        s_waiting = false;
        app_timer_cancel(s_burst_timer);
        s_burst_timer = app_timer_register(SHOT_GAP_MS, prv_shot, NULL);
      } else if (!s_burst_timer) {
              prv_set_status(status->value->uint8 == STATUS_OK ? "Done" : "Done (tap)");
        if (s_last_cmd == CMD_SHUTTER) {
          prv_send_run_end();
        }
      }
      vibes_short_pulse();
      prv_play_shutter_if_photo();
      break;
    case STATUS_NO_ACCESSIBILITY:
      prv_set_status("Enable service");
      vibes_double_pulse();
      prv_play_error();
      break;
    case STATUS_CAMERA_NOT_OPEN:
      prv_set_status("Camera closed");
      vibes_double_pulse();
      prv_play_error();
      break;
    case STATUS_SHUTTER_NOT_FOUND:
      prv_set_status("No shutter");
      vibes_double_pulse();
      prv_play_error();
      break;
    default:
      prv_set_status("Error");
      vibes_double_pulse();
      prv_play_error();
      break;
  }
}

// Debug timings (issue 35): when the phone side acked the photo's message.
static void prv_outbox_sent(DictionaryIterator *iter, void *context) {
  if (s_ping_out) {
    s_ping_out = false;
    return;
  }
  if (s_last_cmd == CMD_SHUTTER) {
    s_prev_ack = prv_now_ms() - s_sent_ms;
  }
}

static void prv_outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "Outbox failed: %d", (int)reason);
  if (s_ping_out) {
    s_ping_out = false;
    return;  // A lost ping doesn't matter.
  }
  prv_stop();
  prv_set_status("Phone app?");
  vibes_double_pulse();
  prv_play_error();
}

static TextLayer *prv_label(Layer *parent, GRect frame, const char *text, GTextAlignment align,
                            const char *font) {
  TextLayer *layer = text_layer_create(frame);
  text_layer_set_text(layer, text);
  text_layer_set_text_alignment(layer, align);
  text_layer_set_background_color(layer, GColorClear);
  text_layer_set_text_color(layer, GColorWhite);
  text_layer_set_font(layer, fonts_get_system_font(font));
  layer_add_child(parent, text_layer_get_layer(layer));
  return layer;
}

static void prv_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect b = layer_get_bounds(root);
  const int16_t inset = PBL_IF_ROUND_ELSE(b.size.w / 6, 4);
  const int16_t w = b.size.w - 2 * inset;

  window_set_background_color(window, GColorBlack);
  const int16_t top = PBL_IF_ROUND_ELSE(30, 6);
  s_up_layer = prv_label(root, GRect(inset, top, w, 24), "Timer (sec)",
                         GTextAlignmentRight, FONT_KEY_GOTHIC_18);
  s_timer_picker = prv_picker_create(root, GRect(inset, top + 24, w, 22), TIMER_OPTIONS,
                                     TIMER_OPTION_COUNT, &s_timer_index, false);
  s_shutter_layer = layer_create(GRectZero);
  layer_set_update_proc(s_shutter_layer, prv_shutter_update);
  layer_add_child(root, s_shutter_layer);
  s_name_down = gbitmap_create_with_resource(RESOURCE_ID_NAME_DOWN);
  s_name_up = gbitmap_create_with_resource(RESOURCE_ID_NAME_UP);
  s_name_layer = bitmap_layer_create(GRectZero);
  layer_add_child(root, bitmap_layer_get_layer(s_name_layer));
  // The bottom mirrors the top: the choices nearer the middle, the label at the edge.
  const int16_t bottom = b.size.h - PBL_IF_ROUND_ELSE(54, 32);
  s_photo_picker = prv_picker_create(root, GRect(inset, bottom - 22, w, 22), PHOTO_OPTIONS,
                                     PHOTO_OPTION_COUNT, &s_photo_index, true);
  s_down_layer = prv_label(root, GRect(inset, bottom, w, 24), "Photos",
                           GTextAlignmentRight, FONT_KEY_GOTHIC_18);
  // The smallest screen needs smaller fonts to fit between the name and the shutter.
  const bool big = b.size.w > 144;
  s_status_layer = prv_label(root, GRectZero, "", GTextAlignmentCenter,
                             big ? FONT_KEY_GOTHIC_28_BOLD : FONT_KEY_GOTHIC_24_BOLD);
  text_layer_set_overflow_mode(s_status_layer, GTextOverflowModeWordWrap);
  s_status_nudge = -5;
  s_time_layer = prv_label(root, GRectZero, "", GTextAlignmentCenter,
                           big ? FONT_KEY_LECO_38_BOLD_NUMBERS : FONT_KEY_LECO_26_BOLD_NUMBERS_AM_PM);
  s_time_nudge = big ? -6 : -4;
  prv_apply_lefty();
  prv_update_time();
  tick_timer_service_subscribe(MINUTE_UNIT, prv_tick);
}

static void prv_window_unload(Window *window) {
  text_layer_destroy(s_up_layer);
  layer_destroy(s_timer_picker);
  layer_destroy(s_photo_picker);
  layer_destroy(s_shutter_layer);
  bitmap_layer_destroy(s_name_layer);
  gbitmap_destroy(s_name_down);
  gbitmap_destroy(s_name_up);
  text_layer_destroy(s_down_layer);
  text_layer_destroy(s_status_layer);
  text_layer_destroy(s_time_layer);
}

static void prv_init(void) {
  s_lefty = persist_read_bool(PERSIST_KEY_LEFTY);
  if (persist_exists(PERSIST_KEY_SOUNDS)) {
    s_sounds = persist_read_bool(PERSIST_KEY_SOUNDS);
  }
  if (persist_exists(PERSIST_KEY_REMEMBER)) {
    s_remember = persist_read_bool(PERSIST_KEY_REMEMBER);
  }
  if (s_remember) {
    s_timer_index = prv_find_option(TIMER_OPTIONS, TIMER_OPTION_COUNT,
                                    persist_read_int(PERSIST_KEY_TIMER_SECONDS));
    s_photo_index = prv_find_option(PHOTO_OPTIONS, PHOTO_OPTION_COUNT,
                                    persist_exists(PERSIST_KEY_PHOTOS)
                                        ? persist_read_int(PERSIST_KEY_PHOTOS) : 1);
  }
  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_outbox_failed(prv_outbox_failed);
  app_message_register_outbox_sent(prv_outbox_sent);
  app_message_open(64, 128);  // The debug timings need the bigger outbox.

  // Opened by holding a button on the watchface (Quick Launch). The phone opens the camera and
  // takes a photo if that's turned on in its settings, and ignores this if it's off.
  DictionaryIterator *iter;
  if (launch_reason() == APP_LAUNCH_QUICK_LAUNCH && connection_service_peek_pebble_app_connection() &&
      app_message_outbox_begin(&iter) == APP_MSG_OK) {
    dict_write_uint8(iter, KEY_CMD, CMD_QUICK_LAUNCH);
    s_last_cmd = CMD_QUICK_LAUNCH;
    app_message_outbox_send();
  }

  s_window = window_create();
  window_set_click_config_provider(s_window, prv_click_config);
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  window_stack_push(s_window, true);
  prv_keep_fast_bluetooth();
}

static void prv_deinit(void) {
  if (s_fast_bt_timer) {
    app_timer_cancel(s_fast_bt_timer);
  }
  tick_timer_service_unsubscribe();
  prv_stop();
  prv_cancel_status_clear();
  window_destroy(s_window);
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
