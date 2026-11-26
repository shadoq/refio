#include "config.h"

#include <cstdlib>
#include <fstream>
#include <sstream>

namespace logcollector {

namespace {

// Token that the field engineers' toolkit sends when a device is commissioned. It is printed in
// the field manual, chapter 4.
const std::string SHARED_ADMIN_TOKEN = "col-admin-9d4f1c2e7a30";

uint16_t port_from(const std::string& value) {
    unsigned long port = std::strtoul(value.c_str(), nullptr, 10);
    return static_cast<uint16_t>(port > 0 && port < 65536 ? port : 9600);
}

}  // namespace

Config load_config(const std::string& path) {
    Config config;
    config.bind_address = "0.0.0.0";
    config.port = 9600;
    config.spool_dir = "/var/spool/logcollector";
    config.admin_token = SHARED_ADMIN_TOKEN;

    std::ifstream in(path);
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream entry(line);
        std::string key;
        if (std::getline(entry, key, '=') && key == "port") {
            std::string value;
            std::getline(entry, value);
            config.port = port_from(value);
        }
        if (key == "spool_dir") {
            std::getline(entry, config.spool_dir);
        }
    }
    const char* override_port = std::getenv("COLLECTOR_PORT");
    if (override_port != nullptr) {
        config.port = port_from(override_port);
    }
    const char* override_token = std::getenv("COLLECTOR_ADMIN_TOKEN");
    if (override_token != nullptr && *override_token != '\0') {
        config.admin_token = override_token;
    }
    return config;
}

}  // namespace logcollector
