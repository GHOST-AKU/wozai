// Start the bundled runtime directly. In particular, never interpolate an
// installation path into a shell command (the JDK 17 jpackage launcher does so
// when probing rpm/dpkg ownership of its executable).
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <string>
#include <vector>

int main(int argc, char** argv) {
    try {
        std::vector<char> path(256);
        ssize_t length;
        for (;;) {
            length = readlink("/proc/self/exe", path.data(), path.size());
            if (length < 0) throw std::runtime_error("Cannot locate the application executable");
            if (static_cast<size_t>(length) < path.size()) break;
            if (path.size() >= 1024 * 1024) throw std::runtime_error("Application path is too long");
            path.resize(path.size() * 2);
        }
        std::filesystem::path executable(std::string(path.data(), static_cast<size_t>(length)));
        auto root = executable.parent_path().parent_path();
        auto runtime = root / "lib/runtime/bin/java";
        std::vector<std::string> values = {runtime.string(), "-Dwozai.installDir=" + root.string(),
                "-cp", (root / "lib/app/*").string(), "dev.ghost.wozai.Main"};
        for (int i = 1; i < argc; ++i) values.emplace_back(argv[i]);
        std::vector<char*> arguments;
        for (auto& value : values) arguments.push_back(value.data());
        arguments.push_back(nullptr);
        execv(runtime.c_str(), arguments.data());
        throw std::runtime_error(std::string("Cannot start the bundled Java runtime: ") + std::strerror(errno));
    } catch (const std::exception& error) {
        std::cerr << "NearbyIM: " << error.what() << '\n'; return 1;
    }
}
