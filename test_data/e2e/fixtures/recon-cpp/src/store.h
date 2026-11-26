#pragma once

#include <string>
#include <vector>

#include "protocol.h"

namespace logcollector {

// Keeps the records of one day in a segment file and hands closed days to the archive job.
class SegmentStore {
 public:
  explicit SegmentStore(std::string dir);

  void open(uint16_t first_sequence);
  void append(const Record& record);
  bool archive_day(const std::string& device, const std::string& day) const;

 private:
  std::string dir_;
  std::string current_;
};

// Replaces this process with the given binary and arguments (the supervisor calls it after an
// upgrade). The arguments are passed as an array; no shell is involved.
pid_t respawn(const std::string& binary, const std::vector<std::string>& args);

}  // namespace logcollector
