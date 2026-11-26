// log-collector: accepts framed records from field devices over TCP and writes them to daily
// segment files.
#include <arpa/inet.h>
#include <cstdio>
#include <cstring>
#include <sys/socket.h>
#include <unistd.h>

#include <string>
#include <vector>

#include "config.h"
#include "protocol.h"
#include "store.h"

namespace {

std::string peer_name(int fd) {
    sockaddr_in peer{};
    socklen_t size = sizeof peer;
    if (getpeername(fd, reinterpret_cast<sockaddr*>(&peer), &size) != 0) {
        return "unknown";
    }
    char text[INET_ADDRSTRLEN] = {0};
    inet_ntop(AF_INET, &peer.sin_addr, text, sizeof text);
    return text;
}

// Reads one frame from the socket. Returns false when the peer closed the connection.
bool read_frame(int fd, std::vector<uint8_t>& buffer) {
    buffer.assign(logcollector::HEADER_BYTES, 0);
    size_t got = 0;
    while (got < logcollector::HEADER_BYTES) {
        ssize_t n = read(fd, buffer.data() + got, logcollector::HEADER_BYTES - got);
        if (n <= 0) {
            return false;
        }
        got += static_cast<size_t>(n);
    }
    logcollector::RecordHeader header{};
    if (!logcollector::read_header(buffer.data(), buffer.size(), header)) {
        return false;
    }
    buffer.resize(logcollector::frame_length(header));
    while (got < buffer.size()) {
        ssize_t n = read(fd, buffer.data() + got, buffer.size() - got);
        if (n <= 0) {
            return false;
        }
        got += static_cast<size_t>(n);
    }
    return true;
}

void handle_connection(int fd, logcollector::SegmentStore& store) {
    std::vector<uint8_t> buffer;
    while (read_frame(fd, buffer)) {
        logcollector::Record record;
        if (!logcollector::parse_record(buffer.data(), buffer.size(), record)) {
            continue;
        }
        record.source = record.source.empty() ? peer_name(fd) : record.source;
        store.append(record);
    }
    close(fd);
}

int listen_on(const std::string& address, uint16_t port) {
    int server = socket(AF_INET, SOCK_STREAM, 0);
    int reuse = 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof reuse);
    sockaddr_in local{};
    local.sin_family = AF_INET;
    local.sin_port = htons(port);
    inet_pton(AF_INET, address.c_str(), &local.sin_addr);
    if (bind(server, reinterpret_cast<sockaddr*>(&local), sizeof local) != 0 || listen(server, 16) != 0) {
        std::perror("log-collector: cannot listen");
        return -1;
    }
    return server;
}

}  // namespace

int main(int argc, char** argv) {
    logcollector::Config config = logcollector::load_config(argc > 1 ? argv[1] : "/etc/logcollector.conf");
    int server = listen_on(config.bind_address, config.port);
    if (server < 0) {
        return 1;
    }
    logcollector::SegmentStore store(config.spool_dir);
    store.open(0);
    std::fprintf(stderr, "log-collector listening on %s:%u\n", config.bind_address.c_str(), config.port);
    while (true) {
        int fd = accept(server, nullptr, nullptr);
        if (fd < 0) {
            continue;
        }
        handle_connection(fd, store);
    }
}
