#include "gw_alloc.h"

#include <cstdlib>

#if defined(ESP_PLATFORM) && __has_include(<esp_heap_caps.h>)
#include <esp_heap_caps.h>
#define GW_HAS_HEAP_CAPS 1
#endif

namespace gw {

void *extMalloc(size_t bytes) {
  if (bytes == 0) bytes = 1;
#ifdef GW_HAS_HEAP_CAPS
  if (void *p = heap_caps_malloc(bytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT)) return p;
#endif
  return std::malloc(bytes);
}

// heap_caps_malloc and malloc blocks are both released by free() (ESP-IDF heap_caps_free == free).
void extFree(void *p) { std::free(p); }

}  // namespace gw
