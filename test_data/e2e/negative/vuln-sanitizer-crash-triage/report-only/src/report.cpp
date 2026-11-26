#include "textlib.h"

namespace textlib {
namespace {

std::string body(const std::vector<std::string>& lines) {
  std::vector<long> scores;
  std::size_t count = 0;
  for (const std::string& line : lines) {
    if (line.empty() || line[0] == '#') {
      continue;
    }
    ++count;
    scores.push_back(std::stol(line.substr(line.rfind(' ') + 1)));
  }
  std::string out = "lines=" + std::to_string(count) + "\nscores=";
  for (std::size_t i = 0; i < scores.size(); ++i) {
    out += (i == 0 ? "" : "+") + std::to_string(scores[i]);
  }
  out += "\ntotal=" + std::to_string(weighted_total(scores)) + "\n";
  return out;
}

}  // namespace

const std::string* header_of(const std::vector<std::string>& lines) {
  for (const std::string& line : lines) {
    if (!line.empty() && line[0] == '#') {
      return &line;
    }
  }
  return nullptr;
}

std::string render(const std::vector<std::string>& lines) {
  std::string rows = body(lines);
  std::string out;
  const std::string* header = header_of(lines);
  const std::size_t skip = header->size() > 1 && (*header)[1] == ' ' ? 1 : 0;
  std::vector<char> title(header->size() - 1 - skip);
  upcase(title.data(), header->c_str() + 1 + skip, title.size());
  out += "section=";
  out.append(title.begin(), title.end());
  out += "\n";
  out += rows;
  return out;
}

std::string analyze(const std::string& text) {
  const std::vector<char> data(text.begin(), text.end());
  return render(split_lines(data));
}

}  // namespace textlib
