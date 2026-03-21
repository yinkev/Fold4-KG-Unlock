#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <setjmp.h>
#include <unistd.h>
#include <sys/mman.h>

// SIGSEGV recovery — skip bad pages instead of crashing PoGo
static sigjmp_buf segv_jmp;
static volatile sig_atomic_t segv_caught = 0;

static void segv_handler(int sig) {
    segv_caught = 1;
    siglongjmp(segv_jmp, 1);
}

// Check if a memory page is resident/mapped using mincore()
static int is_page_readable(void *addr, size_t len) {
    unsigned long page_size = sysconf(_SC_PAGESIZE);
    unsigned long aligned = (unsigned long)addr & ~(page_size - 1);
    size_t pages = ((unsigned long)addr + len - aligned + page_size - 1) / page_size;
    unsigned char *vec = (unsigned char *)alloca(pages);
    if (mincore((void *)aligned, pages * page_size, vec) != 0) {
        return 0; // mincore failed — page not mapped
    }
    // Check all pages are resident
    for (size_t i = 0; i < pages; i++) {
        if (!(vec[i] & 1)) return 0;
    }
    return 1;
}

// Safe memory copy with SIGSEGV recovery
static size_t safe_memcpy(void *dst, const void *src, size_t len) {
    struct sigaction sa, old_sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = segv_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGSEGV, &sa, &old_sa);
    sigaction(SIGBUS, &sa, NULL);

    size_t copied = 0;
    unsigned long page_size = sysconf(_SC_PAGESIZE);
    const unsigned char *s = (const unsigned char *)src;
    unsigned char *d = (unsigned char *)dst;

    while (copied < len) {
        // Calculate how much we can copy in this page
        size_t offset_in_page = ((unsigned long)(s + copied)) & (page_size - 1);
        size_t chunk = page_size - offset_in_page;
        if (chunk > len - copied) chunk = len - copied;

        segv_caught = 0;
        if (sigsetjmp(segv_jmp, 1) == 0) {
            memcpy(d + copied, s + copied, chunk);
            copied += chunk;
        } else {
            // SIGSEGV caught — write zeros for this chunk and continue
            memset(d + copied, 0, chunk);
            copied += chunk;
        }
    }

    // Restore original handlers
    sigaction(SIGSEGV, &old_sa, NULL);
    sigaction(SIGBUS, &old_sa, NULL);

    return copied;
}

// JVMTI agent entry point — called when attached to a running process
JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    FILE *log = fopen("/sdcard/Download/memdump_log.txt", "w");
    if (!log) log = stderr;

    fprintf(log, "JVMTI Agent loaded (safe version with SIGSEGV handler).\n");
    fprintf(log, "Reading /proc/self/maps...\n");
    fflush(log);

    // Read memory maps
    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) {
        fprintf(log, "ERROR: Cannot open /proc/self/maps\n");
        fclose(log);
        return JNI_OK;
    }

    char line[512];
    FILE *dump = fopen("/sdcard/Download/pogo_native.bin", "wb");
    if (!dump) {
        fprintf(log, "ERROR: Cannot create dump file\n");
        fclose(maps);
        fclose(log);
        return JNI_OK;
    }

    long total = 0;
    long regions = 0;
    long skipped = 0;

    while (fgets(line, sizeof(line), maps)) {
        unsigned long start, end;
        char perms[5], path[256] = "";

        sscanf(line, "%lx-%lx %4s %*s %*s %*s %255[^\n]", &start, &end, perms, path);

        // Must be readable
        if (perms[0] != 'r') continue;

        // Dump interesting regions
        int interested = 0;

        // Niantic/PoGo native libraries
        if (strstr(path, "libpgpplugin") || strstr(path, "libmain") ||
            strstr(path, "libNianticLabs") || strstr(path, "libil2cpp") ||
            strstr(path, "libunity") || strstr(path, "libgrpc") ||
            strstr(path, "libcrypto") || strstr(path, "libssl") ||
            strstr(path, "libpokemon")) {
            interested = 1;
        }

        // Anonymous RW mappings (native heap — most likely location for keys)
        if (path[0] == '\0' && perms[0] == 'r' && perms[1] == 'w') {
            interested = 1;
        }

        // [anon:libc_malloc] regions
        if (strstr(path, "[anon:libc_malloc]") || strstr(path, "[heap]")) {
            interested = 1;
        }

        if (!interested) continue;

        size_t size = end - start;
        if (size > 256 * 1024 * 1024) {
            fprintf(log, "SKIP (too large): %lx-%lx %s %s (%zu bytes)\n",
                    start, end, perms, path, size);
            skipped++;
            continue;
        }

        fprintf(log, "Dumping %lx-%lx %s %s (%zu bytes)\n",
                start, end, perms, path, size);
        fflush(log);

        // Write region header marker
        unsigned long marker = 0xDEADBEEFCAFEBABEUL;
        fwrite(&marker, 8, 1, dump);
        fwrite(&start, 8, 1, dump);
        fwrite(&end, 8, 1, dump);

        // Allocate temp buffer and safe-copy
        unsigned char *buf = (unsigned char *)malloc(size);
        if (!buf) {
            fprintf(log, "  MALLOC FAILED for %zu bytes, skipping\n", size);
            skipped++;
            continue;
        }

        size_t copied = safe_memcpy(buf, (const void *)start, size);
        fwrite(buf, 1, size, dump);
        free(buf);

        total += size;
        regions++;
    }

    fclose(dump);
    fclose(maps);
    fprintf(log, "Done. Dumped %ld regions, %ld bytes total. Skipped %ld regions.\n",
            regions, total, skipped);
    fclose(log);

    return JNI_OK;
}
