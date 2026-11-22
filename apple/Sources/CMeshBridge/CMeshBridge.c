#include "CMeshBridge.h"
#if __has_include("MeshRust.h")
#include "MeshRust.h"
int32_t wm_available(void) { return 1; }
uint64_t wm_start(const uint8_t *p, size_t n) { return nm_mesh_start(p,n); }
int32_t wm_command(uint64_t h, const uint8_t *p, size_t n) { return nm_mesh_command(h,p,n); }
int32_t wm_poll(uint64_t h, uint8_t *p, size_t n, uint32_t t) { return nm_mesh_poll(h,p,n,t); }
int32_t wm_state(uint64_t h) { return nm_mesh_state(h); }
void wm_stop(uint64_t h) { nm_mesh_stop(h); }
#else
// A source-only checkout may build the private-chat client without the Rust artifact.
// Public Mesh stays visibly unavailable; it never falls back to a fake engine.
int32_t wm_available(void) { return 0; }
uint64_t wm_start(const uint8_t *p, size_t n) { (void)p; (void)n; return 0; }
int32_t wm_command(uint64_t h, const uint8_t *p, size_t n) { (void)h; (void)p; (void)n; return 0; }
int32_t wm_poll(uint64_t h, uint8_t *p, size_t n, uint32_t t) { (void)h; (void)p; (void)n; (void)t; return -1; }
int32_t wm_state(uint64_t h) { (void)h; return -1; }
void wm_stop(uint64_t h) { (void)h; }
#endif
