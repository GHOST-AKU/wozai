// A private D-Bus daemon and BlueZ contract double exercise real descriptor
// passing, profile registration, cancellation and byte streams, without a radio.
#include "linux_bluetooth.cpp"
#include <iostream>

namespace {
constexpr const char* testDevice = "/org/bluez/hci0/dev_AB_CD_01_23_45_67";
constexpr const char* testAddress = "AB:CD:01:23:45:67";
std::atomic<int> passed{0};
void check(bool value, const char* message) { if (!value) throw std::runtime_error(message); ++passed; }
template<typename F> bool fails(F work) { try { work(); return false; } catch (const NativeError&) { return true; } }
struct Remote {
    int fd;
    explicit Remote(int f) : fd(f) { }
    ~Remote() { if (fd >= 0) ::close(fd); }
};
class BluezDouble {
public:
    struct Profile { std::string sender, path, role; };
    GMainContext* context = nullptr;
    GDBusConnection* bus = nullptr;
    std::mutex mutex;
    std::vector<Profile> profiles;
    std::atomic<int> starts{0}, stops{0}, pairCalls{0}, cancelPairs{0}, disconnects{0};
    std::atomic<bool> powered{true}, paired{true}, holdPair{false}, holdConnect{false}, routeToServer{false}, holdCancel{false};
    GDBusMethodInvocation* pendingPair = nullptr;
    GDBusMethodInvocation* pendingConnect = nullptr;
    std::atomic<GDBusMethodInvocation*> pendingCancel{nullptr};
    std::atomic<bool> holdDisconnect{false};
    std::atomic<GDBusMethodInvocation*> pendingDisconnect{nullptr};
    std::atomic<int> unregisterFault{0}; // 1=error, 2=timeout/retained, 3=timeout/removed, 4=absent.
    std::atomic<GDBusMethodInvocation*> pendingUnregister{nullptr};
    std::atomic<int> registerFault{0}; // 1=timeout/accepted, 2=timeout/absent, 3=denied.
    std::atomic<GDBusMethodInvocation*> pendingRegister{nullptr};
    std::deque<int> peers;
    GVariant* managedObjects() {
        GVariantBuilder tree, interfaces, properties;
        g_variant_builder_init(&tree, G_VARIANT_TYPE("a{oa{sa{sv}}}"));
        g_variant_builder_init(&interfaces, G_VARIANT_TYPE("a{sa{sv}}"));
        g_variant_builder_init(&properties, G_VARIANT_TYPE("a{sv}"));
        g_variant_builder_add(&properties, "{sv}", "Powered", g_variant_new_boolean(powered));
        g_variant_builder_add(&interfaces, "{s@a{sv}}", "org.bluez.Adapter1", g_variant_builder_end(&properties));
        g_variant_builder_add(&tree, "{o@a{sa{sv}}}", "/org/bluez/hci0", g_variant_builder_end(&interfaces));
        g_variant_builder_init(&interfaces, G_VARIANT_TYPE("a{sa{sv}}"));
        g_variant_builder_init(&properties, G_VARIANT_TYPE("a{sv}"));
        g_variant_builder_add(&properties, "{sv}", "Address", g_variant_new_string(testAddress));
        g_variant_builder_add(&properties, "{sv}", "Alias", g_variant_new_string("手机 日本語 한국어 🙂"));
        g_variant_builder_add(&properties, "{sv}", "Paired", g_variant_new_boolean(paired));
        g_variant_builder_add(&properties, "{sv}", "Adapter", g_variant_new_object_path("/org/bluez/hci0"));
        g_variant_builder_add(&interfaces, "{s@a{sv}}", "org.bluez.Device1", g_variant_builder_end(&properties));
        g_variant_builder_add(&tree, "{o@a{sa{sv}}}", testDevice, g_variant_builder_end(&interfaces));
        return g_variant_new("(@a{oa{sa{sv}}})", g_variant_builder_end(&tree));
    }
    static void method(GDBusConnection*, const gchar* sender, const gchar*, const gchar*, const gchar* name,
            GVariant* args, GDBusMethodInvocation* invocation, gpointer user) {
        auto& self = *static_cast<BluezDouble*>(user);
        try {
            if (!std::strcmp(name, "GetManagedObjects")) { g_dbus_method_invocation_return_value(invocation, self.managedObjects()); return; }
            if (!std::strcmp(name, "RegisterProfile")) {
                const char* path; const char* uuid; GVariant* options;
                g_variant_get(args, "(&o&s@a{sv})", &path, &uuid, &options); Variant values(options);
                const char* role = nullptr; gboolean authentication = FALSE, automatic = TRUE; guint16 channel = 1;
                check(std::string(uuid) == serviceUuid, "Different cross-platform service UUID");
                check(!g_variant_lookup(values, "Role", "&s", &role), "Shared profile must support both roles");
                role = "both";
                check(g_variant_lookup(values, "RequireAuthentication", "b", &authentication) && authentication, "Insecure Bluetooth profile");
                check(g_variant_lookup(values, "AutoConnect", "b", &automatic) && !automatic, "Unrequested automatic Bluetooth connection");
                check(g_variant_lookup(values, "Channel", "q", &channel) && channel == 0, "RFCOMM channel is not dynamically allocated");
                std::lock_guard<std::mutex> lock(self.mutex);
                if (!self.profiles.empty()) {
                    g_dbus_method_invocation_return_dbus_error(invocation, "org.bluez.Error.NotPermitted", "UUID already registered"); return;
                }
                if (self.registerFault == 3) {
                    g_dbus_method_invocation_return_dbus_error(invocation, "org.bluez.Error.NotPermitted", "Registration denied"); return;
                }
                if (self.registerFault == 2) {
                    self.pendingRegister = static_cast<GDBusMethodInvocation*>(g_object_ref(invocation)); return;
                }
                self.profiles.push_back({sender, path, role});
                if (self.registerFault == 1) {
                    self.pendingRegister = static_cast<GDBusMethodInvocation*>(g_object_ref(invocation)); return;
                }
            } else if (!std::strcmp(name, "UnregisterProfile")) {
                const char* path; g_variant_get(args, "(&o)", &path);
                std::lock_guard<std::mutex> lock(self.mutex);
                if (self.unregisterFault == 1) {
                    g_dbus_method_invocation_return_dbus_error(invocation, "org.bluez.Error.Failed", "Temporary unregister failure"); return;
                }
                if (self.unregisterFault == 2) {
                    self.pendingUnregister = static_cast<GDBusMethodInvocation*>(g_object_ref(invocation)); return;
                }
                bool exists = std::any_of(self.profiles.begin(), self.profiles.end(), [&](const auto& profile) { return profile.path == path && profile.sender == sender; });
                self.profiles.erase(std::remove_if(self.profiles.begin(), self.profiles.end(), [&](const auto& profile) { return profile.path == path && profile.sender == sender; }), self.profiles.end());
                if (self.unregisterFault == 3) {
                    self.pendingUnregister = static_cast<GDBusMethodInvocation*>(g_object_ref(invocation)); return;
                }
                if (!exists || self.unregisterFault == 4) {
                    g_dbus_method_invocation_return_dbus_error(invocation, "org.bluez.Error.DoesNotExist", "Profile does not exist"); return;
                }
            } else if (!std::strcmp(name, "SetDiscoveryFilter")) {
                GVariant* filter; g_variant_get(args, "(@a{sv})", &filter); Variant values(filter); const char* transport = nullptr;
                check(g_variant_lookup(values, "Transport", "&s", &transport) && !std::strcmp(transport, "bredr"), "Discovery is not classic Bluetooth");
            } else if (!std::strcmp(name, "StartDiscovery")) ++self.starts;
            else if (!std::strcmp(name, "StopDiscovery")) ++self.stops;
            else if (!std::strcmp(name, "Pair")) {
                ++self.pairCalls;
                if (self.holdPair) { self.pendingPair = static_cast<GDBusMethodInvocation*>(g_object_ref(invocation)); return; }
                self.paired = true;
            } else if (!std::strcmp(name, "CancelPairing")) {
                ++self.cancelPairs;
                if (self.pendingPair) { g_dbus_method_invocation_return_dbus_error(self.pendingPair, "org.bluez.Error.Canceled", "Pairing cancelled"); g_object_unref(self.pendingPair); self.pendingPair = nullptr; }
                if (self.holdCancel) { self.pendingCancel = static_cast<GDBusMethodInvocation*>(g_object_ref(invocation)); return; }
            } else if (!std::strcmp(name, "DisconnectProfile")) {
                ++self.disconnects;
                if (self.holdDisconnect) { self.pendingDisconnect = static_cast<GDBusMethodInvocation*>(g_object_ref(invocation)); return; }
                // BlueZ Device1 DisconnectProfile is device-wide: the profile
                // callback requests closure of every stream for this device.
                auto profile = self.profile("both", sender);
                GError* error = nullptr;
                Variant response(g_dbus_connection_call_sync(self.bus, profile.sender.c_str(), profile.path.c_str(),
                    "org.bluez.Profile1", "RequestDisconnection", g_variant_new("(o)", testDevice), nullptr,
                    G_DBUS_CALL_FLAGS_NONE, 3000, nullptr, &error));
                if (!response.value) { std::string message = error->message; g_error_free(error); throw NativeError(message); }
                if (self.pendingConnect) { g_dbus_method_invocation_return_dbus_error(self.pendingConnect, "org.bluez.Error.Canceled", "Connection cancelled"); g_object_unref(self.pendingConnect); self.pendingConnect = nullptr; }
            } else if (!std::strcmp(name, "ConnectProfile")) {
                const char* uuid; g_variant_get(args, "(&s)", &uuid); check(std::string(uuid) == serviceUuid, "Connect requested wrong UUID");
                if (self.holdConnect) { self.pendingConnect = static_cast<GDBusMethodInvocation*>(g_object_ref(invocation)); return; }
                int descriptors[2]; if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, descriptors)) throw NativeError("socketpair failed");
                Remote first(descriptors[0]); Remote second(descriptors[1]);
                auto client = self.profile("client", sender); self.deliver(client, first.fd);
                if (self.routeToServer) self.deliver(self.profile("server", sender), second.fd);
                else { std::lock_guard<std::mutex> lock(self.mutex); self.peers.push_back(second.fd); second.fd = -1; }
            } else throw NativeError("Unexpected BlueZ method");
            g_dbus_method_invocation_return_value(invocation, nullptr);
        } catch (const std::exception& error) { g_dbus_method_invocation_return_dbus_error(invocation, "org.bluez.Error.Failed", error.what()); }
    }
    BluezDouble() {
        std::promise<void> ready; auto future = ready.get_future();
        std::thread([this, ready = std::move(ready)]() mutable {
            context = g_main_context_new(); g_main_context_push_thread_default(context);
            GError* error = nullptr;
            bus = g_dbus_connection_new_for_address_sync(std::getenv("DBUS_SYSTEM_BUS_ADDRESS"),
                GDBusConnectionFlags(G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT | G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION), nullptr, nullptr, &error);
            if (!bus) { ready.set_exception(std::make_exception_ptr(NativeError(error->message))); g_error_free(error); return; }
            g_dbus_connection_set_exit_on_close(bus, FALSE);
            const char* xml = "<node>"
                "<interface name='org.freedesktop.DBus.ObjectManager'><method name='GetManagedObjects'><arg type='a{oa{sa{sv}}}' direction='out'/></method></interface>"
                "<interface name='org.bluez.ProfileManager1'><method name='RegisterProfile'><arg type='o' direction='in'/><arg type='s' direction='in'/><arg type='a{sv}' direction='in'/></method><method name='UnregisterProfile'><arg type='o' direction='in'/></method></interface>"
                "<interface name='org.bluez.Adapter1'><method name='StartDiscovery'/><method name='StopDiscovery'/><method name='SetDiscoveryFilter'><arg type='a{sv}' direction='in'/></method></interface>"
                "<interface name='org.bluez.Device1'><method name='Pair'/><method name='CancelPairing'/><method name='ConnectProfile'><arg type='s' direction='in'/></method><method name='DisconnectProfile'><arg type='s' direction='in'/></method></interface></node>";
            auto info = g_dbus_node_info_new_for_xml(xml, &error);
            static const GDBusInterfaceVTable table = {method, nullptr, nullptr, {nullptr}};
            const char* paths[] = {"/", "/org/bluez", "/org/bluez/hci0", testDevice};
            for (int i = 0; i < 4; ++i) g_dbus_connection_register_object(bus, paths[i], info->interfaces[i], &table, this, nullptr, &error);
            Variant response(g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", g_variant_new("(su)", "org.bluez", 0), nullptr, G_DBUS_CALL_FLAGS_NONE, 3000, nullptr, &error));
            if (!response.value || error) { ready.set_exception(std::make_exception_ptr(NativeError(error ? error->message : "Name request failed"))); return; }
            ready.set_value(); g_main_loop_run(g_main_loop_new(context, FALSE));
        }).detach();
        future.get();
    }
    Profile profile(const std::string& role, const std::string& sender = "") {
        std::lock_guard<std::mutex> lock(mutex);
        for (auto i = profiles.rbegin(); i != profiles.rend(); ++i) if ((i->role == role || i->role == "both") && (sender.empty() || i->sender == sender)) return *i;
        throw NativeError("Test profile not registered");
    }
    int takePeer() { std::lock_guard<std::mutex> lock(mutex); if (peers.empty()) throw NativeError("No test peer"); int result = peers.front(); peers.pop_front(); return result; }
    void powerOff() {
        powered = false;
        GVariantBuilder changed, invalidated;
        g_variant_builder_init(&changed, G_VARIANT_TYPE("a{sv}"));
        g_variant_builder_add(&changed, "{sv}", "Powered", g_variant_new_boolean(FALSE));
        g_variant_builder_init(&invalidated, G_VARIANT_TYPE("as"));
        g_dbus_connection_emit_signal(bus, nullptr, "/org/bluez/hci0", "org.freedesktop.DBus.Properties", "PropertiesChanged",
            g_variant_new("(s@a{sv}@as)", "org.bluez.Adapter1", g_variant_builder_end(&changed), g_variant_builder_end(&invalidated)), nullptr);
    }
    void releaseName() {
        GError* error = nullptr;
        Variant result(g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ReleaseName",
            g_variant_new("(s)", "org.bluez"), nullptr, G_DBUS_CALL_FLAGS_NONE, 3000, nullptr, &error));
        if (!result.value) throw NativeError(error->message);
    }
    void deliver(const Profile& profile, int fd) {
        GError* error = nullptr; auto list = g_unix_fd_list_new(); int index = g_unix_fd_list_append(list, fd, &error);
        GVariantBuilder properties; g_variant_builder_init(&properties, G_VARIANT_TYPE("a{sv}"));
        auto result = g_dbus_connection_call_with_unix_fd_list_sync(bus, profile.sender.c_str(), profile.path.c_str(), "org.bluez.Profile1", "NewConnection",
            g_variant_new("(oh@a{sv})", testDevice, index, g_variant_builder_end(&properties)), nullptr, G_DBUS_CALL_FLAGS_NONE, 3000, list, nullptr, nullptr, &error);
        g_object_unref(list);
        if (!result) { std::string message = error->message; g_error_free(error); throw NativeError(message); }
        g_variant_unref(result);
    }
    void requestDisconnection(const Profile& profile) {
        GError* error = nullptr;
        Variant result(g_dbus_connection_call_sync(bus, profile.sender.c_str(), profile.path.c_str(),
            "org.bluez.Profile1", "RequestDisconnection", g_variant_new("(o)", testDevice), nullptr,
            G_DBUS_CALL_FLAGS_NONE, 3000, nullptr, &error));
        if (!result.value) { std::string message = error->message; g_error_free(error); throw NativeError(message); }
    }
};
void scanLifecycle(BluezDouble& mock) {
    auto inquiry = std::make_shared<State>(Kind::Inquiry);
    auto found = scan(inquiry, 1);
    check(found.size() == 1 && found[0].paired && found[0].address == testAddress, "Discovery lost paired device metadata");
    check(found[0].name == "手机 日本語 한국어 🙂", "Discovery changed UTF-8 aliases");
    check(mock.starts == 1 && mock.stops == 1, "Discovery session not released");
    auto scanning = std::async(std::launch::async, [&] { return fails([&] { scan(inquiry, 30); }); });
    for (int i = 0; i < 100 && mock.starts < 2; ++i) std::this_thread::sleep_for(10ms);
    check(mock.starts == 2, "Cancellable discovery did not start"); inquiry->closeLocal();
    check(scanning.wait_for(2s) == std::future_status::ready && scanning.get(), "Closing did not cancel discovery");
    check(mock.stops == 2, "Cancelled inquiry retained a BlueZ discovery lease");
    check(fails([&] { scan(inquiry, 1); }), "Closed inquiry restarted");
    mock.powered = false; check(fails([&] { Runtime::instance().poweredAdapter(); }), "Disabled adapter was reported ready"); mock.powered = true;
}
void connectionAndStream(BluezDouble& mock) {
    auto connection = std::make_shared<State>(Kind::Socket);
    mock.paired = false; connectSocket(connection, testAddress, 3000); Remote peer(mock.takePeer());
    check(mock.pairCalls == 1 && connection->connected, "Pairing did not precede the connection");
    std::vector<char> payload(3 * maximumIoChunk + 37); for (size_t i = 0; i < payload.size(); ++i) payload[i] = char(i * 17);
    auto read = std::async(std::launch::async, [&] {
        std::vector<char> result(payload.size()); size_t offset = 0;
        while (offset < result.size()) { int count = recv(peer.fd, result.data() + offset, result.size() - offset, 0); if (count <= 0) throw NativeError("Test peer truncated"); offset += count; }
        return result;
    });
    writeSocket(connection, payload.data(), int(payload.size())); check(read.wait_for(2s) == std::future_status::ready && read.get() == payload, "Native bounded write changed bytes");
    char sent = 42, got = 0; ::send(peer.fd, &sent, 1, MSG_NOSIGNAL); check(readSocket(connection, &got, 1) == 1 && got == sent, "Native read changed bytes");
    auto blocked = std::async(std::launch::async, [&] { return fails([&] { readSocket(connection, &got, 1); }); });
    check(blocked.wait_for(80ms) == std::future_status::timeout, "Read did not block"); closeState(connection);
    check(blocked.wait_for(2s) == std::future_status::ready && blocked.get(), "Closing did not unblock read");
    check(mock.disconnects > 0, "BlueZ profile connection was not disconnected");
    jlong old = addState(connection); check(removeState(old) == connection && !removeState(old), "Handle removal is not idempotent");
    auto replacement = std::make_shared<State>(Kind::Socket); jlong next = addState(replacement);
    check(next > old && fails([&] { lookup(old, Kind::Socket); }), "Stale handle was reused"); removeState(next);
    jlong inquiry = addState(std::make_shared<State>(Kind::Inquiry));
    check(fails([&] { removeState(inquiry); }) && bool(lookup(inquiry, Kind::Inquiry)), "Wrong-type close consumed a live handle"); removeState(inquiry, true);
    connection = std::make_shared<State>(Kind::Socket); connectSocket(connection, testAddress, 3000); Remote slowPeer(mock.takePeer());
    int small = 4096; setsockopt(connection->fd, SOL_SOCKET, SO_SNDBUF, &small, sizeof(small));
    std::vector<char> blockedPayload(8 * 1024 * 1024, 7);
    auto writing = std::async(std::launch::async, [&] { return fails([&] { writeSocket(connection, blockedPayload.data(), int(blockedPayload.size())); }); });
    check(writing.wait_for(80ms) == std::future_status::timeout, "Slow-peer write was not blocked"); closeState(connection);
    check(writing.wait_for(2s) == std::future_status::ready && writing.get(), "Closing did not cancel blocked write");
}
void receptionLifecycle(BluezDouble& mock) {
    auto server = listen(); auto profile = mock.profile("server");
    auto outgoing = std::make_shared<State>(Kind::Socket);
    connectSocket(outgoing, testAddress, 3000); Remote outgoingPeer(mock.takePeer());
    { std::lock_guard<std::mutex> lock(mock.mutex); check(mock.profiles.size() == 1, "Reception and outgoing connection did not share the profile"); }
    closeState(outgoing);
    auto accepting = std::async(std::launch::async, [&] { return acceptSocket(server); });
    check(accepting.wait_for(80ms) == std::future_status::timeout, "Accept did not block");
    int descriptors[2]; check(socketpair(AF_UNIX, SOCK_STREAM, 0, descriptors) == 0, "Test socketpair failed");
    Remote peer(descriptors[1]); mock.deliver(profile, descriptors[0]); ::close(descriptors[0]);
    check(accepting.wait_for(2s) == std::future_status::ready, "Profile FD did not reach accept"); auto connection = accepting.get();
    check(connection->address == testAddress, "Accepted route address was lost");
    closeState(server);
    char message = 33, received = 0; send(peer.fd, &message, 1, MSG_NOSIGNAL);
    check(readSocket(connection, &received, 1) == 1 && received == message, "Stopping reception closed the established chat");
    check(fails([&] { acceptSocket(server); }), "Stopped server continued accepting");
    auto restarted = listen();
    { std::lock_guard<std::mutex> lock(mock.mutex); check(mock.profiles.size() == 1 && mock.profiles.front().path == profile.path, "Restarting reception registered a duplicate UUID"); }
    check(fails([&] { listen(); }), "Two active listeners were permitted");
    closeState(restarted);
    closeState(connection); check(fails([&] { mock.profile("server"); }), "Stopped profile leaked after final connection closed");
    server = listen(); auto waiting = std::async(std::launch::async, [&] { return fails([&] { acceptSocket(server); }); });
    check(waiting.wait_for(80ms) == std::future_status::timeout, "Second accept did not block"); closeState(server);
    check(waiting.wait_for(2s) == std::future_status::ready && waiting.get(), "Stop reception did not cancel accept");
}
void registerFailureRecovery(BluezDouble& mock) {
    for (int fault : {1, 2, 3}) {
        mock.registerFault = fault;
        check(fails([&] { listen(); }), "Failed profile registration reported a listener");
        mock.registerFault = 0;
        if (auto delayed = mock.pendingRegister.exchange(nullptr)) {
            g_dbus_method_invocation_return_value(delayed, nullptr); g_object_unref(delayed);
        }
        auto restored = listen();
        auto profile = mock.profile("both");
        { std::lock_guard<std::mutex> lock(mock.mutex); check(mock.profiles.size() == 1, "Retry left duplicate profile registrations"); }
        int descriptors[2]; check(socketpair(AF_UNIX, SOCK_STREAM, 0, descriptors) == 0, "Test socketpair failed");
        Remote peer(descriptors[1]); mock.deliver(profile, descriptors[0]); ::close(descriptors[0]);
        auto connection = acceptSocket(restored);
        char sent = 't', received = 0; check(::write(peer.fd, &sent, 1) == 1, "Recovered registration peer could not write");
        check(readSocket(connection, &received, 1) == 1 && received == sent, "Recovered registration did not deliver bytes");
        closeState(connection); closeState(restored);
        { std::lock_guard<std::mutex> lock(mock.mutex); check(mock.profiles.empty(), "Recovered registration did not unregister"); }
    }
}
void unregisterFailureRecovery(BluezDouble& mock) {
    for (int fault : {1, 2, 3, 4}) {
        auto original = listen(); mock.unregisterFault = fault;
        closeState(original); mock.unregisterFault = 0;
        if (auto delayed = mock.pendingUnregister.exchange(nullptr)) {
            g_dbus_method_invocation_return_value(delayed, nullptr); g_object_unref(delayed);
        }
        std::shared_ptr<State> resumed;
        check(!fails([&] { resumed = listen(); }), "Bluetooth listener could not recover from failed/timed-out unregistration");
        auto profile = mock.profile("server"); int pair[2];
        check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Recovery socketpair failed");
        Remote peer(pair[1]); mock.deliver(profile, pair[0]); ::close(pair[0]);
        auto accepted = acceptSocket(resumed); char sent = 'u', received = 0;
        check(::write(peer.fd, &sent, 1) == 1, "Recovery peer could not write");
        check(readSocket(accepted, &received, 1) == 1 && received == sent, "Recovered listener did not receive a real descriptor stream");
        closeState(resumed); closeState(accepted);
    }
}
void duplicateConnectionPreservesChat(BluezDouble& mock) {
    auto server = listen(); auto profile = mock.profile("server");
    int pair[2]; check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Test socketpair failed");
    Remote originalPeer(pair[1]); mock.deliver(profile, pair[0]); ::close(pair[0]);
    auto original = acceptSocket(server);
    int before = mock.disconnects;
    check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Test duplicate socketpair failed");
    Remote rejectedPeer(pair[1]); mock.deliver(profile, pair[0]); ::close(pair[0]);
    auto rejected = acceptSocket(server); closeState(rejected);
    check(mock.disconnects == before, "Rejecting a duplicate invoked device-wide DisconnectProfile");
    char sent = 42, received = 0; send(originalPeer.fd, &sent, 1, MSG_NOSIGNAL);
    check(readSocket(original, &received, 1) == 1 && received == sent, "Rejecting a duplicate closed the original chat");
    check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Test queued socketpair failed");
    Remote queuedPeer(pair[1]); mock.deliver(profile, pair[0]); ::close(pair[0]);
    closeState(server);
    check(mock.disconnects == before, "Draining reception queue disconnected the established chat");
    send(originalPeer.fd, &sent, 1, MSG_NOSIGNAL);
    check(readSocket(original, &received, 1) == 1 && received == sent, "Stopping reception closed the original chat");
    closeState(original);
    check(mock.disconnects == before + 1, "Last stream did not release the BlueZ device profile");
}
void disconnectedQueuePreservesReception(BluezDouble& mock) {
    auto server = listen(); auto profile = mock.profile("server");
    int pair[2];
    for (int i = 0; i < 5; ++i) {
        check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Test stale socketpair failed");
        Remote stalePeer(pair[1]); mock.deliver(profile, pair[0]); ::close(pair[0]);
        mock.requestDisconnection(profile);
    }
    auto accepting = std::async(std::launch::async, [&] { return acceptSocket(server); });
    check(accepting.wait_for(80ms) == std::future_status::timeout, "Disconnected pending stream was returned by accept");
    check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Test live socketpair failed");
    Remote livePeer(pair[1]); mock.deliver(profile, pair[0]); ::close(pair[0]);
    check(accepting.wait_for(2s) == std::future_status::ready, "Listener did not accept after stale stream");
    auto live = accepting.get(); char sent = 'q', received = 0;
    check(::write(livePeer.fd, &sent, 1) == 1, "Live peer could not write");
    check(readSocket(live, &received, 1) == 1 && received == sent, "Live stream was unusable after stale pending entry");
    mock.requestDisconnection(profile);
    check(remoteAddress(live) == testAddress, "Disconnect after accept made routing metadata fail the listener");
    check(fails([&] { readSocket(live, &received, 1); }), "Disconnected accepted stream remained readable");
    auto stopping = std::async(std::launch::async, [&] { return fails([&] { acceptSocket(server); }); });
    check(stopping.wait_for(80ms) == std::future_status::timeout, "Listener stopped after discarding stale streams");
    closeState(server);
    check(stopping.wait_for(2s) == std::future_status::ready && stopping.get(), "Stopping listener did not cancel accept after stale streams");
    closeState(live);
}
void delayedDisconnectKeepsFence(BluezDouble& mock) {
    auto server = listen(); auto profile = mock.profile("server");
    auto original = std::make_shared<State>(Kind::Socket);
    connectSocket(original, testAddress, 3000); Remote originalPeer(mock.takePeer());
    mock.holdDisconnect = true;
    auto started = std::chrono::steady_clock::now(); closeState(original);
    check(std::chrono::steady_clock::now() - started < 1500ms, "Delayed daemon reply made close unbounded");
    check(mock.pendingDisconnect.load() != nullptr, "Disconnect was not held past the close deadline");
    int pair[2]; check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Fence socketpair failed");
    Remote inbound(pair[0]), inboundPeer(pair[1]);
    check(fails([&] { mock.deliver(profile, inbound.fd); }), "Timed-out disconnect accepted a new inbound stream");
    auto reconnect = std::make_shared<State>(Kind::Socket);
    check(fails([&] { connectSocket(reconnect, testAddress, 3000); }), "Timed-out disconnect accepted a new outgoing stream");
    mock.requestDisconnection(profile);
    reconnect = std::make_shared<State>(Kind::Socket);
    check(fails([&] { connectSocket(reconnect, testAddress, 3000); }), "Callback alone released an unresolved disconnect fence");
    auto reply = mock.pendingDisconnect.exchange(nullptr);
    g_dbus_method_invocation_return_value(reply, nullptr); g_object_unref(reply); mock.holdDisconnect = false;
    bool restored = false;
    for (int i = 0; i < 100 && !restored; ++i) {
        reconnect = std::make_shared<State>(Kind::Socket);
        restored = !fails([&] { connectSocket(reconnect, testAddress, 3000); });
        if (!restored) std::this_thread::sleep_for(10ms);
    }
    check(restored, "Definitive disconnect completion did not release its fence");
    Remote peer(mock.takePeer()); char sent = 'f', received = 0;
    check(::write(peer.fd, &sent, 1) == 1 && readSocket(reconnect, &received, 1) == 1 && received == sent, "Reconnection after late completion was unusable");
    closeState(server); closeState(reconnect);
}
void cancelPairingAndConnect(BluezDouble& mock) {
    mock.paired = false; mock.holdPair = true;
    auto connection = std::make_shared<State>(Kind::Socket);
    int count = mock.pairCalls;
    auto connecting = std::async(std::launch::async, [&] { return fails([&] { connectSocket(connection, testAddress, 120000); }); });
    for (int i = 0; i < 100 && mock.pairCalls == count; ++i) std::this_thread::sleep_for(10ms);
    check(mock.pairCalls > count, "Pairing did not start"); closeState(connection);
    check(connecting.wait_for(2s) == std::future_status::ready && connecting.get(), "Closing did not cancel pending pairing");
    check(mock.cancelPairs > 0, "Daemon pairing was left running"); mock.holdPair = false; mock.paired = true;
    mock.holdConnect = true; connection = std::make_shared<State>(Kind::Socket);
    auto timed = std::async(std::launch::async, [&] { return fails([&] { connectSocket(connection, testAddress, 150); }); });
    check(timed.wait_for(2s) == std::future_status::ready && timed.get(), "Connection timeout was not bounded"); mock.holdConnect = false;
    std::lock_guard<std::mutex> lock(mock.mutex); check(mock.profiles.empty(), "Cancelled client profile leaked");
}
void stalledCancellationSharesCloseDeadline(BluezDouble& mock) {
    mock.paired = false; mock.holdPair = true; mock.holdCancel = true; mock.holdDisconnect = true;
    auto connection = std::make_shared<State>(Kind::Socket);
    int pairCalls = mock.pairCalls;
    auto connecting = std::async(std::launch::async, [&] { return fails([&] { connectSocket(connection, testAddress, 120000); }); });
    for (int i = 0; i < 100 && mock.pairCalls == pairCalls; ++i) std::this_thread::sleep_for(10ms);
    check(mock.pairCalls > pairCalls, "Stalled cancellation did not reach pairing");
    auto started = std::chrono::steady_clock::now(); closeState(connection);
    check(std::chrono::steady_clock::now() - started < 1500ms, "Pair cancellation and disconnect used separate close deadlines");
    check(mock.pendingCancel.load() != nullptr, "Cancellation was not stalled");
    for (int i = 0; i < 100 && !mock.pendingDisconnect.load(); ++i) std::this_thread::sleep_for(10ms);
    check(mock.pendingDisconnect.load() != nullptr, "Stalled cancellation did not start fenced disconnect");
    check(connecting.wait_for(1s) == std::future_status::ready && connecting.get(), "Stalled cleanup prevented the connect caller from cancelling");
    auto retry = std::make_shared<State>(Kind::Socket);
    check(fails([&] { connectSocket(retry, testAddress, 3000); }), "Stalled cleanup allowed outgoing reconnect");
    auto cancelReply = mock.pendingCancel.exchange(nullptr);
    g_dbus_method_invocation_return_value(cancelReply, nullptr); g_object_unref(cancelReply);
    auto profile = mock.profile("both"); mock.requestDisconnection(profile);
    auto reply = mock.pendingDisconnect.exchange(nullptr);
    g_dbus_method_invocation_return_value(reply, nullptr); g_object_unref(reply);
    mock.holdPair = false; mock.holdCancel = false; mock.holdDisconnect = false; mock.paired = true;
    bool restored = false;
    for (int i = 0; i < 100 && !restored; ++i) {
        retry = std::make_shared<State>(Kind::Socket);
        restored = !fails([&] { connectSocket(retry, testAddress, 3000); });
        if (!restored) std::this_thread::sleep_for(10ms);
    }
    check(restored, "Late cleanup completion prevented outgoing recovery");
    Remote peer(mock.takePeer()); closeState(retry);
}
void adapterLoss(BluezDouble& mock) {
    auto server = listen(); auto connection = std::make_shared<State>(Kind::Socket);
    connectSocket(connection, testAddress, 3000); Remote peer(mock.takePeer());
    auto accepting = std::async(std::launch::async, [&] { return fails([&] { acceptSocket(server); }); });
    auto reading = std::async(std::launch::async, [&] { char byte; return fails([&] { readSocket(connection, &byte, 1); }); });
    mock.powerOff();
    bool acceptStopped = accepting.wait_for(1s) == std::future_status::ready;
    bool readStopped = reading.wait_for(1s) == std::future_status::ready;
    closeState(server); closeState(connection);
    check(acceptStopped && accepting.get(), "Disabling adapter left reception blocked");
    check(readStopped && reading.get(), "Disabling adapter left stream read blocked");
    mock.powered = true; server = listen(); closeState(server);
    { std::lock_guard<std::mutex> lock(mock.mutex); check(mock.profiles.empty(), "Adapter restart leaked profile"); }
}
void daemonLoss(BluezDouble& mock) {
    auto server = listen();
    auto accepting = std::async(std::launch::async, [&] { return fails([&] { acceptSocket(server); }); });
    check(accepting.wait_for(80ms) == std::future_status::timeout, "Accept did not wait before daemon loss");
    mock.releaseName();
    bool interrupted = accepting.wait_for(500ms) == std::future_status::ready;
    closeState(server); accepting.wait();
    check(interrupted && accepting.get(), "BlueZ daemon loss left accept blocked");
}
void daemonRestartDuringCancellation() {
    auto& old = *new BluezDouble(); old.paired = false; old.holdPair = true; old.holdCancel = true;
    auto canceled = std::make_shared<State>(Kind::Socket);
    auto connecting = std::async(std::launch::async, [&] { return fails([&] { connectSocket(canceled, testAddress, 120000); }); });
    for (int i = 0; i < 100 && old.pairCalls == 0; ++i) std::this_thread::sleep_for(10ms);
    check(old.pairCalls > 0, "Restart test did not reach pairing");
    auto closing = std::async(std::launch::async, [&] { closeState(canceled); });
    for (int i = 0; i < 100 && !old.pendingCancel.load(); ++i) std::this_thread::sleep_for(10ms);
    check(old.pendingCancel.load() != nullptr, "Restart test did not reach cancellation cleanup");
    old.releaseName();
    auto& replacement = *new BluezDouble();
    auto server = listen(); auto restored = std::make_shared<State>(Kind::Socket);
    connectSocket(restored, testAddress, 3000); Remote peer(replacement.takePeer());
    auto reply = old.pendingCancel.exchange(nullptr);
    g_dbus_method_invocation_return_value(reply, nullptr); g_object_unref(reply);
    check(closing.wait_for(2s) == std::future_status::ready, "Old daemon cleanup blocked after restart"); closing.get();
    check(connecting.wait_for(2s) == std::future_status::ready && connecting.get(), "Old pairing did not terminate");
    check(replacement.disconnects == 0, "Old cleanup disconnected the replacement daemon's session");
    char sent = 'r', received = 0; check(::write(peer.fd, &sent, 1) == 1, "Restored peer could not write");
    check(readSocket(restored, &received, 1) == 1 && received == sent, "Restored stream was closed by old cleanup");
    closeState(server); closeState(restored); replacement.releaseName();
}
void ambiguousDisconnectKeepsFence(const char* error) {
    auto& old = *new BluezDouble(); auto server = listen(); auto profile = old.profile("server");
    auto connection = std::make_shared<State>(Kind::Socket);
    connectSocket(connection, testAddress, 3000); Remote oldPeer(old.takePeer());
    old.holdDisconnect = true; closeState(connection);
    auto reply = old.pendingDisconnect.exchange(nullptr);
    check(reply != nullptr, "Ambiguous disconnect did not reach daemon");
    g_dbus_method_invocation_return_dbus_error(reply, error, "Disconnect completion is uncertain"); g_object_unref(reply);
    int pair[2]; check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Ambiguous fence socketpair failed");
    Remote inbound(pair[0]), inboundPeer(pair[1]);
    check(fails([&] { old.deliver(profile, inbound.fd); }), "Ambiguous disconnect reply released inbound fence");
    auto retry = std::make_shared<State>(Kind::Socket);
    check(fails([&] { connectSocket(retry, testAddress, 3000); }), "Ambiguous disconnect reply released outgoing fence");
    old.requestDisconnection(profile);
    check(fails([&] { old.deliver(profile, inbound.fd); }), "Late callback released an ambiguous fence");
    closeState(server);
    { std::lock_guard<std::mutex> lock(old.mutex); check(old.profiles.size() == 1, "Unresolved disconnect unregistered its callback object"); }
    old.releaseName();
    auto& replacement = *new BluezDouble(); auto restored = std::make_shared<State>(Kind::Socket);
    check(!fails([&] { connectSocket(restored, testAddress, 3000); }), "Owner invalidation did not release ambiguous disconnect fence");
    Remote peer(replacement.takePeer()); char sent = 'a', received = 0;
    check(::write(peer.fd, &sent, 1) == 1 && readSocket(restored, &received, 1) == 1 && received == sent, "Stream after owner invalidation was unusable");
    closeState(restored); replacement.releaseName();
}
void oldDisconnectCompletionPreservesNewFence() {
    auto& old = *new BluezDouble(); auto oldServer = listen(); auto oldProfile = old.profile("server");
    auto connection = std::make_shared<State>(Kind::Socket);
    connectSocket(connection, testAddress, 3000); Remote oldPeer(old.takePeer());
    old.holdDisconnect = true; connection->closeLocal(); auto oldFinished = disconnectDevice(connection); closeState(connection);
    check(oldFinished.valid(), "Old disconnect was not scheduled");
    for (int i = 0; i < 100 && !old.pendingDisconnect.load(); ++i) std::this_thread::sleep_for(10ms);
    check(old.pendingDisconnect.load() != nullptr, "Old daemon did not receive disconnect before restart");
    old.releaseName();
    auto& replacement = *new BluezDouble(); auto server = listen(); auto profile = replacement.profile("server");
    auto restored = std::make_shared<State>(Kind::Socket);
    connectSocket(restored, testAddress, 3000); Remote peer(replacement.takePeer());
    replacement.holdDisconnect = true; closeState(restored);
    check(fails([&] { old.requestDisconnection(oldProfile); }), "Old owner callback was accepted after daemon replacement");
    auto oldReply = old.pendingDisconnect.exchange(nullptr);
    check(oldReply != nullptr, "Old disconnect completion was not retained");
    g_dbus_method_invocation_return_value(oldReply, nullptr); g_object_unref(oldReply);
    check(oldFinished.wait_for(1s) == std::future_status::ready, "Old daemon completion was not processed");
    int pair[2]; check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Replacement fence socketpair failed");
    Remote inbound(pair[0]), inboundPeer(pair[1]);
    check(fails([&] { replacement.deliver(profile, inbound.fd); }), "Old completion removed new owner's inbound fence");
    auto retry = std::make_shared<State>(Kind::Socket);
    check(fails([&] { connectSocket(retry, testAddress, 3000); }), "Old completion removed new owner's outgoing fence");
    replacement.requestDisconnection(profile);
    auto reply = replacement.pendingDisconnect.exchange(nullptr);
    check(reply != nullptr, "New owner disconnect was not pending");
    g_dbus_method_invocation_return_value(reply, nullptr); g_object_unref(reply); replacement.holdDisconnect = false;
    bool connected = false;
    for (int i = 0; i < 100 && !connected; ++i) {
        retry = std::make_shared<State>(Kind::Socket);
        connected = !fails([&] { connectSocket(retry, testAddress, 3000); });
        if (!connected) std::this_thread::sleep_for(10ms);
    }
    check(connected, "Current owner's definitive completion did not release its fence");
    Remote currentPeer(replacement.takePeer()); char sent = 'n', received = 0;
    check(::write(currentPeer.fd, &sent, 1) == 1 && readSocket(retry, &received, 1) == 1 && received == sent, "Current owner's recovered stream was unusable");
    closeState(oldServer); closeState(server); closeState(retry); replacement.releaseName();
}
} // namespace
int main(int argc, char** argv) {
    try {
        const char* address = std::getenv("DBUS_SESSION_BUS_ADDRESS"); if (!address) throw NativeError("Run tests inside dbus-run-session");
        g_setenv("DBUS_SYSTEM_BUS_ADDRESS", address, TRUE);
        auto& mock = *new BluezDouble();
        scanLifecycle(mock); connectionAndStream(mock); receptionLifecycle(mock); registerFailureRecovery(mock); unregisterFailureRecovery(mock); duplicateConnectionPreservesChat(mock); disconnectedQueuePreservesReception(mock); delayedDisconnectKeepsFence(mock); cancelPairingAndConnect(mock); stalledCancellationSharesCloseDeadline(mock); adapterLoss(mock);
        if (argc > 1) {
            mock.routeToServer = true;
            gint status; GError* error = nullptr;
            if (!g_spawn_sync(nullptr, argv + 1, nullptr, G_SPAWN_SEARCH_PATH, nullptr, nullptr, nullptr, nullptr, &status, &error)) throw NativeError(error->message);
            check(g_spawn_check_wait_status(status, &error), "Java BlueZ JNI / NIM2 integration failed");
        }
        daemonLoss(mock);
        daemonRestartDuringCancellation();
        for (const char* error : {"org.freedesktop.DBus.Error.NoReply", "org.freedesktop.DBus.Error.Timeout", "org.bluez.Error.InProgress"}) ambiguousDisconnectKeepsFence(error);
        oldDisconnectCompletionPreservesNewFence();
        std::cout << "Linux native BlueZ tests: " << passed << " checks passed (private D-Bus and real Unix FD streams; no physical radio)\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << "FAIL Linux native Bluetooth: " << error.what() << '\n'; return 1; }
}
