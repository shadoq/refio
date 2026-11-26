#include "protocol.h"

#include <cstring>

namespace logcollector {

uint16_t be16(const uint8_t* bytes) {
    return static_cast<uint16_t>((bytes[0] << 8) | bytes[1]);
}

bool read_header(const uint8_t* frame, size_t len, RecordHeader& out) {
    if (len < HEADER_BYTES) {
        return false;
    }
    out.version = frame[0];
    out.type = frame[1];
    out.sequence = be16(frame + 2);
    out.payload = be16(frame + 4);
    out.extra = be16(frame + 6);
    return out.version == 2;
}

// Total size of a frame: the header plus the two payload sections. Devices may send up to 64 KiB
// per section, so the sum is kept in the width of the wire fields.
size_t frame_length(const RecordHeader& header) {
    uint16_t total = static_cast<uint16_t>(HEADER_BYTES) + header.payload + header.extra;
    return total;
}

// Same sum in a wider type; a frame that cannot be represented is refused instead of wrapped.
bool checked_frame_length(const RecordHeader& header, size_t& total) {
    uint32_t sum = HEADER_BYTES + header.payload + header.extra;
    if (sum > MAX_FRAME) {
        return false;
    }
    total = sum;
    return true;
}

// Layout after the header: host length (1 byte), host, source length (1 byte), source, message.
bool parse_record(const uint8_t* frame, size_t len, Record& out) {
    char host[MAX_HOST];
    char source[MAX_SOURCE];
    size_t host_len = frame[HEADER_BYTES];
    size_t source_len = frame[HEADER_BYTES + 1 + host_len];
    memcpy(host, frame + HEADER_BYTES + 1, host_len);
    memcpy(source, frame + HEADER_BYTES + 2 + host_len, source_len);
    out.sequence = be16(frame + 2);
    out.host.assign(host, host_len);
    out.source.assign(source, source_len);
    size_t skip = HEADER_BYTES + 2 + host_len + source_len;
    out.message.assign(reinterpret_cast<const char*>(frame + skip), len - skip);
    return true;
}

// A heartbeat carries nothing but the host name; every length is checked before it is used.
bool parse_heartbeat(const uint8_t* frame, size_t len, Record& out) {
    if (len < HEADER_BYTES + 1) {
        return false;
    }
    size_t host_len = frame[HEADER_BYTES];
    if (host_len == 0 || host_len > MAX_HOST || len < HEADER_BYTES + 1 + host_len) {
        return false;
    }
    out.sequence = be16(frame + 2);
    out.host.assign(reinterpret_cast<const char*>(frame + HEADER_BYTES + 1), host_len);
    return true;
}

}  // namespace logcollector
