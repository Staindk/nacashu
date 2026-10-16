#include <pebble.h>

// AppMessage keys and values. Must match ShutterProtocol.kt in the Android app.
#define KEY_CMD    1
#define KEY_STATUS 2
#define KEY_LEFTY  3
#define KEY_SOUNDS 4

#define CMD_SHUTTER     1
#define CMD_OPEN_CAMERA 2
#define CMD_QUICK_LAUNCH 3

#define STATUS_OK               0
#define STATUS_NO_ACCESSIBILITY 1
#define STATUS_CAMERA_NOT_OPEN  2
#define STATUS_SHUTTER_NOT_FOUND 3
#define STATUS_GESTURE_FALLBACK 4

// Timer delays cycled by holding UP. The chosen delay in seconds is saved on the watch.
// Key 1 held an index into an older list of options, so it's no longer read.
#define PERSIST_KEY_TIMER_SECONDS 3
static const int TIMER_OPTIONS[] = {1, 3, 5, 10, 30};
#define TIMER_OPTION_COUNT ((int)ARRAY_LENGTH(TIMER_OPTIONS))
#define TIMER_DEFAULT_INDEX 1

// Lefty layout, set in the phone app and sent when the watchapp opens or the setting changes.
// PebbleOS has its own Left-Handed setting (Settings > Display) that turns the screen and
// buttons round, but apps can't read it, so the labels move left to stay next to the buttons.
#define PERSIST_KEY_LEFTY 2

// Sounds on or off, set in the phone app the same way as lefty. On until the phone says otherwise.
#define PERSIST_KEY_SOUNDS 4

// Status messages are wiped after this long, so an old "Done" doesn't look like a new one.
#define STATUS_CLEAR_MS 5000

static Window *s_window;
static TextLayer *s_up_layer;
static Layer *s_picker_layer;
static Layer *s_shutter_layer;
static BitmapLayer *s_name_layer;
static GBitmap *s_name_down;
static GBitmap *s_name_up;
static TextLayer *s_down_layer;
static TextLayer *s_status_layer;
static TextLayer *s_time_layer;
static GRect s_status_box;
static AppTimer *s_countdown_timer;
static AppTimer *s_status_clear_timer;
static int s_countdown;
static int s_timer_index = TIMER_DEFAULT_INDEX;
static bool s_lefty;
static bool s_sounds = true;
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
  resource_load(handle, data, size);
  const SpeakerSample sample = {
    .data = data,
    .num_bytes = size,
    .format = SpeakerPcmFormat_16kHz_8bit,
    .base_midi_note = 60,
  };
  // 16 kHz, one byte per sample. The note just has to outlast the sample.
  const SpeakerNote note = {.midi_note = 60, .duration_ms = size / 16 + 1};
  const SpeakerTrack track = {.notes = &note, .num_notes = 1, .sample = &sample};
  speaker_stop();
  speaker_play_tracks(&track, 1, SOUND_VOLUME);
  free(data);  // The OS copies the sample when playback starts.
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

static void prv_send_cmd(uint8_t cmd) {
  if (!connection_service_peek_pebble_app_connection()) {
    prv_set_status("No phone");
    vibes_double_pulse();
    prv_play_error();
    return;
  }
  DictionaryIterator *iter;
  AppMessageResult result = app_message_outbox_begin(&iter);
  if (result != APP_MSG_OK) {
    prv_set_status("Busy");
    return;
  }
  dict_write_uint8(iter, KEY_CMD, cmd);
  app_message_outbox_send();
  prv_set_status(cmd == CMD_SHUTTER ? "Snap..." : "Opening...");
}

static void prv_countdown_tick(void *context) {
  s_countdown--;
  if (s_countdown <= 0) {
    s_countdown_timer = NULL;
    s_count_buf[0] = '\0';
    prv_send_cmd(CMD_SHUTTER);
    return;
  }
  // The countdown shows in the time's place, in its big font, until it ends.
  prv_cancel_status_clear();
  s_status_buf[0] = '\0';
  snprintf(s_count_buf, sizeof(s_count_buf), "%d", s_countdown);
  prv_show_status_text();
  vibes_short_pulse();
  prv_play_tick(s_countdown <= 2);
  s_countdown_timer = app_timer_register(1000, prv_countdown_tick, NULL);
}

static bool prv_cancel_countdown(void) {
  if (!s_countdown_timer) {
    return false;
  }
  app_timer_cancel(s_countdown_timer);
  s_countdown_timer = NULL;
  s_count_buf[0] = '\0';
  return true;
}

// All the delays in a row next to UP, with the chosen one bold on a white pill.
static void prv_picker_update(Layer *layer, GContext *ctx) {
  const GRect b = layer_get_bounds(layer);
  const GFont font = fonts_get_system_font(FONT_KEY_GOTHIC_18);
  const GFont bold = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
  const int16_t gap = b.size.w > 150 ? 9 : 6;
  const int16_t pad = 4;
  char text[TIMER_OPTION_COUNT][4];
  int16_t widths[TIMER_OPTION_COUNT];
  int16_t total = gap * (TIMER_OPTION_COUNT - 1);
  for (int i = 0; i < TIMER_OPTION_COUNT; i++) {
    snprintf(text[i], sizeof(text[i]), "%d", TIMER_OPTIONS[i]);
    const bool chosen = i == s_timer_index;
    widths[i] = graphics_text_layout_get_content_size(
        text[i], chosen ? bold : font, b, GTextOverflowModeFill, GTextAlignmentLeft).w;
    if (chosen) {
      widths[i] += 2 * pad;
    }
    total += widths[i];
  }

  int16_t x = s_lefty ? 0 : b.size.w - total;
  for (int i = 0; i < TIMER_OPTION_COUNT; i++) {
    const bool chosen = i == s_timer_index;
    GRect box = GRect(x, 0, widths[i], b.size.h);
    if (chosen) {
      graphics_context_set_fill_color(ctx, GColorWhite);
      graphics_fill_rect(ctx, box, 4, GCornersAll);
      graphics_context_set_text_color(ctx, GColorBlack);
    } else {
      graphics_context_set_text_color(ctx, GColorWhite);
    }
    box.origin.y -= 3;  // Gothic has extra space above the digits.
    graphics_draw_text(ctx, text[i], chosen ? bold : font, box, GTextOverflowModeFill,
                       GTextAlignmentCenter, NULL);
    x += widths[i] + gap;
  }
}

static void prv_select_click(ClickRecognizerRef recognizer, void *context) {
  prv_send_cmd(CMD_SHUTTER);
}

static void prv_up_click(ClickRecognizerRef recognizer, void *context) {
  if (prv_cancel_countdown()) {
    prv_set_status("Cancelled");
    return;
  }
  s_countdown = TIMER_OPTIONS[s_timer_index] + 1;
  prv_countdown_tick(NULL);
}

static void prv_up_long_click(ClickRecognizerRef recognizer, void *context) {
  prv_cancel_countdown();
  s_timer_index = (s_timer_index + 1) % TIMER_OPTION_COUNT;
  persist_write_int(PERSIST_KEY_TIMER_SECONDS, TIMER_OPTIONS[s_timer_index]);
  layer_mark_dirty(s_picker_layer);
  prv_set_status("Timer set");
  vibes_short_pulse();
}

static void prv_down_click(ClickRecognizerRef recognizer, void *context) {
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
  layer_mark_dirty(s_picker_layer);
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
}

static void prv_inbox_received(DictionaryIterator *iter, void *context) {
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

  Tuple *status = dict_find(iter, KEY_STATUS);
  if (!status) {
    return;
  }
  switch (status->value->uint8) {
    case STATUS_OK:
      prv_set_status("Done");
      vibes_short_pulse();
      prv_play_shutter();
      break;
    case STATUS_GESTURE_FALLBACK:
      prv_set_status("Done (tap)");
      vibes_short_pulse();
      prv_play_shutter();
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

static void prv_outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "Outbox failed: %d", (int)reason);
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
  s_picker_layer = layer_create(GRect(inset, top + 24, w, 22));
  layer_set_update_proc(s_picker_layer, prv_picker_update);
  layer_add_child(root, s_picker_layer);
  s_shutter_layer = layer_create(GRectZero);
  layer_set_update_proc(s_shutter_layer, prv_shutter_update);
  layer_add_child(root, s_shutter_layer);
  s_name_down = gbitmap_create_with_resource(RESOURCE_ID_NAME_DOWN);
  s_name_up = gbitmap_create_with_resource(RESOURCE_ID_NAME_UP);
  s_name_layer = bitmap_layer_create(GRectZero);
  layer_add_child(root, bitmap_layer_get_layer(s_name_layer));
  s_down_layer = prv_label(root, GRect(inset, b.size.h - PBL_IF_ROUND_ELSE(54, 32), w, 24),
                           "Open camera", GTextAlignmentRight, FONT_KEY_GOTHIC_18);
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
  layer_destroy(s_picker_layer);
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
  const int seconds = persist_read_int(PERSIST_KEY_TIMER_SECONDS);
  for (int i = 0; i < TIMER_OPTION_COUNT; i++) {
    if (TIMER_OPTIONS[i] == seconds) {
      s_timer_index = i;
    }
  }
  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_outbox_failed(prv_outbox_failed);
  app_message_open(64, 64);

  // Opened by holding a button on the watchface (Quick Launch). The phone opens the camera and
  // takes a photo if that's turned on in its settings, and ignores this if it's off.
  DictionaryIterator *iter;
  if (launch_reason() == APP_LAUNCH_QUICK_LAUNCH && connection_service_peek_pebble_app_connection() &&
      app_message_outbox_begin(&iter) == APP_MSG_OK) {
    dict_write_uint8(iter, KEY_CMD, CMD_QUICK_LAUNCH);
    app_message_outbox_send();
  }

  s_window = window_create();
  window_set_click_config_provider(s_window, prv_click_config);
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  window_stack_push(s_window, true);
}

static void prv_deinit(void) {
  tick_timer_service_unsubscribe();
  prv_cancel_countdown();
  prv_cancel_status_clear();
  window_destroy(s_window);
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
