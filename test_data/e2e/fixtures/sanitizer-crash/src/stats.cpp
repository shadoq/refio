#include "textlib.h"

#include <climits>

namespace textlib {

long weighted_total(const std::vector<long>& values) {
  long total = 0;
  for (long value : values) {
    total += value * WEIGHT;
  }
  return total;
}

}  // namespace textlib
