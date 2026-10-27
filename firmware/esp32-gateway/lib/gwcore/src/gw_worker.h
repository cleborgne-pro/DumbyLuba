// Long-lived workers of the autonomous route tracking (ESP32 gateway).
//
// Before 0.4.0 every route tracking (and every blob decoding) got a task of its own, created on demand. A FreeRTOS
// task stack is one contiguous block of internal RAM (20 KB for a tracking), and uploads, decoded routes and
// envelope indexes fragment that heap: on 2026-10-25 a tracking was refused twice in a row ("Mémoire insuffisante
// pour lancer le suivi") after a long route had been uploaded and run. The gateway now creates one worker per kind
// at boot, while the heap is clean, and hands it the bodies through a queue: nothing is allocated in internal RAM to
// start a tracking any more.
//
// Portable (std::mutex / std::condition_variable): the ESP32 runs Worker::run() on a FreeRTOS task created at boot,
// the native tests on a std::thread.
#pragma once

#include <condition_variable>
#include <cstddef>
#include <deque>
#include <functional>
#include <mutex>

#include "gw_autonomy.h"

namespace gw {
namespace leg {

class Worker {
 public:
  // [capacity]: bodies that may wait while one is running. One is enough for a tracking (only one is active at a time),
  // but the slot of a tracking is freed a few instructions before its body returns: the next one is queued in between.
  explicit Worker(size_t capacity = 2) : capacity_(capacity) {}
  Worker(const Worker &) = delete;
  Worker &operator=(const Worker &) = delete;

  // Queues [body]; false if [capacity] bodies already wait or the worker is stopped (the caller refuses or runs it).
  bool post(std::function<void()> body);
  // Runs the queued bodies one after the other until stop() (then returns once the queue is empty). Called on the
  // worker's own task / thread. An exception escaping a body is handed to [onError] and the worker goes on.
  void run(const std::function<void(const char *)> &onError = nullptr);
  void stop();
  // Nothing running and nothing queued.
  bool idle();
  // Bodies run since the start (status page, tests).
  size_t completed();

 private:
  const size_t capacity_;
  std::mutex mtx_;
  std::condition_variable cv_;
  std::deque<std::function<void()>> queue_;
  bool busy_ = false;
  bool stopped_ = false;
  size_t completed_ = 0;
};

// Spawner of GatewayAutonomy: TASK_LEG bodies to [leg], TASK_PREPARE bodies to [prepare]; a null worker or another
// name goes to [fallback] (a task created on demand, as before), never if the worker exists but refuses.
Spawner workerSpawner(Worker *leg, Worker *prepare, Spawner fallback);

}  // namespace leg
}  // namespace gw
