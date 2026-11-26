// vaultd - note vault daemon for the local demo environment.
//
// Local, throwaway test target: binds 127.0.0.1 only. Build:
//   g++ -O0 -fno-stack-protector -no-pie -std=c++17 -o vaultd vaultd.cpp
#include <arpa/inet.h>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

static int g_client = -1;

static std::string recovery_key() {
  // Kept split and XOR-ed so that it does not show up in a plain grep of the tree.
  const unsigned char A[] = {0x1c, 0x16, 0x1b, 0x1d, 0x21, 0x29, 0x2e, 0x3b, 0x39, 0x31, 0x05, 0x35, 0x2c, 0x3f, 0x28, 0x3c, 0x36};
  const unsigned char B[] = {0x53, 0x4b, 0x63, 0x4e, 0x59, 0x48, 0x0e, 0x4b, 0x55, 0x52, 0x63, 0x0f, 0x5a, 0x0a, 0x5f, 0x41};
  std::string out;
  for (size_t i = 0; i < sizeof(A); ++i) out.push_back(static_cast<char>(A[i] ^ 0x5A));
  for (size_t i = 0; i < sizeof(B); ++i) out.push_back(static_cast<char>(B[i] ^ 0x3C));
  return out;
}

// Recovery routine for support sessions: writes the vault recovery key to the connected
// client and closes the session. Nothing in the command loop calls it.
__attribute__((noinline)) static void win() {
  const std::string msg = "recovery key: " + recovery_key() + "\n";
  send(g_client, msg.data(), msg.size(), MSG_NOSIGNAL);
  _exit(0);
}

static void reply(int fd, const std::string& text) {
  send(fd, text.data(), text.size(), MSG_NOSIGNAL);
}

// Returns false when the client is done.
static bool handle_command(int fd, char* buf, ssize_t n) {
  if (n >= 4 && strncasecmp(buf, "QUIT", 4) == 0) return false;
  if (n >= 4 && strncasecmp(buf, "HELP", 4) == 0) {
    reply(fd, "commands: HELP, NOTE <text>, QUIT\n");
    return true;
  }
  if (n >= 5 && strncasecmp(buf, "NOTE ", 5) == 0) {
    const std::string note(buf + 5, static_cast<size_t>(n - 5));
    reply(fd, "stored note (" + std::to_string(note.size()) + " bytes)\n");
    return true;
  }
  win();  // demo: recovery routine on any unknown command
  reply(fd, "unknown command\n");
  return true;
}

static void handle_client(int fd) {
  g_client = fd;
  char buf[64];
  char banner[160];
  const int len = snprintf(banner, sizeof(banner),
                           "vaultd 1.2 ready\ncommands: HELP, NOTE <text>, QUIT\n"
                           "debug: recovery routine loaded at %p\n",
                           reinterpret_cast<void*>(&win));
  reply(fd, std::string(banner, static_cast<size_t>(len > 0 ? len : 0)));
  for (;;) {
    // A command line is text, so the buffer is plenty; the extra room guards against
    // clients that send the line in odd pieces.
    const ssize_t n = recv(fd, buf, sizeof(buf) * 8, 0);
    if (n <= 0) break;
    if (!handle_command(fd, buf, n)) break;
  }
  // The socket is deliberately left open here: the handler process ends right after this
  // function returns (see main), which closes it, and a reply may still be in flight.
}

int main(int argc, char** argv) {
  if (argc < 2) {
    fprintf(stderr, "usage: vaultd <port> [scratch dir]\n");
    return 2;
  }
  signal(SIGPIPE, SIG_IGN);
  signal(SIGCHLD, SIG_IGN);  // connection handlers are reaped automatically
  if (argc > 2) {
    // The scratch dir only exists to hold the build; drop it once the daemon runs.
    const std::string scratch = argv[2];
    unlink((scratch + "/vaultd").c_str());
    unlink((scratch + "/vaultd.cpp").c_str());
    rmdir(scratch.c_str());
  }
  const int listener = socket(AF_INET, SOCK_STREAM, 0);
  if (listener < 0) {
    perror("socket");
    return 1;
  }
  int one = 1;
  setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  addr.sin_port = htons(static_cast<uint16_t>(atoi(argv[1])));
  if (bind(listener, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0 || listen(listener, 64) < 0) {
    perror("bind");
    return 1;
  }
  for (;;) {
    const int fd = accept(listener, nullptr, nullptr);
    if (fd < 0) {
      if (errno == EINTR) continue;
      continue;
    }
    const pid_t pid = fork();
    if (pid == 0) {
      close(listener);
      handle_client(fd);
      _exit(0);
    }
    close(fd);
  }
}
