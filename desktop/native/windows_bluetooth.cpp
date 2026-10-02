// Classic Bluetooth RFCOMM for the Microsoft Windows Bluetooth stack.
// JNI handles are monotonic IDs in a shared_ptr registry, never native pointers.
// Every socket is nonblocking. The state mutex covers each Winsock call, while
// cancellable condition-variable waits happen outside it. Closing a socket can
// therefore never make a subsequent operation act on a recycled SOCKET value.
#include <winsock2.h>
#include <ws2bth.h>
#include <windows.h>
#include <bluetoothapis.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <limits>
#include <iterator>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
const GUID serviceUuid = {0x90c649e1, 0xc095, 0x4b22, {0x8b, 0xc3, 0x35, 0xe4, 0xc9, 0xc7, 0xb3, 0x72}};
constexpr auto pollInterval = std::chrono::milliseconds(20);
constexpr int maximumIoChunk = 64 * 1024;

struct NativeError : std::runtime_error { using std::runtime_error::runtime_error; };
struct InvalidArgument : NativeError { using NativeError::NativeError; };
struct ConnectTimeout : NativeError { using NativeError::NativeError; };

[[noreturn]] void winError(const char* operation, int code) {
    throw NativeError(std::string(operation) + " failed (Windows error " + std::to_string(code) + ")");
}
void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}
template<typename Result, typename Function>
Result guarded(JNIEnv* env, Result fallback, Function&& function) {
    try { return function(); }
    catch (const InvalidArgument& e) { throwJava(env, "java/lang/IllegalArgumentException", e.what()); }
    catch (const ConnectTimeout& e) { throwJava(env, "java/net/SocketTimeoutException", e.what()); }
    catch (const std::bad_alloc&) { throwJava(env, "java/lang/OutOfMemoryError", "Bluetooth native allocation failed"); }
    catch (const std::exception& e) { throwJava(env, "java/io/IOException", e.what()); }
    catch (...) { throwJava(env, "java/io/IOException", "Unexpected Windows Bluetooth native failure"); }
    return fallback;
}
template<typename Function>
void guardedVoid(JNIEnv* env, Function&& function) {
    guarded<int>(env, 0, [&] { function(); return 1; });
}

std::once_flag winsockOnce;
int winsockFailure = 0;
void requireWinsock() {
    std::call_once(winsockOnce, [] {
        WSADATA data{};
        winsockFailure = WSAStartup(MAKEWORD(2, 2), &data);
    });
    if (winsockFailure) winError("Starting Windows sockets", winsockFailure);
}

struct Radio {
    HANDLE handle = nullptr;
    explicit Radio(HANDLE handle) : handle(handle) { }
    Radio(Radio&& other) noexcept : handle(std::exchange(other.handle, nullptr)) { }
    Radio& operator=(Radio&&) = delete;
    Radio(const Radio&) = delete;
    ~Radio() { if (handle) CloseHandle(handle); }
};
std::vector<Radio> radios() {
    BLUETOOTH_FIND_RADIO_PARAMS parameters{};
    parameters.dwSize = sizeof(parameters);
    HANDLE handle = nullptr;
    HBLUETOOTH_RADIO_FIND find = BluetoothFindFirstRadio(&parameters, &handle);
    if (!find) {
        DWORD error = GetLastError();
        if (error == ERROR_NO_MORE_ITEMS || error == ERROR_SUCCESS) return {};
        winError("Enumerating Bluetooth radios", static_cast<int>(error));
    }
    struct FindOwner {
        HBLUETOOTH_RADIO_FIND handle;
        ~FindOwner() { BluetoothFindRadioClose(handle); }
    } owner{find};
    std::vector<Radio> result;
    do {
        Radio radio(handle);
        result.push_back(std::move(radio));
        handle = nullptr;
    } while (BluetoothFindNextRadio(find, &handle));
    DWORD error = GetLastError();
    if (error != ERROR_NO_MORE_ITEMS && error != ERROR_SUCCESS)
        winError("Enumerating the next Bluetooth radio", static_cast<int>(error));
    return result;
}
void requireRadio() {
    requireWinsock();
    if (radios().empty()) throw NativeError("No enabled Windows Bluetooth radio; turn on Bluetooth or attach a compatible adapter");
}

std::string addressText(BTH_ADDR address) {
    char result[18];
    std::snprintf(result, sizeof(result), "%02X:%02X:%02X:%02X:%02X:%02X",
        static_cast<unsigned>((address >> 40) & 255), static_cast<unsigned>((address >> 32) & 255),
        static_cast<unsigned>((address >> 24) & 255), static_cast<unsigned>((address >> 16) & 255),
        static_cast<unsigned>((address >> 8) & 255), static_cast<unsigned>(address & 255));
    return result;
}
BTH_ADDR parseAddress(JNIEnv* env, jstring source) {
    if (!source) throw InvalidArgument("Bluetooth address is null");
    if (env->GetStringLength(source) != 17) throw InvalidArgument("Invalid Bluetooth MAC address");
    std::array<jchar, 17> chars{};
    env->GetStringRegion(source, 0, 17, chars.data());
    if (env->ExceptionCheck()) throw NativeError("Could not read Bluetooth address");
    auto nibble = [](jchar c) -> unsigned {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw InvalidArgument("Invalid Bluetooth MAC address");
    };
    BTH_ADDR result = 0;
    for (int i = 0; i < 6; ++i) {
        int offset = i * 3;
        if (i < 5 && chars[offset + 2] != ':') throw InvalidArgument("Invalid Bluetooth MAC address");
        result = (result << 8) | (nibble(chars[offset]) << 4) | nibble(chars[offset + 1]);
    }
    if (!result || result == 0xffffffffffffULL) throw InvalidArgument("Bluetooth address must identify a device");
    return result;
}

enum class Phase { Open, Connecting, Connected, Listening, Closed };
struct SocketState {
    std::mutex mutex;
    std::mutex writeMutex;
    std::mutex readMutex;
    std::condition_variable changed;
    SOCKET socket = INVALID_SOCKET;
    Phase phase = Phase::Open;
    bool registered = false;
    SOCKADDR_BTH local{};

    // The record is generated by NS_BTH from this service UUID and RFCOMM address.
    int serviceOperation(WSAESETSERVICEOP operation) {
        GUID uuid = serviceUuid;
        wchar_t name[] = L"NearbyIM";
        wchar_t comment[] = L"WoZai authenticated messaging";
        CSADDR_INFO address{};
        address.LocalAddr.lpSockaddr = reinterpret_cast<sockaddr*>(&local);
        address.LocalAddr.iSockaddrLength = sizeof(local);
        address.iSocketType = SOCK_STREAM;
        address.iProtocol = BTHPROTO_RFCOMM;
        WSAQUERYSETW query{};
        query.dwSize = sizeof(query);
        query.lpszServiceInstanceName = name;
        query.lpszComment = comment;
        query.lpServiceClassId = &uuid;
        query.dwNameSpace = NS_BTH;
        query.dwNumberOfCsAddrs = 1;
        query.lpcsaBuffer = &address;
        return WSASetServiceW(&query, operation, 0) == SOCKET_ERROR ? WSAGetLastError() : 0;
    }
    // Caller owns mutex, or the state has no remaining users (destructor).
    int closeSocket() noexcept {
        int firstError = 0;
        phase = Phase::Closed;
        if (registered) { firstError = serviceOperation(RNRSERVICE_DELETE); registered = false; }
        if (socket != INVALID_SOCKET) {
            // closesocket cancels all operations; no blocking calls hold this mutex.
            if (closesocket(socket) == SOCKET_ERROR && !firstError) firstError = WSAGetLastError();
            socket = INVALID_SOCKET;
        }
        changed.notify_all();
        return firstError;
    }
    ~SocketState() { closeSocket(); }
    void requireOpen() const {
        if (phase == Phase::Closed || socket == INVALID_SOCKET) throw NativeError("Bluetooth socket is closed");
    }
    void requireConnected() const {
        requireOpen();
        if (phase != Phase::Connected) throw NativeError("Bluetooth socket is not connected");
    }
};

std::mutex registryMutex;
std::map<jlong, std::shared_ptr<SocketState>> registry;
jlong nextHandle = 1;
jlong registerState(std::shared_ptr<SocketState> state) {
    std::lock_guard<std::mutex> lock(registryMutex);
    if (nextHandle == std::numeric_limits<jlong>::max()) throw NativeError("Bluetooth handle IDs exhausted");
    jlong handle = nextHandle++;
    registry.emplace(handle, std::move(state));
    return handle;
}
std::shared_ptr<SocketState> lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = registry.find(handle);
    if (handle <= 0 || found == registry.end()) throw NativeError("Bluetooth socket is closed or the handle is invalid");
    return found->second;
}
std::shared_ptr<SocketState> remove(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = registry.find(handle);
    if (found == registry.end()) return {};
    auto result = found->second;
    registry.erase(found);
    return result;
}
void makeNonblocking(SOCKET socket) {
    u_long nonblocking = 1;
    if (ioctlsocket(socket, FIONBIO, &nonblocking) == SOCKET_ERROR)
        winError("Configuring cancellable Bluetooth I/O", WSAGetLastError());
}
void secureSocket(SOCKET socket) {
    ULONG enabled = TRUE;
    if (setsockopt(socket, SOL_RFCOMM, SO_BTH_AUTHENTICATE, reinterpret_cast<const char*>(&enabled), sizeof(enabled)) == SOCKET_ERROR)
        winError("Requiring Bluetooth authentication", WSAGetLastError());
    if (setsockopt(socket, SOL_RFCOMM, SO_BTH_ENCRYPT, reinterpret_cast<const char*>(&enabled), sizeof(enabled)) == SOCKET_ERROR)
        winError("Requiring Bluetooth encryption", WSAGetLastError());
    makeNonblocking(socket);
}
std::shared_ptr<SocketState> newSocket() {
    requireWinsock();
    auto state = std::make_shared<SocketState>();
    state->socket = ::socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (state->socket == INVALID_SOCKET) winError("Opening Bluetooth RFCOMM socket", WSAGetLastError());
    secureSocket(state->socket);
    return state;
}
void pause(std::shared_ptr<SocketState>& state, std::unique_lock<std::mutex>& lock) {
    state->changed.wait_for(lock, pollInterval, [&] { return state->phase == Phase::Closed; });
    state->requireOpen();
}
void validateBuffer(JNIEnv* env, jbyteArray array, jint offset, jint length) {
    if (!array) throw InvalidArgument("Bluetooth I/O buffer is null");
    jsize size = env->GetArrayLength(array);
    if (offset < 0 || length < 0 || offset > size || length > size - offset)
        throw InvalidArgument("Bluetooth I/O buffer range is invalid");
}

std::shared_ptr<SocketState> acceptSocket(std::shared_ptr<SocketState> server) {
    std::unique_lock<std::mutex> lock(server->mutex);
    for (;;) {
        server->requireOpen();
        if (server->phase != Phase::Listening) throw NativeError("Bluetooth socket is not a listener");
        // Allocate the owner before accept so allocation failures cannot leak a SOCKET.
        auto accepted = std::make_shared<SocketState>();
        accepted->socket = ::accept(server->socket, nullptr, nullptr);
        if (accepted->socket != INVALID_SOCKET) {
            // Authentication and encryption are required by the listening socket,
            // before accept completes. Reapplying them here could start pairing twice.
            makeNonblocking(accepted->socket);
            accepted->phase = Phase::Connected;
            return accepted;
        }
        int error = WSAGetLastError();
        if (error != WSAEWOULDBLOCK) winError("Accepting Bluetooth connection", error);
        pause(server, lock);
    }
}

int readSocket(std::shared_ptr<SocketState> state, char* buffer, int length) {
    std::lock_guard<std::mutex> reader(state->readMutex);
    std::unique_lock<std::mutex> lock(state->mutex);
    state->requireConnected();
    if (!length) return 0;
    for (;;) {
        state->requireConnected();
        int count = recv(state->socket, buffer, length, 0);
        if (count > 0) return count;
        if (count == 0) return -1;
        int error = WSAGetLastError();
        if (error != WSAEWOULDBLOCK) winError("Reading Bluetooth stream", error);
        pause(state, lock);
    }
}

template<typename FillBuffer>
void writeSocket(std::shared_ptr<SocketState> state, int length, FillBuffer&& fill) {
    std::lock_guard<std::mutex> writer(state->writeMutex);
    std::vector<char> buffer(static_cast<std::size_t>(std::min(length, maximumIoChunk)));
    std::unique_lock<std::mutex> lock(state->mutex);
    state->requireConnected();
    int position = 0;
    while (position < length) {
        int chunk = std::min(length - position, maximumIoChunk);
        if (!fill(position, chunk, buffer.data())) return;
        int written = 0;
        while (written < chunk) {
            state->requireConnected();
            int count = send(state->socket, buffer.data() + written, chunk - written, 0);
            if (count > 0) { written += count; continue; }
            if (count == 0) throw NativeError("Bluetooth stream closed during write");
            int error = WSAGetLastError();
            if (error != WSAEWOULDBLOCK) winError("Writing Bluetooth stream", error);
            pause(state, lock);
        }
        position += chunk;
    }
}

struct FoundDevice { BTH_ADDR address; std::wstring name; bool paired; };
std::vector<FoundDevice> scanDevices(int seconds) {
    if (seconds < 1 || seconds > 30) throw InvalidArgument("Inquiry duration must be 1..30 seconds");
    auto adapters = radios();
    if (adapters.empty()) throw NativeError("No enabled Windows Bluetooth radio");
    std::map<BTH_ADDR, FoundDevice> found;
    // Inquire on one radio, then include the cached/paired devices from every radio.
    // Running an inquiry per adapter would multiply the user's bounded duration.
    for (std::size_t index = 0; index < adapters.size(); ++index) {
        BLUETOOTH_DEVICE_SEARCH_PARAMS search{};
        search.dwSize = sizeof(search);
        search.fReturnAuthenticated = TRUE;
        search.fReturnRemembered = TRUE;
        search.fReturnUnknown = TRUE;
        search.fReturnConnected = TRUE;
        search.fIssueInquiry = index == 0;
        search.cTimeoutMultiplier = static_cast<UCHAR>(std::max(1, (seconds * 100) / 128));
        search.hRadio = adapters[index].handle;
        BLUETOOTH_DEVICE_INFO device{};
        device.dwSize = sizeof(device);
        HBLUETOOTH_DEVICE_FIND find = BluetoothFindFirstDevice(&search, &device);
        if (!find) {
            DWORD error = GetLastError();
            if (error == ERROR_NO_MORE_ITEMS || error == ERROR_SUCCESS) continue;
            winError("Searching Bluetooth devices", static_cast<int>(error));
        }
        struct FindOwner {
            HBLUETOOTH_DEVICE_FIND handle;
            ~FindOwner() { BluetoothFindDeviceClose(handle); }
        } owner{find};
        do {
            BTH_ADDR mac = device.Address.ullLong;
            if (!mac || mac == 0xffffffffffffULL) continue;
            bool paired = device.fAuthenticated != FALSE;
            auto previous = found.find(mac);
            if (previous != found.end()) paired = paired || previous->second.paired;
            std::size_t nameLength = 0;
            while (nameLength < std::size(device.szName) && device.szName[nameLength]) ++nameLength;
            std::wstring name(device.szName, nameLength);
            if (name.empty() && previous != found.end()) name = previous->second.name;
            found[mac] = FoundDevice{mac, std::move(name), paired};
            device = {};
            device.dwSize = sizeof(device);
        } while (BluetoothFindNextDevice(find, &device));
        DWORD error = GetLastError();
        if (error != ERROR_NO_MORE_ITEMS && error != ERROR_SUCCESS)
            winError("Enumerating Bluetooth devices", static_cast<int>(error));
    }
    std::vector<FoundDevice> result;
    for (auto& item : found) result.push_back(std::move(item.second));
    return result;
}
} // namespace

extern "C" {
JNIEXPORT jint JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeVersion(JNIEnv*, jclass) { return 1; }

JNIEXPORT jstring JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeStatus(JNIEnv* env, jclass) {
    return guarded<jstring>(env, nullptr, [&]() -> jstring {
        requireWinsock();
        auto adapters = radios();
        if (adapters.empty()) return env->NewStringUTF("No enabled Windows Bluetooth radio; turn on Bluetooth or attach a compatible adapter");
        for (const auto& radio : adapters) {
            BLUETOOTH_RADIO_INFO info{};
            info.dwSize = sizeof(info);
            DWORD error = BluetoothGetRadioInfo(radio.handle, &info);
            if (error == ERROR_SUCCESS) return nullptr;
        }
        return env->NewStringUTF("Windows Bluetooth radios could not be queried; check the adapter and Bluetooth service");
    });
}

JNIEXPORT jobjectArray JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeScan(JNIEnv* env, jclass, jint seconds) {
    return guarded<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        requireWinsock();
        auto found = scanDevices(seconds);
        jclass type = env->FindClass("dev/ghost/wozai/WindowsBluetooth$Device");
        if (!type) return nullptr;
        jmethodID constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;Ljava/lang/String;Z)V");
        if (!constructor) { env->DeleteLocalRef(type); return nullptr; }
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(found.size()), type, nullptr);
        if (!result) { env->DeleteLocalRef(type); return nullptr; }
        for (jsize i = 0; i < static_cast<jsize>(found.size()); ++i) {
            const auto& device = found[static_cast<std::size_t>(i)];
            jstring address = env->NewStringUTF(addressText(device.address).c_str());
            jstring name = env->NewString(reinterpret_cast<const jchar*>(device.name.data()), static_cast<jsize>(device.name.size()));
            if (!address || !name) {
                if (address) env->DeleteLocalRef(address);
                if (name) env->DeleteLocalRef(name);
                env->DeleteLocalRef(type);
                return nullptr;
            }
            jobject entry = env->NewObject(type, constructor, address, name, static_cast<jboolean>(device.paired));
            env->DeleteLocalRef(address);
            env->DeleteLocalRef(name);
            if (!entry) { env->DeleteLocalRef(type); return nullptr; }
            env->SetObjectArrayElement(result, i, entry);
            env->DeleteLocalRef(entry);
            if (env->ExceptionCheck()) { env->DeleteLocalRef(type); return nullptr; }
        }
        env->DeleteLocalRef(type);
        return result;
    });
}

JNIEXPORT jlong JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeOpen(JNIEnv* env, jclass) {
    return guarded<jlong>(env, 0, [&] { requireRadio(); return registerState(newSocket()); });
}

JNIEXPORT jlong JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeListen(JNIEnv* env, jclass) {
    return guarded<jlong>(env, 0, [&] {
        requireRadio();
        auto state = newSocket();
        state->local.addressFamily = AF_BTH;
        state->local.port = BT_PORT_ANY;
        if (bind(state->socket, reinterpret_cast<sockaddr*>(&state->local), sizeof(state->local)) == SOCKET_ERROR)
            winError("Binding Bluetooth listener", WSAGetLastError());
        int length = sizeof(state->local);
        if (getsockname(state->socket, reinterpret_cast<sockaddr*>(&state->local), &length) == SOCKET_ERROR)
            winError("Reading Bluetooth listener channel", WSAGetLastError());
        if (::listen(state->socket, 4) == SOCKET_ERROR) winError("Listening for Bluetooth connections", WSAGetLastError());
        int error = state->serviceOperation(RNRSERVICE_REGISTER);
        if (error) winError("Publishing NearbyIM Bluetooth service", error);
        state->registered = true;
        state->phase = Phase::Listening;
        return registerState(std::move(state));
    });
}

JNIEXPORT void JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeConnect(JNIEnv* env, jclass, jlong handle, jstring remote, jint timeout) {
    guardedVoid(env, [&] {
        if (timeout < 1 || timeout > 120000) throw InvalidArgument("Connection timeout must be 1..120000 milliseconds");
        BTH_ADDR address = parseAddress(env, remote);
        auto state = lookup(handle);
        std::unique_lock<std::mutex> lock(state->mutex);
        state->requireOpen();
        if (state->phase != Phase::Open) throw NativeError("Bluetooth connection has already been started");
        state->phase = Phase::Connecting;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout);
        try {
            SOCKADDR_BTH target{};
            target.addressFamily = AF_BTH;
            target.btAddr = address;
            target.serviceClassId = serviceUuid;
            // A zero channel instructs Windows to resolve the Android service UUID via SDP.
            target.port = 0;
            int result = ::connect(state->socket, reinterpret_cast<sockaddr*>(&target), sizeof(target));
            if (result == 0) { state->phase = Phase::Connected; return; }
            int error = WSAGetLastError();
            if (error != WSAEWOULDBLOCK && error != WSAEINPROGRESS && error != WSAEALREADY)
                winError("Connecting Bluetooth device; confirm pairing on both devices and enable receiving", error);
            for (;;) {
                state->requireOpen();
                fd_set writable, errors;
                FD_ZERO(&writable); FD_ZERO(&errors);
                FD_SET(state->socket, &writable); FD_SET(state->socket, &errors);
                timeval immediate{};
                int selected = select(0, nullptr, &writable, &errors, &immediate);
                if (selected == SOCKET_ERROR) winError("Waiting for Bluetooth connection", WSAGetLastError());
                if (selected > 0) {
                    int connectedError = 0;
                    int size = sizeof(connectedError);
                    if (getsockopt(state->socket, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&connectedError), &size) == SOCKET_ERROR)
                        winError("Checking Bluetooth connection", WSAGetLastError());
                    if (connectedError) winError("Connecting Bluetooth device; confirm pairing on both devices and enable receiving", connectedError);
                    if (FD_ISSET(state->socket, &errors)) throw NativeError("Windows rejected the Bluetooth connection");
                    state->phase = Phase::Connected;
                    return;
                }
                if (std::chrono::steady_clock::now() >= deadline)
                    throw ConnectTimeout("Bluetooth connection timed out; confirm pairing on both devices and enable receiving");
                auto remaining = deadline - std::chrono::steady_clock::now();
                state->changed.wait_for(lock, std::min(std::chrono::duration_cast<std::chrono::milliseconds>(remaining), pollInterval),
                    [&] { return state->phase == Phase::Closed; });
            }
        } catch (...) {
            // A failed socket is unusable; keeping it open would continue pairing in the background.
            state->closeSocket();
            throw;
        }
    });
}

JNIEXPORT jlong JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeAccept(JNIEnv* env, jclass, jlong handle) {
    return guarded<jlong>(env, 0, [&] {
        return registerState(acceptSocket(lookup(handle)));
    });
}

JNIEXPORT jstring JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeRemoteAddress(JNIEnv* env, jclass, jlong handle) {
    return guarded<jstring>(env, nullptr, [&]() -> jstring {
        auto state = lookup(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        state->requireConnected();
        SOCKADDR_BTH address{};
        int length = sizeof(address);
        if (getpeername(state->socket, reinterpret_cast<sockaddr*>(&address), &length) == SOCKET_ERROR)
            winError("Reading Bluetooth remote address", WSAGetLastError());
        return env->NewStringUTF(addressText(address.btAddr).c_str());
    });
}

JNIEXPORT void JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeRequireConnected(JNIEnv* env, jclass, jlong handle) {
    guardedVoid(env, [&] {
        auto state = lookup(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        state->requireConnected();
    });
}

JNIEXPORT jint JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeRead(JNIEnv* env, jclass, jlong handle, jbyteArray bytes, jint offset, jint length) {
    return guarded<jint>(env, -1, [&]() -> jint {
        validateBuffer(env, bytes, offset, length);
        auto state = lookup(handle);
        std::vector<char> buffer(static_cast<std::size_t>(std::min(length, maximumIoChunk)));
        int count = readSocket(std::move(state), buffer.data(), static_cast<int>(buffer.size()));
        if (count > 0) env->SetByteArrayRegion(bytes, offset, count, reinterpret_cast<const jbyte*>(buffer.data()));
        return count;
    });
}

JNIEXPORT void JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeWrite(JNIEnv* env, jclass, jlong handle, jbyteArray bytes, jint offset, jint length) {
    guardedVoid(env, [&] {
        validateBuffer(env, bytes, offset, length);
        auto state = lookup(handle);
        writeSocket(std::move(state), length, [&](int position, int chunk, char* buffer) {
            env->GetByteArrayRegion(bytes, offset + position, chunk, reinterpret_cast<jbyte*>(buffer));
            return !env->ExceptionCheck();
        });
    });
}

JNIEXPORT void JNICALL Java_dev_ghost_wozai_WindowsBluetooth_nativeClose(JNIEnv* env, jclass, jlong handle) {
    guardedVoid(env, [&] {
        auto state = remove(handle);
        if (!state) return; // Java and native close are independently idempotent.
        std::lock_guard<std::mutex> lock(state->mutex);
        int error = state->closeSocket();
        if (error) winError("Closing Bluetooth socket or removing its service record", error);
    });
}
} // extern "C"
