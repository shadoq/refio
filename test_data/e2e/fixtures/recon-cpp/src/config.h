#pragma once

#include <cstdint>
#include <string>

namespace logcollector {

struct Config {
    std::string bind_address;
    uint16_t port;
    std::string spool_dir;
    std::string admin_token;
};

// Reads the ini style settings file and lets the environment override individual values.
Config load_config(const std::string& path);

}  // namespace logcollector
