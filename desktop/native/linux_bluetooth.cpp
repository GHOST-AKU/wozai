// BlueZ owns pairing, SDP and RFCOMM creation. Profile1 delivers Unix descriptors;
// no raw HCI permissions, root process or private bluetoothd compatibility mode.
#include <jni.h>
#include <gio/gio.h>
#include <gio/gunixfdlist.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr const char* serviceUuid = "90c649e1-c095-4b22-8bc3-35e4c9c7b372";
constexpr int maximumIoChunk = 65536;
using namespace std::chrono_literals;
struct NativeError : std::runtime_error { using std::runtime_error::runtime_error; };
struct InvalidArgument : NativeError { using NativeError::NativeError; };
struct ConnectTimeout : NativeError { using NativeError::NativeError; };
struct Variant {
    GVariant* value;
    explicit Variant(GVariant* v) : value(v) { }
    ~Variant() { if (value) g_variant_unref(value); }
    Variant(const Variant&) = delete;
    operator GVariant*() const { return value; }
};
enum class Kind { Inquiry, Socket, Server };
struct State {
    explicit State(Kind k) : kind(k), cancel(g_cancellable_new()) { }
    Kind kind;
    std::mutex mutex, readMutex, writeMutex;
    std::condition_variable changed;
    bool closed = false, connecting = false, connected = false, scanning = false;
    int fd = -1;
    GCancellable* cancel;
    std::string address, device, adapter, profile;
    std::deque<std::shared_ptr<State>> pending;
    void closeLocal() {
        std::lock_guard<std::mutex> lock(mutex);
        closed = true; g_cancellable_cancel(cancel);
        if (fd >= 0) { shutdown(fd, SHUT_RDWR); ::close(fd); fd = -1; }
        changed.notify_all();
    }
    ~State() { closeLocal(); g_object_unref(cancel); }
};
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<State>> registry;
jlong nextHandle = 1;
jlong addState(const std::shared_ptr<State>& state) {
    std::lock_guard<std::mutex> lock(registryMutex);
    jlong id = nextHandle++; registry.emplace(id, state); return id;
}
std::shared_ptr<State> lookup(jlong id, Kind kind) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto i = registry.find(id);
    if (i == registry.end() || i->second->kind != kind) throw NativeError("Bluetooth handle is closed or has the wrong type");
    return i->second;
}
std::shared_ptr<State> removeState(jlong id, bool inquiry = false) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto i = registry.find(id); if (i == registry.end()) return {};
    if ((i->second->kind == Kind::Inquiry) != inquiry) throw NativeError("Bluetooth handle has the wrong type");
    auto state = i->second; registry.erase(i); return state;
}
void requireActive(const std::shared_ptr<State>& state) {
    std::lock_guard<std::mutex> lock(state->mutex);
    if (state->closed) throw NativeError("Bluetooth operation was cancelled");
}
void nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0 || fcntl(fd, F_SETFD, FD_CLOEXEC) < 0)
        throw NativeError("Unable to configure Bluetooth descriptor");
}
std::string normalizeAddress(std::string address) {
    if (address.size() != 17) throw InvalidArgument("Invalid Bluetooth address");
    for (size_t i = 0; i < address.size(); ++i) {
        if (i % 3 == 2) { if (address[i] != ':') throw InvalidArgument("Invalid Bluetooth address"); }
        else {
            char c = address[i];
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')))
                throw InvalidArgument("Invalid Bluetooth address");
            if (c >= 'a' && c <= 'f') address[i] = c - 'a' + 'A';
        }
    }
    if (address == "00:00:00:00:00:00" || address == "FF:FF:FF:FF:FF:FF") throw InvalidArgument("Invalid Bluetooth device");
    return address;
}
std::string addressFromPath(const std::string& path) {
    size_t at = path.rfind("/dev_");
    if (at == std::string::npos) throw NativeError("BlueZ supplied an invalid device path");
    auto address = path.substr(at + 5); std::replace(address.begin(), address.end(), '_', ':');
    return normalizeAddress(address);
}

class Runtime {
    struct Profile {
        std::shared_ptr<State> server;
        guint object;
        std::vector<std::weak_ptr<State>> sockets;
    };
    GMainContext* context = nullptr;
    GMainLoop* loop = nullptr;
    GDBusConnection* bus = nullptr;
    GDBusNodeInfo* info = nullptr;
    std::string owner;
    std::map<std::string, Profile> profiles; // Access only on the GLib event thread.
    std::atomic<unsigned long> serial{1};
    std::map<std::string, std::string> disconnecting; // Device -> owning profile, event thread only.
    std::mutex watchedMutex;
    std::vector<std::weak_ptr<State>> watched;
    void invalidate() {
        std::vector<std::shared_ptr<State>> states;
        {
            std::lock_guard<std::mutex> lock(watchedMutex);
            for (auto& weak : watched) if (auto state = weak.lock()) states.push_back(state);
            watched.clear();
        }
        for (auto& item : profiles) {
            if (item.second.server) states.push_back(item.second.server);
            for (auto& weak : item.second.sockets) if (auto state = weak.lock()) states.push_back(state);
            g_dbus_connection_unregister_object(bus, item.second.object);
        }
        profiles.clear(); disconnecting.clear();
        for (auto& state : states) {
            state->closeLocal();
            std::lock_guard<std::mutex> lock(state->mutex); state->profile.clear();
        }
    }
    void stopMatching(const std::string& path, bool adapter) {
        std::vector<std::shared_ptr<State>> states;
        { std::lock_guard<std::mutex> lock(watchedMutex);
            for (auto& weak : watched) if (auto state = weak.lock()) states.push_back(state); }
        for (auto& state : states) {
            bool matches;
            { std::lock_guard<std::mutex> lock(state->mutex); matches = (adapter ? state->adapter : state->device) == path; }
            if (matches) {
                state->closeLocal();
                std::lock_guard<std::mutex> lock(state->mutex); state->profile.clear();
            }
        }
    }
    static void profileCall(GDBusConnection*, const gchar* sender, const gchar* path,
            const gchar*, const gchar* method, GVariant* args, GDBusMethodInvocation* call, gpointer data) {
        static_cast<Runtime*>(data)->dispatch(sender, path, method, args, call);
    }
    void dispatch(const char* sender, const char* path, const char* method, GVariant* args, GDBusMethodInvocation* call) {
        try {
            if (owner.empty() || owner != sender) throw NativeError("Profile caller is not the BlueZ service");
            auto found = profiles.find(path);
            if (found == profiles.end()) throw NativeError("Bluetooth profile was stopped");
            auto& profile = found->second;
            if (std::strcmp(method, "NewConnection") == 0) {
                const char* device; gint index; GVariant* properties;
                g_variant_get(args, "(&oh@a{sv})", &device, &index, &properties); g_variant_unref(properties);
                GUnixFDList* descriptors = g_dbus_message_get_unix_fd_list(g_dbus_method_invocation_get_message(call));
                if (!descriptors) throw NativeError("BlueZ supplied no connection descriptor");
                GError* error = nullptr; int fd = g_unix_fd_list_get(descriptors, index, &error);
                if (fd < 0) { std::string message = error->message; g_error_free(error); throw NativeError(message); }
                try {
                    std::string address = addressFromPath(device);
                    if (disconnecting.count(device)) throw NativeError("Bluetooth device is disconnecting");
                    nonblocking(fd);
                    std::shared_ptr<State> outgoing;
                    for (auto& weak : profile.sockets) if (auto socket = weak.lock()) {
                        std::lock_guard<std::mutex> lock(socket->mutex);
                        if (!socket->closed && socket->connecting && !socket->connected && socket->device == device) {
                            outgoing = socket; break;
                        }
                    }
                    if (outgoing) {
                        std::lock_guard<std::mutex> lock(outgoing->mutex);
                        if (outgoing->closed || outgoing->address != address) throw NativeError("Bluetooth connection was cancelled");
                        outgoing->fd = fd; outgoing->connected = true; fd = -1;
                        outgoing->changed.notify_all();
                    } else {
                        auto server = profile.server;
                        if (!server) throw NativeError("Bluetooth reception is disabled");
                        std::lock_guard<std::mutex> lock(server->mutex);
                        if (server->closed) throw NativeError("Bluetooth reception was stopped");
                        if (server->pending.size() >= 4) throw NativeError("Bluetooth reception queue is full");
                        auto child = std::make_shared<State>(Kind::Socket);
                        child->fd = fd; child->connected = true; child->address = address;
                        child->device = device; child->profile = path;
                        child->adapter = std::string(device).substr(0, std::string(device).rfind("/dev_"));
                        profile.sockets.erase(std::remove_if(profile.sockets.begin(), profile.sockets.end(), [](const auto& weak) { return weak.expired(); }), profile.sockets.end());
                        profile.sockets.push_back(child); watch(child);
                        server->pending.push_back(child); fd = -1; server->changed.notify_all();
                    }
                } catch (...) { if (fd >= 0) ::close(fd); throw; }
            } else if (std::strcmp(method, "RequestDisconnection") == 0) {
                const char* device; g_variant_get(args, "(&o)", &device);
                for (auto& weak : profile.sockets) if (auto socket = weak.lock()) {
                    bool matches;
                    { std::lock_guard<std::mutex> lock(socket->mutex); matches = socket->device == device; }
                    if (matches) {
                        socket->closeLocal();
                        std::lock_guard<std::mutex> lock(socket->mutex); socket->profile.clear();
                    }
                }
            } else if (std::strcmp(method, "Release") == 0) {
                invalidate();
            } else throw NativeError("Unknown BlueZ profile method");
            g_dbus_method_invocation_return_value(call, nullptr);
        } catch (const std::exception& e) { g_dbus_method_invocation_return_dbus_error(call, "org.bluez.Error.Rejected", e.what()); }
    }
public:
    std::timed_mutex discoveryMutex;
    Runtime() {
        std::promise<void> ready; auto future = ready.get_future();
        std::thread([this, ready = std::move(ready)]() mutable {
            try {
                context = g_main_context_new(); g_main_context_push_thread_default(context);
                GError* error = nullptr; gchar* address = g_dbus_address_get_for_bus_sync(G_BUS_TYPE_SYSTEM, nullptr, &error);
                if (!address) { std::string message = error->message; g_error_free(error); throw NativeError(message); }
                bus = g_dbus_connection_new_for_address_sync(address,
                        GDBusConnectionFlags(G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT | G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION), nullptr, nullptr, &error);
                g_free(address);
                if (!bus) { std::string message = error->message; g_error_free(error); throw NativeError(message); }
                g_dbus_connection_set_exit_on_close(bus, FALSE);
                g_dbus_connection_signal_subscribe(bus, "org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged", "/org/freedesktop/DBus", "org.bluez", G_DBUS_SIGNAL_FLAGS_NONE,
                    [](GDBusConnection*, const gchar*, const gchar*, const gchar*, const gchar*, GVariant* args, gpointer data) {
                        const char *name, *oldOwner, *newOwner; g_variant_get(args, "(&s&s&s)", &name, &oldOwner, &newOwner);
                        auto& runtime = *static_cast<Runtime*>(data);
                        if (*oldOwner) runtime.invalidate();
                        runtime.owner = newOwner;
                    }, this, nullptr);
                g_dbus_connection_signal_subscribe(bus, "org.bluez", "org.freedesktop.DBus.Properties", "PropertiesChanged", nullptr, nullptr, G_DBUS_SIGNAL_FLAGS_NONE,
                    [](GDBusConnection*, const gchar*, const gchar* path, const gchar*, const gchar*, GVariant* args, gpointer data) {
                        const char* interface; GVariant *changed, *invalidated;
                        g_variant_get(args, "(&s@a{sv}@as)", &interface, &changed, &invalidated);
                        Variant values(changed), omitted(invalidated); gboolean powered = TRUE;
                        if (!std::strcmp(interface, "org.bluez.Adapter1") && g_variant_lookup(values, "Powered", "b", &powered) && !powered)
                            static_cast<Runtime*>(data)->stopMatching(path, true);
                    }, this, nullptr);
                g_dbus_connection_signal_subscribe(bus, "org.bluez", "org.freedesktop.DBus.ObjectManager", "InterfacesRemoved", nullptr, nullptr, G_DBUS_SIGNAL_FLAGS_NONE,
                    [](GDBusConnection*, const gchar*, const gchar*, const gchar*, const gchar*, GVariant* args, gpointer data) {
                        const char* path; GVariant* removed; g_variant_get(args, "(&o@as)", &path, &removed);
                        Variant values(removed); GVariantIter iter; g_variant_iter_init(&iter, values); const char* interface;
                        while (g_variant_iter_next(&iter, "&s", &interface)) {
                            if (!std::strcmp(interface, "org.bluez.Adapter1")) static_cast<Runtime*>(data)->stopMatching(path, true);
                            else if (!std::strcmp(interface, "org.bluez.Device1")) static_cast<Runtime*>(data)->stopMatching(path, false);
                        }
                    }, this, nullptr);
                g_signal_connect(bus, "closed", G_CALLBACK(+[](GDBusConnection*, gboolean, GError*, gpointer data) {
                    static_cast<Runtime*>(data)->invalidate();
                }), this);
                const char* xml = "<node><interface name='org.bluez.Profile1'>"
                    "<method name='Release'/><method name='NewConnection'><arg type='o' direction='in'/><arg type='h' direction='in'/><arg type='a{sv}' direction='in'/></method>"
                    "<method name='RequestDisconnection'><arg type='o' direction='in'/></method></interface></node>";
                info = g_dbus_node_info_new_for_xml(xml, nullptr);
                loop = g_main_loop_new(context, FALSE); ready.set_value(); g_main_loop_run(loop);
            } catch (...) { ready.set_exception(std::current_exception()); }
        }).detach();
        future.get();
    }
    static Runtime& instance() {
        // The bus and its dispatch loop live until process exit. BlueZ removes all
        // profiles when the process disconnects; no callbacks target freed memory.
        static Runtime* runtime = new Runtime(); return *runtime;
    }
    template<typename F> auto onLoop(F function) -> decltype(function()) {
        using T = decltype(function());
        auto task = new std::packaged_task<T()>(std::move(function)); auto future = task->get_future();
        g_main_context_invoke_full(context, G_PRIORITY_DEFAULT, [](gpointer data) -> gboolean {
            auto task = static_cast<std::packaged_task<T()>*>(data); (*task)(); return G_SOURCE_REMOVE;
        }, task, [](gpointer data) { delete static_cast<std::packaged_task<T()>*>(data); });
        return future.get();
    }
    GVariant* call(const std::string& path, const char* interface, const char* method,
            GVariant* parameters = nullptr, int timeout = 3000, GCancellable* cancel = nullptr, const char* destination = "org.bluez") {
        GError* error = nullptr;
        auto result = g_dbus_connection_call_sync(bus, destination, path.c_str(), interface, method,
                parameters, nullptr, G_DBUS_CALL_FLAGS_NONE, timeout, cancel, &error);
        if (!result) {
            bool timedOut = g_error_matches(error, G_IO_ERROR, G_IO_ERROR_TIMED_OUT) || g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_TIMEOUT);
            std::string message = std::string(method) + ": " + error->message; g_error_free(error);
            if (timedOut) throw ConnectTimeout(message);
            throw NativeError(message);
        }
        return result;
    }
    GVariant* objects() {
        Variant result(call("/", "org.freedesktop.DBus.ObjectManager", "GetManagedObjects"));
        return g_variant_get_child_value(result, 0);
    }
    void watch(const std::shared_ptr<State>& state) {
        std::lock_guard<std::mutex> lock(watchedMutex);
        watched.erase(std::remove_if(watched.begin(), watched.end(), [](const auto& weak) { return weak.expired(); }), watched.end());
        if (std::none_of(watched.begin(), watched.end(), [&](const auto& weak) { return weak.lock() == state; })) watched.push_back(state);
    }
    std::string poweredAdapter() {
        Variant tree(objects()); GVariantIter iter; g_variant_iter_init(&iter, tree);
        const char* path; GVariant* interfaces;
        while (g_variant_iter_next(&iter, "{&o@a{sa{sv}}}", &path, &interfaces)) {
            Variant all(interfaces); Variant properties(g_variant_lookup_value(all, "org.bluez.Adapter1", G_VARIANT_TYPE("a{sv}")));
            gboolean powered = FALSE;
            if (properties.value && g_variant_lookup(properties, "Powered", "b", &powered) && powered) return path;
        }
        throw NativeError("BlueZ has no powered Bluetooth adapter");
    }
    void registerProfile(const std::shared_ptr<State>& state, bool server) {
        onLoop([&] {
            requireActive(state);
            if (!server && disconnecting.count(state->device)) throw NativeError("Bluetooth device is disconnecting");
            // BlueZ permits exactly one external profile per UUID, independent
            // of role or object path. Keep one bidirectional registration while
            // any listener or stream needs it; stopped reception never owns it.
            if (!profiles.empty()) {
                auto& profile = profiles.begin()->second;
                profile.sockets.erase(std::remove_if(profile.sockets.begin(), profile.sockets.end(), [](const auto& weak) { return weak.expired(); }), profile.sockets.end());
                if (server && profile.server) {
                    std::lock_guard<std::mutex> lock(profile.server->mutex);
                    if (!profile.server->closed) throw NativeError("Bluetooth reception is already enabled");
                }
                if (!server) for (auto& weak : profile.sockets) if (auto socket = weak.lock()) {
                    std::lock_guard<std::mutex> lock(socket->mutex);
                    if (!socket->closed && socket->connecting && !socket->connected && socket->device == state->device)
                        throw NativeError("Bluetooth connection to this device is already pending");
                }
                if (server) profile.server = state; else profile.sockets.push_back(state);
                { std::lock_guard<std::mutex> lock(state->mutex); state->profile = profiles.begin()->first; }
                watch(state); return;
            }
            GError* error = nullptr;
            Variant name(g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
                "org.freedesktop.DBus", "GetNameOwner", g_variant_new("(s)", "org.bluez"), nullptr, G_DBUS_CALL_FLAGS_NONE, 3000, nullptr, &error));
            if (!name.value) { std::string message = error->message; g_error_free(error); throw NativeError(message); }
            const char* unique; g_variant_get(name, "(&s)", &unique); owner = unique;
            std::string path = "/dev/ghost/nearbyim/profile/p" + std::to_string(serial++);
            static const GDBusInterfaceVTable table = {profileCall, nullptr, nullptr, {nullptr}};
            guint object = g_dbus_connection_register_object(bus, path.c_str(), info->interfaces[0], &table, this, nullptr, &error);
            if (!object) { std::string message = error->message; g_error_free(error); throw NativeError(message); }
            GVariantBuilder options; g_variant_builder_init(&options, G_VARIANT_TYPE("a{sv}"));
            g_variant_builder_add(&options, "{sv}", "Name", g_variant_new_string("NearbyIM"));
            // Omit Role: BlueZ defaults custom UUIDs to both client and server.
            g_variant_builder_add(&options, "{sv}", "RequireAuthentication", g_variant_new_boolean(TRUE));
            g_variant_builder_add(&options, "{sv}", "RequireAuthorization", g_variant_new_boolean(FALSE));
            g_variant_builder_add(&options, "{sv}", "AutoConnect", g_variant_new_boolean(FALSE));
            g_variant_builder_add(&options, "{sv}", "Channel", g_variant_new_uint16(0));
            try {
                Variant result(call("/org/bluez", "org.bluez.ProfileManager1", "RegisterProfile",
                        g_variant_new("(os@a{sv})", path.c_str(), serviceUuid, g_variant_builder_end(&options))));
                { std::lock_guard<std::mutex> lock(state->mutex); state->profile = path; }
                Profile profile{server ? state : nullptr, object, {}};
                if (!server) profile.sockets.push_back(state);
                profiles.emplace(path, std::move(profile)); watch(state);
            } catch (...) { g_dbus_connection_unregister_object(bus, object); throw; }
        });
    }
    std::string beginDeviceDisconnect(const std::shared_ptr<State>& state, const std::string& device, const std::string& path) {
        return onLoop([&] {
            auto found = profiles.find(path);
            if (found == profiles.end() || disconnecting.count(device) || owner.empty()) return std::string();
            for (auto& weak : found->second.sockets) if (auto other = weak.lock(); other && other != state) {
                std::lock_guard<std::mutex> lock(other->mutex);
                if (!other->closed && other->device == device) return std::string();
            }
            // Fence new streams until the device-wide call completes. Never
            // block this event thread on DisconnectProfile: BlueZ can wait for
            // our RequestDisconnection reply before answering that call.
            disconnecting.emplace(device, path); return owner;
        });
    }
    void endDeviceDisconnect(const std::string& device, const std::string& path) {
        onLoop([&] {
            auto found = disconnecting.find(device);
            if (found != disconnecting.end() && found->second == path) disconnecting.erase(found);
        });
    }
    void unregisterIfIdle(const std::shared_ptr<State>&) {
        onLoop([&] {
            if (profiles.empty()) return;
            auto found = profiles.begin(); auto& profile = found->second;
            if (profile.server) {
                std::lock_guard<std::mutex> lock(profile.server->mutex);
                if (!profile.server->closed) return;
            }
            for (auto& weak : profile.sockets) if (auto socket = weak.lock()) {
                std::lock_guard<std::mutex> lock(socket->mutex); if (!socket->closed) return;
            }
            try { Variant result(call("/org/bluez", "org.bluez.ProfileManager1", "UnregisterProfile", g_variant_new("(o)", found->first.c_str()), 1000)); }
            catch (const NativeError&) { }
            g_dbus_connection_unregister_object(bus, profile.object); profiles.erase(found);
        });
    }
};
struct DeviceInfo { std::string path, address, name; bool paired; };
std::vector<DeviceInfo> devices(Runtime& runtime, const std::string& adapter = "") {
    std::vector<DeviceInfo> result;
    Variant tree(runtime.objects()); GVariantIter iter; g_variant_iter_init(&iter, tree);
    const char* path; GVariant* interfaces;
    while (g_variant_iter_next(&iter, "{&o@a{sa{sv}}}", &path, &interfaces)) {
        Variant all(interfaces); Variant properties(g_variant_lookup_value(all, "org.bluez.Device1", G_VARIANT_TYPE("a{sv}")));
        if (!properties.value) continue;
        const char* deviceAdapter = nullptr;
        if (!adapter.empty() && (!g_variant_lookup(properties, "Adapter", "&o", &deviceAdapter) || adapter != deviceAdapter)) continue;
        const char* address = nullptr; const char* name = nullptr; gboolean paired = FALSE;
        if (!g_variant_lookup(properties, "Address", "&s", &address)) continue;
        g_variant_lookup(properties, "Alias", "&s", &name); g_variant_lookup(properties, "Paired", "b", &paired);
        try { result.push_back({path, normalizeAddress(address), name ? name : address, bool(paired)}); } catch (const InvalidArgument&) { }
    }
    return result;
}
std::vector<DeviceInfo> scan(const std::shared_ptr<State>& state, int seconds) {
    if (seconds < 1 || seconds > 30) throw InvalidArgument("Inquiry duration must be 1..30 seconds");
    requireActive(state); auto& runtime = Runtime::instance(); runtime.watch(state); auto adapter = runtime.poweredAdapter();
    std::unique_lock<std::timed_mutex> discoveryLock(runtime.discoveryMutex, std::defer_lock);
    while (!discoveryLock.try_lock_for(20ms)) requireActive(state);
    { std::lock_guard<std::mutex> lock(state->mutex); if (state->closed) throw NativeError("Inquiry was cancelled"); if (state->scanning) throw NativeError("Inquiry is already running"); state->scanning = true; state->adapter = adapter; }
    bool started = false;
    try {
        GVariantBuilder filter; g_variant_builder_init(&filter, G_VARIANT_TYPE("a{sv}"));
        g_variant_builder_add(&filter, "{sv}", "Transport", g_variant_new_string("bredr"));
        { Variant result(runtime.call(adapter, "org.bluez.Adapter1", "SetDiscoveryFilter", g_variant_new("(@a{sv})", g_variant_builder_end(&filter)), 3000, state->cancel)); }
        started = true;
        { Variant result(runtime.call(adapter, "org.bluez.Adapter1", "StartDiscovery", nullptr, 3000, state->cancel)); }
        { std::unique_lock<std::mutex> lock(state->mutex); state->changed.wait_for(lock, std::chrono::seconds(seconds), [&] { return state->closed; }); }
        requireActive(state);
        auto result = devices(runtime, adapter);
        { Variant stopped(runtime.call(adapter, "org.bluez.Adapter1", "StopDiscovery")); }
        { std::lock_guard<std::mutex> lock(state->mutex); state->scanning = false; }
        return result;
    } catch (...) {
        if (started) try { Variant stopped(runtime.call(adapter, "org.bluez.Adapter1", "StopDiscovery", nullptr, 1000)); } catch (const NativeError&) { }
        { std::lock_guard<std::mutex> lock(state->mutex); state->scanning = false; }
        throw;
    }
}
std::shared_ptr<State> listen() {
    auto& runtime = Runtime::instance(); auto adapter = runtime.poweredAdapter();
    auto server = std::make_shared<State>(Kind::Server); server->adapter = adapter;
    runtime.registerProfile(server, true); return server;
}
std::shared_ptr<State> acceptSocket(const std::shared_ptr<State>& server) {
    std::unique_lock<std::mutex> lock(server->mutex);
    server->changed.wait(lock, [&] { return server->closed || !server->pending.empty(); });
    if (server->closed) throw NativeError("Bluetooth reception stopped");
    auto connection = server->pending.front(); server->pending.pop_front(); return connection;
}
void disconnectDevice(const std::shared_ptr<State>& state) {
    std::string device, profile; bool connecting;
    { std::lock_guard<std::mutex> lock(state->mutex); device = state->device; profile.swap(state->profile); connecting = state->connecting; }
    if (device.empty() || profile.empty()) return;
    try {
        auto& runtime = Runtime::instance();
        auto owner = runtime.beginDeviceDisconnect(state, device, profile);
        if (owner.empty()) return;
        try {
            // Bind cleanup to this daemon instance; an old cancellation must
            // never disconnect a new stream after BlueZ has restarted.
            if (connecting) try { Variant result(runtime.call(device, "org.bluez.Device1", "CancelPairing", nullptr, 1000, nullptr, owner.c_str())); } catch (const NativeError&) { }
            Variant result(runtime.call(device, "org.bluez.Device1", "DisconnectProfile", g_variant_new("(s)", serviceUuid), 1000, nullptr, owner.c_str()));
        } catch (const NativeError&) { }
        runtime.endDeviceDisconnect(device, profile);
    } catch (const NativeError&) { }
}
void closeState(const std::shared_ptr<State>& state) {
    if (!state) return;
    state->closeLocal();
    std::deque<std::shared_ptr<State>> pending;
    { std::lock_guard<std::mutex> lock(state->mutex); pending.swap(state->pending); }
    for (auto& child : pending) { child->closeLocal(); disconnectDevice(child); }
    if (state->kind == Kind::Socket) disconnectDevice(state);
    bool usedRuntime;
    { std::lock_guard<std::mutex> lock(state->mutex); usedRuntime = !state->adapter.empty(); }
    if (usedRuntime && state->kind != Kind::Inquiry) Runtime::instance().unregisterIfIdle(state);

}
void connectSocket(const std::shared_ptr<State>& state, const std::string& address, int timeout) {
    if (timeout < 1 || timeout > 120000) throw InvalidArgument("Connection timeout must be 1..120000 milliseconds");
    auto canonical = normalizeAddress(address); requireActive(state);
    auto& runtime = Runtime::instance(); auto adapter = runtime.poweredAdapter();
    auto known = devices(runtime, adapter); auto device = std::find_if(known.begin(), known.end(), [&](const auto& item) { return item.address == canonical; });
    if (device == known.end()) throw NativeError("Bluetooth device is not known to BlueZ; search or pair it first");
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->closed || state->connecting || state->connected) throw NativeError("Bluetooth socket cannot start another connection");
        state->address = canonical; state->device = device->path; state->adapter = adapter; state->connecting = true;
    }
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout);
    auto remaining = [&] { auto value = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count(); if (value <= 0) throw ConnectTimeout("Bluetooth connection timed out"); return int(value); };
    try {
        runtime.registerProfile(state, false); requireActive(state);
        if (!device->paired) { Variant paired(runtime.call(device->path, "org.bluez.Device1", "Pair", nullptr, remaining(), state->cancel)); }
        requireActive(state);
        { Variant result(runtime.call(device->path, "org.bluez.Device1", "ConnectProfile", g_variant_new("(s)", serviceUuid), remaining(), state->cancel)); }
        std::unique_lock<std::mutex> lock(state->mutex);
        if (!state->changed.wait_until(lock, deadline, [&] { return state->closed || state->connected; })) throw ConnectTimeout("BlueZ did not supply the RFCOMM connection");
        if (state->closed) throw NativeError("Bluetooth connection cancelled");
        state->connecting = false;
    } catch (...) { closeState(state); throw; }
}
void requireConnected(const std::shared_ptr<State>& state) {
    std::lock_guard<std::mutex> lock(state->mutex);
    if (state->closed || !state->connected || state->fd < 0) throw NativeError("Bluetooth connection is not open");
}
int readSocket(const std::shared_ptr<State>& state, char* data, int length) {
    std::lock_guard<std::mutex> reader(state->readMutex);
    std::unique_lock<std::mutex> lock(state->mutex);
    for (;;) {
        if (state->closed || !state->connected || state->fd < 0) throw NativeError("Bluetooth connection is closed");
        int count = recv(state->fd, data, length, 0);
        if (count > 0) return count;
        if (count == 0) return -1;
        if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) throw NativeError(std::string("Bluetooth read: ") + std::strerror(errno));
        state->changed.wait_for(lock, 20ms);
    }
}
void writeSocket(const std::shared_ptr<State>& state, const char* data, int length) {
    std::lock_guard<std::mutex> writer(state->writeMutex);
    std::unique_lock<std::mutex> lock(state->mutex); int offset = 0;
    while (offset < length) {
        if (state->closed || !state->connected || state->fd < 0) throw NativeError("Bluetooth connection is closed");
        int count = send(state->fd, data + offset, std::min(maximumIoChunk, length - offset), MSG_NOSIGNAL);
        if (count > 0) { offset += count; continue; }
        if (count == 0 || (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) throw NativeError("Bluetooth write failed");
        state->changed.wait_for(lock, 20ms);
    }
}
void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type); if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}
template<typename T, typename F> T guarded(JNIEnv* env, T fallback, F function) {
    try { return function(); }
    catch (const InvalidArgument& e) { throwJava(env, "java/lang/IllegalArgumentException", e.what()); }
    catch (const ConnectTimeout& e) { throwJava(env, "java/net/SocketTimeoutException", e.what()); }
    catch (const std::exception& e) { throwJava(env, "java/io/IOException", e.what()); }
    return fallback;
}
std::string javaAddress(JNIEnv* env, jstring address) {
    if (!address) throw InvalidArgument("Bluetooth address is missing");
    const char* text = env->GetStringUTFChars(address, nullptr); if (!text) throw NativeError("Unable to read Bluetooth address");
    std::string result(text); env->ReleaseStringUTFChars(address, text); return normalizeAddress(result);
}
jstring javaString(JNIEnv* env, const std::string& text) {
    // Device aliases are UTF-8, whereas JNI NewStringUTF expects modified UTF-8.
    auto bytes = env->NewByteArray(jsize(text.size())); if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, jsize(text.size()), reinterpret_cast<const jbyte*>(text.data()));
    auto type = env->FindClass("java/lang/String"); auto ctor = env->GetMethodID(type, "<init>", "([BLjava/lang/String;)V");
    auto encoding = env->NewStringUTF("UTF-8"); auto value = static_cast<jstring>(env->NewObject(type, ctor, bytes, encoding));
    env->DeleteLocalRef(encoding); env->DeleteLocalRef(type); env->DeleteLocalRef(bytes); return value;
}
void validateBuffer(JNIEnv* env, jbyteArray bytes, jint offset, jint length) {
    if (!bytes || offset < 0 || length < 0) throw InvalidArgument("Invalid Bluetooth byte range");
    int size = env->GetArrayLength(bytes); if (offset > size || length > size - offset) throw InvalidArgument("Invalid Bluetooth byte range");
}
} // namespace

extern "C" {
JNIEXPORT jint JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeVersion(JNIEnv*, jclass) { return 2; }
JNIEXPORT jstring JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeStatus(JNIEnv* env, jclass) {
    try { Runtime::instance().poweredAdapter(); return nullptr; }
    catch (const std::exception& e) { return javaString(env, e.what()); }
}
JNIEXPORT jlong JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeOpenInquiry(JNIEnv* env, jclass) {
    return guarded<jlong>(env, 0, [] { return addState(std::make_shared<State>(Kind::Inquiry)); });
}
JNIEXPORT void JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeCloseInquiry(JNIEnv* env, jclass, jlong handle) {
    guarded<int>(env, 0, [&] { auto state = removeState(handle, true); if (state) state->closeLocal(); return 0; });
}
JNIEXPORT jobjectArray JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeScan(JNIEnv* env, jclass, jlong handle, jint seconds) {
    return guarded<jobjectArray>(env, nullptr, [&] {
        if (seconds < 1 || seconds > 30) throw InvalidArgument("Inquiry duration must be 1..30 seconds");
        auto found = scan(lookup(handle, Kind::Inquiry), seconds);
        auto cls = env->FindClass("dev/ghost/wozai/DesktopBluetooth$Device");
        auto ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Z)V");
        auto array = env->NewObjectArray(jsize(found.size()), cls, nullptr);
        for (size_t i = 0; i < found.size() && !env->ExceptionCheck(); ++i) {
            auto address = javaString(env, found[i].address); auto name = javaString(env, found[i].name);
            auto device = env->NewObject(cls, ctor, address, name, jboolean(found[i].paired)); env->SetObjectArrayElement(array, jsize(i), device);
            env->DeleteLocalRef(device); env->DeleteLocalRef(name); env->DeleteLocalRef(address);
        }
        env->DeleteLocalRef(cls); return array;
    });
}
JNIEXPORT jlong JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeListen(JNIEnv* env, jclass) {
    return guarded<jlong>(env, 0, [] { return addState(listen()); });
}
JNIEXPORT jlong JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeOpen(JNIEnv* env, jclass) {
    return guarded<jlong>(env, 0, [] { return addState(std::make_shared<State>(Kind::Socket)); });
}
JNIEXPORT void JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeConnect(JNIEnv* env, jclass, jlong handle, jstring address, jint timeout) {
    guarded<int>(env, 0, [&] {
        if (timeout < 1 || timeout > 120000) throw InvalidArgument("Invalid Bluetooth connection timeout");
        auto canonical = javaAddress(env, address); connectSocket(lookup(handle, Kind::Socket), canonical, timeout); return 0;
    });
}
JNIEXPORT jlong JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeAccept(JNIEnv* env, jclass, jlong handle) {
    return guarded<jlong>(env, 0, [&] { return addState(acceptSocket(lookup(handle, Kind::Server))); });
}
JNIEXPORT jstring JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeRemoteAddress(JNIEnv* env, jclass, jlong handle) {
    return guarded<jstring>(env, nullptr, [&] { auto state = lookup(handle, Kind::Socket); requireConnected(state); return javaString(env, state->address); });
}
JNIEXPORT void JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeRequireConnected(JNIEnv* env, jclass, jlong handle) {
    guarded<int>(env, 0, [&] { requireConnected(lookup(handle, Kind::Socket)); return 0; });
}
JNIEXPORT jint JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeRead(JNIEnv* env, jclass, jlong handle, jbyteArray bytes, jint offset, jint length) {
    return guarded<jint>(env, -1, [&] {
        validateBuffer(env, bytes, offset, length); auto state = lookup(handle, Kind::Socket); requireConnected(state); if (!length) return 0;
        std::vector<char> buffer(std::min(length, maximumIoChunk)); int count = readSocket(state, buffer.data(), int(buffer.size()));
        if (count > 0) env->SetByteArrayRegion(bytes, offset, count, reinterpret_cast<const jbyte*>(buffer.data()));
        return count;
    });
}
JNIEXPORT void JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeWrite(JNIEnv* env, jclass, jlong handle, jbyteArray bytes, jint offset, jint length) {
    guarded<int>(env, 0, [&] {
        validateBuffer(env, bytes, offset, length); auto state = lookup(handle, Kind::Socket); requireConnected(state);
        std::vector<char> buffer(std::min(length, maximumIoChunk));
        for (int done = 0; done < length; ) {
            int count = std::min(length - done, maximumIoChunk); env->GetByteArrayRegion(bytes, offset + done, count, reinterpret_cast<jbyte*>(buffer.data()));
            if (env->ExceptionCheck()) return 0;
            writeSocket(state, buffer.data(), count); done += count;
        }
        return 0;
    });
}
JNIEXPORT void JNICALL Java_dev_ghost_wozai_DesktopBluetooth_nativeClose(JNIEnv* env, jclass, jlong handle) {
    guarded<int>(env, 0, [&] { closeState(removeState(handle)); return 0; });
}
}
