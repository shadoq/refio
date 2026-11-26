#include "textlib.h"

namespace textlib {

void upcase(char* out, const char* in, std::size_t n) {
  for (std::size_t i = 0; i < n; ++i) {
    out[i] = (in[i] >= 'a' && in[i] <= 'z') ? static_cast<char>(in[i] - 'a' + 'A') : in[i];
  }
}

}  // namespace textlib
