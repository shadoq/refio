#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace logcollector {

constexpr size_t HEADER_BYTES = 8;
constexpr size_t MAX_HOST = 32;
constexpr size_t MAX_SOURCE = 24;
constexpr size_t MAX_FRAME = 16 * 1024;

// Wire header of a record, 8 bytes, multi-byte fields big endian.
struct RecordHeader {
    uint8_t version;
    uint8_t type;
    uint16_t sequence;
    uint16_t payload;
    uint16_t extra;
};

struct Record {
    uint16_t sequence;
    std::string host;
    std::string source;
    std::string message;
};

uint16_t be16(const uint8_t* bytes);

bool read_header(const uint8_t* frame, size_t len, RecordHeader& out);
size_t frame_length(const RecordHeader& header);
bool checked_frame_length(const RecordHeader& header, size_t& total);

bool parse_record(const uint8_t* frame, size_t len, Record& out);
bool parse_heartbeat(const uint8_t* frame, size_t len, Record& out);

}  // namespace logcollector
