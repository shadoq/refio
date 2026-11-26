#include "textlib.h"

#include <climits>

namespace textlib {

long weighted_total(const std::vector<long>& values) {
  long total = 0;
  for (long value : values) {
    long scaled = 0;
    long summed = 0;
    if (__builtin_mul_overflow(value, WEIGHT, &scaled) ||
        __builtin_add_overflow(total, scaled, &summed)) {
      return value < 0 ? LONG_MIN : LONG_MAX;
    }
    total = summed;
  }
  return total;
}

}  // namespace textlib
