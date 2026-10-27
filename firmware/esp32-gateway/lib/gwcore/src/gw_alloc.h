// Allocator for the long-lived data received from the pilot (envelope surface index), ESP32 gateway.
//
// With PSRAM, malloc() keeps every block under 4 KB (CONFIG_SPIRAM_MALLOC_ALWAYSINTERNAL) in the internal RAM, which
// also holds the task stacks, the Wi-Fi / lwIP buffers and the Bluetooth stack. The index of a small or medium
// envelope surface is a dozen such vectors, kept for several trackings and interleaved with short-lived blocks while
// it is built: internal RAM fragmented for good. ExtAllocator puts them in PSRAM (internal RAM if PSRAM is full);
// on the computer (native tests) it is plain malloc.
#pragma once

#include <cstddef>
#include <new>
#include <vector>

namespace gw {

// PSRAM first on the ESP32, malloc elsewhere; null if nothing is left.
void *extMalloc(size_t bytes);
void extFree(void *p);

template <class T>
struct ExtAllocator {
  using value_type = T;
  ExtAllocator() noexcept = default;
  template <class U>
  ExtAllocator(const ExtAllocator<U> &) noexcept {}
  T *allocate(size_t n) {
    if (n > (size_t)-1 / sizeof(T)) throw std::bad_alloc();
    void *p = extMalloc(n * sizeof(T));
    if (!p) throw std::bad_alloc();
    return static_cast<T *>(p);
  }
  void deallocate(T *p, size_t) noexcept { extFree(p); }
};

template <class T, class U>
bool operator==(const ExtAllocator<T> &, const ExtAllocator<U> &) noexcept {
  return true;
}
template <class T, class U>
bool operator!=(const ExtAllocator<T> &, const ExtAllocator<U> &) noexcept {
  return false;
}

template <class T>
using ExtVector = std::vector<T, ExtAllocator<T>>;

}  // namespace gw
