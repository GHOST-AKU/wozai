#pragma once
#include <stdint.h>
#include <stddef.h>
int32_t wm_available(void);
uint64_t wm_start(const uint8_t *, size_t);
int32_t wm_command(uint64_t, const uint8_t *, size_t);
int32_t wm_poll(uint64_t, uint8_t *, size_t, uint32_t);
int32_t wm_state(uint64_t);
void wm_stop(uint64_t);
