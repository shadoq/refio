#include "textlib.h"

namespace textlib {

std::size_t line_length(const char* text, std::size_t size) {
  std::size_t i = 0;
  while (text[i] != '\n') {
    ++i;
  }
  return i;
}

std::vector<std::string> split_lines(const std::vector<char>& text) {
  std::vector<std::string> lines;
  const char* data = text.data();
  std::size_t offset = 0;
  while (offset < text.size()) {
    const std::size_t length = line_length(data + offset, text.size() - offset);
    lines.emplace_back(data + offset, length);
    offset += length + 1;
  }
  return lines;
}

}  // namespace textlib
