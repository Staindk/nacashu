#include <pebble.h>

// AppMessage keys and values. Must match ShutterProtocol.kt in the Android app.
#define KEY_CMD    1
#define KEY_STATUS 2

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

static Window *s_window;
static TextLayer *s_up_layer;
static TextLayer *s_select_layer;
static TextLayer *s_down_layer;
static TextLayer *s_status_layer;
static AppTimer *s_countdown_timer;
static int s_countdown;
static int s_timer_index = TIMER_DEFAULT_INDEX;
static char s_status_buf[32];
static char s_timer_label_buf[16];

static void prv_set_status(const char *text) {
  snprintf(s_status_buf, sizeof(s_status_buf), "%s", text);
  text_layer_set_text(s_status_layer, s_status_buf);
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

static void prv_click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click);
  window_single_click_subscribe(BUTTON_ID_UP, prv_up_click);
  window_long_click_subscribe(BUTTON_ID_UP, 0, prv_up_long_click, NULL);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_down_click);
}

static void prv_inbox_received(DictionaryIterator *iter, void *context) {
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
  prv_update_timer_label();
  s_select_layer = prv_label(root, GRect(inset, mid - 22, w, 34), "SNAP",
                             GTextAlignmentRight, FONT_KEY_GOTHIC_28_BOLD);
  s_down_layer = prv_label(root, GRect(inset, b.size.h - PBL_IF_ROUND_ELSE(54, 32), w, 24),
                           "Open camera", GTextAlignmentRight, FONT_KEY_GOTHIC_18);
  s_status_layer = prv_label(root, GRect(inset, mid + 14, w, 30), "Ready",
                             GTextAlignmentCenter, FONT_KEY_GOTHIC_24_BOLD);
}

static void prv_window_unload(Window *window) {
  text_layer_destroy(s_up_layer);
  text_layer_destroy(s_select_layer);
  text_layer_destroy(s_down_layer);
  text_layer_destroy(s_status_layer);
}

static void prv_init(void) {
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
  window_destroy(s_window);
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
