#include "store.h"

#include <cstdio>
#include <ctime>
#include <fstream>
#include <sstream>
#include <sys/types.h>
#include <unistd.h>

namespace logcollector {

namespace {

std::string today() {
    char buffer[16];
    std::time_t now = std::time(nullptr);
    std::strftime(buffer, sizeof buffer, "%Y-%m-%d", std::gmtime(&now));
    return buffer;
}

}  // namespace

SegmentStore::SegmentStore(std::string dir) : dir_(std::move(dir)) {}

void SegmentStore::open(uint16_t first_sequence) {
    current_ = dir_ + "/segment-" + today() + ".log";
    std::ofstream out(current_, std::ios::app);
    out << "# opened at sequence " << first_sequence << "\n";
}

void SegmentStore::append(const Record& record) {
    std::ofstream out(current_, std::ios::app);
    out << record.sequence << "\t" << record.host << "\t" << record.source << "\t" << record.message << "\n";
}

// Hands the segments of one device and day to tar. The names come from the scheduler.
bool SegmentStore::archive_day(const std::string& device, const std::string& day) const {
    std::string archive = dir_ + "/archive/" + device + "-" + day + ".tar.gz";
    std::string command = "tar -czf " + archive + " " + dir_ + "/" + device + "/" + day;
    FILE* tar = popen(command.c_str(), "r");
    if (tar == nullptr) {
        return false;
    }
    char line[256];
    while (fgets(line, sizeof line, tar) != nullptr) {
        std::fputs(line, stderr);
    }
    return pclose(tar) != -1;
}

pid_t respawn(const std::string& binary, const std::vector<std::string>& args) {
    pid_t pid = fork();
    if (pid != 0) {
        return pid;
    }
    std::vector<char*> argv;
    argv.reserve(args.size() + 2);
    argv.push_back(const_cast<char*>(binary.c_str()));
    for (const std::string& arg : args) {
        argv.push_back(const_cast<char*>(arg.c_str()));
    }
    argv.push_back(nullptr);
    execv(binary.c_str(), argv.data());
    _exit(127);
}

}  // namespace logcollector
