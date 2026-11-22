#ifndef QD_HOST_TLS_H
#define QD_HOST_TLS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Sign the UNHASHED message using ECDSA P-256/SHA-256. Write DER (r,s)
 * into out, at most capacity bytes, and return its length or -1. Neither
 * buffer may be retained. Do not throw or reenter the handle from sign. */
typedef int32_t (*qd_sign_fn)(void *ctx, const uint8_t *message, int32_t length,
                              uint8_t *out, int32_t capacity);

/* Socket-free TLS 1.3 mutual raw-public-key client. spki is the client's
 * canonical 91-byte P-256 SPKI DER. pin is REQUIRED: the server's 32 raw
 * SHA-256(SPKI DER) bytes. Both are copied. Returns a positive handle or 0.
 * Serialize calls per handle; keep sign and ctx valid until close returns.
 * Callbacks are synchronous on the thread driving the session. */
int64_t qd_tls_open_client(const uint8_t *spki, int32_t spki_len,
                           const uint8_t *pin, int32_t pin_len,
                           qd_sign_fn sign, void *ctx);

/* Returns 0 or -1. receive accepts up to 65536 ordered TLS bytes; send
 * accepts up to 16384 + 4 plaintext bytes (one maximum framed message).
 * Input must remain readable and unchanged for the call. A zero-length
 * input may be NULL; it is not EOF. No send before handshake. */
int32_t qd_tls_receive(int64_t id, const uint8_t *data, int32_t len);
int32_t qd_tls_send(int64_t id, const uint8_t *data, int32_t len);

/* Pass a writable buffer with cap > 0. Each drain copies and consumes at
 * most min(cap, 65536) bytes; repeat until 0 (empty). Drains need not
 * align to frames. A positive short read does not mean the queue is empty. */
int32_t qd_tls_outbound(int64_t id, uint8_t *out, int32_t cap);
int32_t qd_tls_plaintext(int64_t id, uint8_t *out, int32_t cap);

/* Not a drain. With cap >= 32, returns 0 until authenticated, then 32.
 * Flush the client's final outbound flight even when the pin is ready. */
int32_t qd_tls_peer_pin(int64_t id, uint8_t *out, int32_t cap);

/* All operations except open/close return -1 on failure. Close on error;
 * never retry a failed session. Unknown/already closed handles are ignored. */
void qd_tls_close(int64_t id);

#ifdef __cplusplus
}
#endif
#endif
