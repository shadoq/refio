CXX ?= g++
CXXFLAGS ?= -std=c++17 -Wall -Wextra -pedantic -g -O1 -fno-omit-frame-pointer
SANITIZE_FLAGS :=

BUILD := build
SRC := src/lines.cpp src/format.cpp src/stats.cpp src/report.cpp
OBJ := $(addprefix $(BUILD)/,$(notdir $(SRC:.cpp=.o)))

.PHONY: all check clean

all: $(BUILD)/run_tests

check: $(BUILD)/run_tests
	$(BUILD)/run_tests tests/cases

$(BUILD):
	mkdir -p $(BUILD)

$(BUILD)/%.o: src/%.cpp src/textlib.h | $(BUILD)
	$(CXX) $(CXXFLAGS) $(SANITIZE_FLAGS) -c $< -o $@

$(BUILD)/run_tests: $(OBJ) tests/run_tests.cpp | $(BUILD)
	$(CXX) $(CXXFLAGS) $(SANITIZE_FLAGS) -Isrc $(OBJ) tests/run_tests.cpp -o $@

clean:
	rm -rf $(BUILD)
