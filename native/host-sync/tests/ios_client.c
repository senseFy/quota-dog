/* Stdio peer of JniRoundTrip.kt. No socket, Keychain item, or persistent key. */
#include "qd_host_tls.h"
#include <CommonCrypto/CommonDigest.h>
#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>
#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "C ABI check failed at line %d: %s\n", __LINE__, #x); exit(1); } } while (0)

struct signer { SecKeyRef key; const char *mode; int calls; };

static SecKeyRef new_key(void) {
    int bits = 256;
    CFNumberRef size = CFNumberCreate(NULL, kCFNumberIntType, &bits);
    const void *keys[] = {kSecAttrKeyType, kSecAttrKeySizeInBits};
    const void *values[] = {kSecAttrKeyTypeECSECPrimeRandom, size};
    CFDictionaryRef attrs = CFDictionaryCreate(NULL, keys, values, 2,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    SecKeyRef key = SecKeyCreateRandomKey(attrs, NULL);
    CHECK(key);
    CFRelease(attrs); CFRelease(size);
    return key;
}

static void public_spki(SecKeyRef key, uint8_t out[91]) {
    const uint8_t prefix[] = {0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,
                              0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00};
    SecKeyRef pub = SecKeyCopyPublicKey(key);
    CHECK(pub);
    CFDataRef point = SecKeyCopyExternalRepresentation(pub, NULL);
    CHECK(point && CFDataGetLength(point) == 65 && CFDataGetBytePtr(point)[0] == 4);
    memcpy(out, prefix, sizeof(prefix));
    memcpy(out + sizeof(prefix), CFDataGetBytePtr(point), 65);
    CFRelease(point); CFRelease(pub);
}

static int32_t sign_message(void *ctx, const uint8_t *msg, int32_t len, uint8_t *out, int32_t cap) {
    struct signer *s = ctx;
    ++s->calls;
    CHECK(len > 0 && len <= 256 && cap == 72);
    if (!strcmp(s->mode, "sign-error")) return -1;
    if (!strcmp(s->mode, "sign-overflow")) return cap + 1;
    CFDataRef input = CFDataCreate(NULL, msg, len);
    CFDataRef sig = SecKeyCreateSignature(s->key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, input, NULL);
    CFRelease(input);
    CHECK(sig && CFDataGetLength(sig) > 0 && CFDataGetLength(sig) <= cap);
    int32_t n = (int32_t)CFDataGetLength(sig);
    memcpy(out, CFDataGetBytePtr(sig), n);
    CFRelease(sig);
    return n;
}

static uint8_t wire[65536];

static void flush_flight(int64_t id) {
    size_t used = 0;
    for (;;) {
        uint8_t chunk[97]; // deliberately smaller than a flight
        int n = qd_tls_outbound(id, chunk, sizeof(chunk));
        CHECK(n >= 0 && (size_t)n <= sizeof(chunk) && used + n <= sizeof(wire));
        if (!n) break;
        memcpy(wire + used, chunk, n); used += n;
    }
    CHECK(used > 0);
    uint32_t size = htonl((uint32_t)used);
    CHECK(fwrite(&size, sizeof(size), 1, stdout) == 1 && fwrite(wire, 1, used, stdout) == used);
    CHECK(fflush(stdout) == 0);
}

static int read_flight(void) {
    uint32_t n;
    CHECK(fread(&n, sizeof(n), 1, stdin) == 1);
    n = ntohl(n);
    CHECK(n > 0 && n <= sizeof(wire) && fread(wire, 1, n, stdin) == n);
    return (int)n;
}

static void unusable(int64_t id) {
    uint8_t buf[32];
    CHECK(qd_tls_peer_pin(id, buf, 32) == -1 && qd_tls_plaintext(id, buf, 32) == -1);
    CHECK(qd_tls_send(id, NULL, 0) == -1 && qd_tls_receive(id, NULL, 0) == -1);
    CHECK(qd_tls_outbound(id, buf, 32) == -1);
}

int main(int argc, char **argv) {
    CHECK(argc == 2);
    struct signer signer = {new_key(), argv[1], 0};
    uint8_t spki[91], mine[32], peer[32], authenticated[32];
    public_spki(signer.key, spki);
    CC_SHA256(spki, sizeof(spki), mine);
    CHECK(fwrite(mine, 1, sizeof(mine), stdout) == sizeof(mine) && fflush(stdout) == 0);
    CHECK(fread(peer, 1, sizeof(peer), stdin) == sizeof(peer));
    CHECK(qd_tls_open_client(spki, 91, NULL, 32, sign_message, &signer) == 0);
    CHECK(qd_tls_open_client(spki, 91, peer, 31, sign_message, &signer) == 0);
    CHECK(qd_tls_open_client(spki, 91, peer, 32, NULL, &signer) == 0);
    CHECK(qd_tls_open_client((uint8_t[91]){0}, 91, peer, 32, sign_message, &signer) == 0);

    int wrong_pin = !strcmp(signer.mode, "wrong-pin");
    if (wrong_pin) peer[0] ^= 1;
    if (!strcmp(signer.mode, "wrong-key")) { CFRelease(signer.key); signer.key = new_key(); }
    int64_t id = qd_tls_open_client(spki, 91, peer, 32, sign_message, &signer);
    CHECK(id > 0 && qd_tls_receive(id, NULL, 0) == 0);
    CHECK(qd_tls_peer_pin(id, authenticated, 32) == 0 && qd_tls_plaintext(id, wire, 1) == 0);
    flush_flight(id);
    int n = read_flight();
    if (strcmp(signer.mode, "pinned")) {
        CHECK(qd_tls_receive(id, wire, n) == -1);
        CHECK(signer.calls == (wrong_pin ? 0 : 1));
        unusable(id);
    } else {
        CHECK(qd_tls_receive(id, wire, n - 1) == 0 && qd_tls_peer_pin(id, authenticated, 32) == 0);
        CHECK(qd_tls_receive(id, wire + n - 1, 1) == 0 && signer.calls == 1);
        CHECK(qd_tls_peer_pin(id, authenticated, 32) == 32 && !memcmp(authenticated, peer, 32));
        flush_flight(id);

        uint8_t request[38417]; // byte pattern and reversed reply checked by the existing JNI fixture
        for (size_t i = 0; i < sizeof(request); ++i) request[i] = (uint8_t)i;
        for (size_t i = 0; i < sizeof(request);) {
            int chunk = (int)(sizeof(request) - i);
            if (chunk > 16388) chunk = 16388;
            CHECK(qd_tls_send(id, request + i, chunk) == 0); i += chunk;
        }
        flush_flight(id);
        n = read_flight();
        CHECK(qd_tls_receive(id, wire, n) == 0);
        size_t total = 0;
        do {
            uint8_t chunk[83];
            n = qd_tls_plaintext(id, chunk, sizeof(chunk));
            CHECK(n >= 0 && (size_t)n <= sizeof(chunk) && total + n <= sizeof(request));
            for (int i = 0; i < n; ++i) CHECK(chunk[i] == request[sizeof(request) - 1 - total++]);
        } while (n);
        CHECK(total == sizeof(request) && signer.calls == 1);
        CHECK(qd_tls_send(id, request, 16389) == -1); // authenticated, one byte beyond the frame bound
        unusable(id);
    }
    qd_tls_close(id); unusable(id); qd_tls_close(id);
    CFRelease(signer.key);
    fprintf(stderr, "PASS iOS C client: %s (%d signing callbacks)\n", signer.mode, signer.calls);
    return 0;
}
