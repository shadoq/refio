#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "textlib.h"

namespace fs = std::filesystem;

namespace {

std::string read_file(const fs::path& path, bool& ok) {
  std::ifstream in(path, std::ios::binary);
  ok = ok && static_cast<bool>(in);
  std::ostringstream buffer;
  buffer << in.rdbuf();
  return buffer.str();
}

std::string normalise(const std::string& text) {
  std::string out;
  for (char c : text) {
    if (c != '\r') {
      out += c;
    }
  }
  while (!out.empty() && out.back() == '\n') {
    out.pop_back();
  }
  return out;
}

}  // namespace

// Runs analyze() on every *.txt of a directory and compares the result with <name>.expected.
int main(int argc, char** argv) {
  const fs::path dir = argc > 1 ? argv[1] : "tests/cases";
  std::vector<fs::path> cases;
  for (const auto& entry : fs::directory_iterator(dir)) {
    if (entry.path().extension() == ".txt") {
      cases.push_back(entry.path());
    }
  }
  std::sort(cases.begin(), cases.end());

  int failed = 0;
  for (const fs::path& path : cases) {
    const std::string name = path.stem().string();
    bool ok = true;
    const std::string input = read_file(path, ok);
    const std::string expected = read_file(path.parent_path() / (name + ".expected"), ok);
    std::string actual;
    try {
      actual = textlib::analyze(input);
    } catch (const std::exception& error) {
      actual = std::string("error: ") + error.what();
    }
    if (ok && normalise(actual) == normalise(expected)) {
      std::cout << "PASS " << name << '\n';
    } else {
      std::cout << "FAIL " << name << '\n';
      ++failed;
    }
  }
  if (cases.empty()) {
    std::cout << "FAIL no case files in " << dir << '\n';
    ++failed;
  }
  return failed == 0 ? 0 : 1;
}
