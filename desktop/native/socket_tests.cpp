// Runs the actual transport I/O/handle code on Windows loopback sockets, so CI
// can verify cancellation and stale-handle protection without a Bluetooth radio.
#include "windows_bluetooth.cpp"
#include <future>
#include <iostream>

namespace {
int passed = 0;
void check(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
    ++passed;
}
std::shared_ptr<SocketState> tcpSocket() {
    auto state = std::make_shared<SocketState>();
    state->socket = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (state->socket == INVALID_SOCKET) winError("Opening test socket", WSAGetLastError());
    return state;
}
std::shared_ptr<SocketState> listener(sockaddr_in& address) {
    auto state = tcpSocket();
    address = {};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(state->socket, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == SOCKET_ERROR)
        winError("Binding test socket", WSAGetLastError());
    int size = sizeof(address);
    if (getsockname(state->socket, reinterpret_cast<sockaddr*>(&address), &size) == SOCKET_ERROR)
        winError("Reading test port", WSAGetLastError());
    if (::listen(state->socket, 4) == SOCKET_ERROR) winError("Listening on test socket", WSAGetLastError());
    makeNonblocking(state->socket);
    state->phase = Phase::Listening;
    return state;
}
struct Pair {
    std::shared_ptr<SocketState> first, second;
    Pair() {
        sockaddr_in address{};
        auto server = listener(address);
        first = tcpSocket();
        if (::connect(first->socket, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == SOCKET_ERROR)
            winError("Connecting test socket", WSAGetLastError());
        makeNonblocking(first->socket);
        first->phase = Phase::Connected;
        second = acceptSocket(server);
    }
};
void closeState(const std::shared_ptr<SocketState>& state) {
    std::lock_guard<std::mutex> lock(state->mutex);
    int error = state->closeSocket();
    if (error) winError("Closing test socket", error);
}
void bufferedRoundTrip() {
    Pair pair;
    std::vector<char> expected(3 * maximumIoChunk + 37);
    for (std::size_t i = 0; i < expected.size(); ++i) expected[i] = static_cast<char>((i * 17) & 255);
    auto reader = std::async(std::launch::async, [&] {
        std::vector<char> received(expected.size());
        int offset = 0;
        while (offset < static_cast<int>(received.size())) {
            int count = readSocket(pair.second, received.data() + offset, std::min(maximumIoChunk, static_cast<int>(received.size()) - offset));
            if (count <= 0) throw std::runtime_error("Premature test EOF");
            offset += count;
        }
        return received;
    });
    writeSocket(pair.first, static_cast<int>(expected.size()), [&](int offset, int count, char* buffer) {
        std::memcpy(buffer, expected.data() + offset, static_cast<std::size_t>(count));
        return true;
    });
    bool finished = reader.wait_for(std::chrono::seconds(2)) == std::future_status::ready;
    if (!finished) closeState(pair.second);
    check(finished, "Native stream transfer did not finish");
    check(reader.get() == expected, "Native chunked stream changed binary payload");
    closeState(pair.first);
    char byte{};
    check(readSocket(pair.second, &byte, 1) == -1, "Orderly peer close did not produce EOF");
}
void cancelReadAndRejectStaleHandle() {
    Pair original;
    jlong oldHandle = registerState(original.second);
    auto reference = lookup(oldHandle);
    auto reader = std::async(std::launch::async, [&] {
        char byte{};
        try { readSocket(reference, &byte, 1); return false; }
        catch (const NativeError&) { return true; }
    });
    bool waiting = reader.wait_for(std::chrono::milliseconds(75)) == std::future_status::timeout;
    closeState(remove(oldHandle));
    bool finished = reader.wait_for(std::chrono::seconds(2)) == std::future_status::ready;
    check(waiting, "Native read did not wait for data");
    check(finished && reader.get(), "Closing did not interrupt native read");
    bool rejected = false;
    try { lookup(oldHandle); } catch (const NativeError&) { rejected = true; }
    check(rejected, "Closed native handle was still accepted");
    Pair replacement;
    jlong newHandle = registerState(replacement.second);
    check(newHandle > oldHandle, "Native handle IDs were reused");
    check(!remove(oldHandle), "Repeated stale close returned a different socket");
    writeSocket(replacement.first, 1, [](int, int, char* buffer) { buffer[0] = 42; return true; });
    char actual{};
    check(readSocket(lookup(newHandle), &actual, 1) == 1 && actual == 42, "Stale handle closed a replacement socket");
    closeState(remove(newHandle));
}
void cancelAccept() {
    sockaddr_in address{};
    auto server = listener(address);
    auto accepting = std::async(std::launch::async, [&] {
        try { acceptSocket(server); return false; }
        catch (const NativeError&) { return true; }
    });
    bool waiting = accepting.wait_for(std::chrono::milliseconds(75)) == std::future_status::timeout;
    closeState(server);
    bool finished = accepting.wait_for(std::chrono::seconds(2)) == std::future_status::ready;
    check(waiting, "Native accept did not wait for a peer");
    check(finished && accepting.get(), "Closing did not interrupt native accept");
}
void cancelBlockedWrite() {
    Pair pair;
    int smallBuffer = 4096;
    if (setsockopt(pair.first->socket, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<char*>(&smallBuffer), sizeof(smallBuffer)) == SOCKET_ERROR)
        winError("Limiting test send buffer", WSAGetLastError());
    auto writing = std::async(std::launch::async, [&] {
        try {
            writeSocket(pair.first, 16 * 1024 * 1024, [](int, int count, char* buffer) {
                std::memset(buffer, 7, static_cast<std::size_t>(count));
                return true;
            });
            return false;
        } catch (const NativeError&) { return true; }
    });
    bool waiting = writing.wait_for(std::chrono::milliseconds(150)) == std::future_status::timeout;
    closeState(pair.first);
    bool finished = writing.wait_for(std::chrono::seconds(2)) == std::future_status::ready;
    check(waiting, "Native write did not backpressure against an unread peer");
    check(finished && writing.get(), "Closing did not interrupt a blocked native write");
}
} // namespace

namespace {
struct BlockingLookup {
    std::mutex mutex;
    std::condition_variable changed;
    std::promise<void> entered;
    bool ended = false;
    int ends = 0;
};
int WSAAPI blockedLookupNext(HANDLE handle, DWORD, DWORD*, WSAQUERYSETW*) {
    auto& lookup = *static_cast<BlockingLookup*>(handle);
    std::unique_lock<std::mutex> lock(lookup.mutex);
    lookup.entered.set_value();
    lookup.changed.wait(lock, [&] { return lookup.ended; });
    WSASetLastError(WSA_E_CANCELLED);
    return SOCKET_ERROR;
}
int WSAAPI cancelLookup(HANDLE handle) {
    auto& lookup = *static_cast<BlockingLookup*>(handle);
    std::lock_guard<std::mutex> lock(lookup.mutex);
    lookup.ended = true; ++lookup.ends; lookup.changed.notify_all();
    return 0;
}
void cancelInquiry() {
    BlockingLookup backend;
    auto inquiry = std::make_shared<InquiryState>(blockedLookupNext, cancelLookup);
    inquiry->query = &backend;
    auto entered = backend.entered.get_future();
    jlong handle = registerInquiry(inquiry);
    auto reader = std::async(std::launch::async, [&] {
        DWORD size = sizeof(WSAQUERYSETW); WSAQUERYSETW result{};
        try { inquiry->next(size, &result); return false; }
        catch (const NativeError&) { return true; }
    });
    bool waiting = entered.wait_for(std::chrono::seconds(2)) == std::future_status::ready;
    auto removed = removeInquiry(handle);
    check(removed && removed->close() == 0, "Inquiry cancel did not end the provider lookup");
    bool finished = reader.wait_for(std::chrono::seconds(2)) == std::future_status::ready;
    check(waiting && finished && reader.get(), "Cancel did not interrupt blocked native inquiry");
    inquiry->close();
    check(backend.ends == 1, "Repeated inquiry close ended the provider twice");
    check(!removeInquiry(handle), "Closed inquiry stayed registered");
    auto replacement = std::make_shared<InquiryState>();
    jlong next = registerInquiry(replacement);
    check(next > handle && !removeInquiry(handle), "Stale inquiry cancellation affected a new scan");
    removeInquiry(next)->close();
    bool rejected = false;
    try { replacement->begin(10); } catch (const NativeError&) { rejected = true; }
    check(rejected, "An inquiry canceled before starting still reached the provider");
    check(inquiries.empty(), "Inquiry test leaked handles");
}
}

int main() {
    try {
        requireWinsock();
        bufferedRoundTrip();
        cancelReadAndRejectStaleHandle();
        cancelAccept();
        cancelBlockedWrite();
        cancelInquiry();
        check(registry.empty(), "Native test leaked registered handles");
        std::cout << "Bluetooth native lifecycle: " << passed << " checks passed (loopback; no physical radio required)\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "Bluetooth native lifecycle failed: " << e.what() << '\n';
        return 1;
    }
}
