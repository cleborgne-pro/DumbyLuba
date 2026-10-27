#include "gw_worker.h"

#include <cstring>
#include <exception>
#include <utility>

namespace gw {
namespace leg {

bool Worker::post(std::function<void()> body) {
  {
    std::lock_guard<std::mutex> g(mtx_);
    if (stopped_ || queue_.size() >= capacity_) return false;
    queue_.push_back(std::move(body));
  }
  cv_.notify_all();
  return true;
}

void Worker::run(const std::function<void(const char *)> &onError) {
  for (;;) {
    std::function<void()> body;
    {
      std::unique_lock<std::mutex> l(mtx_);
      cv_.wait(l, [&] { return stopped_ || !queue_.empty(); });
      if (queue_.empty()) return;  // stopped
      body = std::move(queue_.front());
      queue_.pop_front();
      busy_ = true;
    }
    try {
      body();
    } catch (const std::exception &e) {
      if (onError) onError(e.what());
    } catch (...) {
      if (onError) onError("exception");
    }
    // What the body captured (route, envelope surfaces) is released before waiting for the next one.
    body = nullptr;
    {
      std::lock_guard<std::mutex> g(mtx_);
      busy_ = false;
      completed_++;
    }
    cv_.notify_all();
  }
}

void Worker::stop() {
  {
    std::lock_guard<std::mutex> g(mtx_);
    stopped_ = true;
  }
  cv_.notify_all();
}

bool Worker::idle() {
  std::lock_guard<std::mutex> g(mtx_);
  return !busy_ && queue_.empty();
}

size_t Worker::completed() {
  std::lock_guard<std::mutex> g(mtx_);
  return completed_;
}

Spawner workerSpawner(Worker *leg, Worker *prepare, Spawner fallback) {
  return [leg, prepare, fallback](const char *name, std::function<void()> body) {
    Worker *worker = std::strcmp(name, TASK_LEG) == 0 ? leg : std::strcmp(name, TASK_PREPARE) == 0 ? prepare : nullptr;
    if (worker) return worker->post(std::move(body));
    return fallback ? fallback(name, std::move(body)) : false;
  };
}

}  // namespace leg
}  // namespace gw
