#include <pebble.h>

// AppMessage keys and values. Must match ShutterProtocol.kt in the Android app.
#define KEY_CMD    1
#define KEY_STATUS 2
#define KEY_LEFTY  3

#define CMD_SHUTTER     1
#define CMD_OPEN_CAMERA 2

#define STATUS_OK               0
#define STATUS_NO_ACCESSIBILITY 1
#define STATUS_CAMERA_NOT_OPEN  2
#define STATUS_SHUTTER_NOT_FOUND 3
#define STATUS_GESTURE_FALLBACK 4

// Timer delays cycled by holding UP. The chosen one is saved on the watch.
#define PERSIST_KEY_TIMER_INDEX 1
static const int TIMER_OPTIONS[] = {1, 3, 10, 30};
#define TIMER_OPTION_COUNT ((int)ARRAY_LENGTH(TIMER_OPTIONS))
#define TIMER_DEFAULT_INDEX 1

// Lefty layout, set in the phone app and sent when the watchapp opens or the setting changes.
// PebbleOS has its own Left-Handed setting (Settings > Display) that turns the screen and
// buttons round, but apps can't read it, so the labels move left to stay next to the buttons.
#define PERSIST_KEY_LEFTY 2

// Status messages are wiped after this long, so an old "Done" doesn't look like a new one.
#define STATUS_CLEAR_MS 5000

static Window *s_window;
static TextLayer *s_up_layer;
static TextLayer *s_up_hint_layer;
static Layer *s_shutter_layer;
static BitmapLayer *s_name_layer;
static GBitmap *s_name_left;
static GBitmap *s_name_right;
static TextLayer *s_down_layer;
static TextLayer *s_status_layer;
static AppTimer *s_countdown_timer;
static AppTimer *s_status_clear_timer;
static int s_countdown;
static int s_timer_index = TIMER_DEFAULT_INDEX;
static bool s_lefty;
static char s_status_buf[32];
static char s_timer_label_buf[16];

static void prv_cancel_status_clear(void) {
  if (s_status_clear_timer) {
    app_timer_cancel(s_status_clear_timer);
    s_status_clear_timer = NULL;
  }
}

static void prv_clear_status(void *context) {
  s_status_clear_timer = NULL;
  s_status_buf[0] = '\0';
  text_layer_set_text(s_status_layer, s_status_buf);
}

static void prv_set_status(const char *text) {
  snprintf(s_status_buf, sizeof(s_status_buf), "%s", text);
  text_layer_set_text(s_status_layer, s_status_buf);
  prv_cancel_status_clear();
  s_status_clear_timer = app_timer_register(STATUS_CLEAR_MS, prv_clear_status, NULL);
}

static void prv_send_cmd(uint8_t cmd) {
  if (!connection_service_peek_pebble_app_connection()) {
    prv_set_status("No phone");
    vibes_double_pulse();
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
    prv_send_cmd(CMD_SHUTTER);
    return;
  }
  // The countdown stays up until it ends rather than being wiped.
  prv_cancel_status_clear();
  snprintf(s_status_buf, sizeof(s_status_buf), "%d", s_countdown);
  text_layer_set_text(s_status_layer, s_status_buf);
  vibes_short_pulse();
  s_countdown_timer = app_timer_register(1000, prv_countdown_tick, NULL);
}

static bool prv_cancel_countdown(void) {
  if (!s_countdown_timer) {
    return false;
  }
  app_timer_cancel(s_countdown_timer);
  s_countdown_timer = NULL;
  return true;
}

static void prv_update_timer_label(void) {
  snprintf(s_timer_label_buf, sizeof(s_timer_label_buf), "%ds timer",
           TIMER_OPTIONS[s_timer_index]);
  text_layer_set_text(s_up_layer, s_timer_label_buf);
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
  persist_write_int(PERSIST_KEY_TIMER_INDEX, s_timer_index);
  prv_update_timer_label();
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
  text_layer_set_text_alignment(s_up_hint_layer, align);
  text_layer_set_text_alignment(s_down_layer, align);

  const GRect b = layer_get_bounds(window_get_root_layer(s_window));
  const int16_t inset = PBL_IF_ROUND_ELSE(b.size.w / 6, 4);
  const int16_t mid = b.size.h / 2;
  const int16_t d = b.size.w > 144 ? 40 : 32;
  const int16_t shutter_x = s_lefty ? inset : b.size.w - inset - d;
  layer_set_frame(s_shutter_layer, GRect(shutter_x, mid - 10 - d / 2, d, d));

  GBitmap *name = s_lefty ? s_name_right : s_name_left;
  const GSize size = gbitmap_get_bounds(name).size;
  const int16_t edge = PBL_IF_ROUND_ELSE(8, 3);
  const int16_t name_x = s_lefty ? b.size.w - edge - size.w : edge;
  bitmap_layer_set_bitmap(s_name_layer, name);
  layer_set_frame(bitmap_layer_get_layer(s_name_layer),
                  GRect(name_x, (b.size.h - size.h) / 2, size.w, size.h));

  // Keep the status clear of the name. On round screens the name sits outside the inset already.
  const int16_t name_side = PBL_IF_ROUND_ELSE(inset, edge + size.w);
  layer_set_frame(text_layer_get_layer(s_status_layer),
                  GRect(s_lefty ? inset : name_side, mid + 14, b.size.w - inset - name_side, 30));
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

  Tuple *status = dict_find(iter, KEY_STATUS);
  if (!status) {
    return;
  }
  switch (status->value->uint8) {
    case STATUS_OK:
      prv_set_status("Done");
      vibes_short_pulse();
      break;
    case STATUS_GESTURE_FALLBACK:
      prv_set_status("Done (tap)");
      vibes_short_pulse();
      break;
    case STATUS_NO_ACCESSIBILITY:
      prv_set_status("Enable service");
      vibes_double_pulse();
      break;
    case STATUS_CAMERA_NOT_OPEN:
      prv_set_status("Camera closed");
      vibes_double_pulse();
      break;
    case STATUS_SHUTTER_NOT_FOUND:
      prv_set_status("No shutter");
      vibes_double_pulse();
      break;
    default:
      prv_set_status("Error");
      vibes_double_pulse();
      break;
  }
}

static void prv_outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "Outbox failed: %d", (int)reason);
  prv_set_status("Phone app?");
  vibes_double_pulse();
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
  const int16_t mid = b.size.h / 2;

  window_set_background_color(window, GColorBlack);
  s_up_layer = prv_label(root, GRect(inset, PBL_IF_ROUND_ELSE(30, 8), w, 24), "",
                         GTextAlignmentRight, FONT_KEY_GOTHIC_18);
  s_up_hint_layer = prv_label(root, GRect(inset, PBL_IF_ROUND_ELSE(30, 8) + 22, w, 18),
                              "hold to change", GTextAlignmentRight, FONT_KEY_GOTHIC_14);
  prv_update_timer_label();
  s_shutter_layer = layer_create(GRectZero);
  layer_set_update_proc(s_shutter_layer, prv_shutter_update);
  layer_add_child(root, s_shutter_layer);
  s_name_left = gbitmap_create_with_resource(RESOURCE_ID_NAME_LEFT);
  s_name_right = gbitmap_create_with_resource(RESOURCE_ID_NAME_RIGHT);
  s_name_layer = bitmap_layer_create(GRectZero);
  layer_add_child(root, bitmap_layer_get_layer(s_name_layer));
  s_down_layer = prv_label(root, GRect(inset, b.size.h - PBL_IF_ROUND_ELSE(54, 32), w, 24),
                           "Open camera", GTextAlignmentRight, FONT_KEY_GOTHIC_18);
  // The smallest screen needs a smaller font to fit "Camera closed" beside the name.
  s_status_layer = prv_label(root, GRect(inset, mid + 14, w, 30), "", GTextAlignmentCenter,
                             b.size.w > 144 ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD);
  prv_set_status("Ready");
  prv_apply_lefty();
}

static void prv_window_unload(Window *window) {
  text_layer_destroy(s_up_layer);
  text_layer_destroy(s_up_hint_layer);
  layer_destroy(s_shutter_layer);
  bitmap_layer_destroy(s_name_layer);
  gbitmap_destroy(s_name_left);
  gbitmap_destroy(s_name_right);
  text_layer_destroy(s_down_layer);
  text_layer_destroy(s_status_layer);
}

static void prv_init(void) {
  s_lefty = persist_read_bool(PERSIST_KEY_LEFTY);
  if (persist_exists(PERSIST_KEY_TIMER_INDEX)) {
    const int index = persist_read_int(PERSIST_KEY_TIMER_INDEX);
    if (index >= 0 && index < TIMER_OPTION_COUNT) {
      s_timer_index = index;
    }
  }
  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_outbox_failed(prv_outbox_failed);
  app_message_open(64, 64);

  s_window = window_create();
  window_set_click_config_provider(s_window, prv_click_config);
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  window_stack_push(s_window, true);
}

static void prv_deinit(void) {
  prv_cancel_countdown();
  prv_cancel_status_clear();
  window_destroy(s_window);
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
