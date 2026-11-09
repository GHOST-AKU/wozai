#pragma once
#include <stddef.h>
#include <stdint.h>
uint64_t nm_mesh_start(const uint8_t *path, size_t length);
int32_t nm_mesh_command(uint64_t handle, const uint8_t *bytes, size_t length);
int32_t nm_mesh_poll(uint64_t handle, uint8_t *output, size_t capacity, uint32_t timeout_ms);
int32_t nm_mesh_state(uint64_t handle);
void nm_mesh_stop(uint64_t handle);
