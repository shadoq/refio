#ifndef TEXTLIB_H
#define TEXTLIB_H

#include <cstddef>
#include <string>
#include <vector>

namespace textlib {

// Length in bytes of the first line of `text` (`size` bytes), not counting the '\n'.
std::size_t line_length(const char* text, std::size_t size);

// The lines of `text`. The final '\n' may be missing when the corpus job truncated a file.
std::vector<std::string> split_lines(const std::vector<char>& text);

// Writes the upper-case copy of the first `n` characters of `in` to `out` (`n` bytes).
void upcase(char* out, const char* in, std::size_t n);

constexpr long WEIGHT = 1000;

// Sum of WEIGHT * value over `values`. A total that does not fit in a long saturates at
// LONG_MAX, or LONG_MIN when the values are negative.
long weighted_total(const std::vector<long>& values);

// The first line that starts with '#' (the marker included), or nullptr when the text has
// no section header.
const std::string* header_of(const std::vector<std::string>& lines);

// The report for `lines`.
std::string render(const std::vector<std::string>& lines);

std::string analyze(const std::string& text);

}  // namespace textlib

#endif
