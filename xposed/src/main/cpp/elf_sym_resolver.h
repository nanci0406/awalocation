// elf_sym_resolver.h
//
// Runtime symbol-address resolver for the native step/gait hook.
//
// Instead of relying on an on-device `readelf` pass (fragile: toybox readelf
// may be absent, and a stale/hardcoded offset like 0x5b420 silently breaks on
// other ROMs or crashes SensorService), we resolve the target function's
// address *at runtime* from the already-mapped module inside system_server.
//
// How it works:
//   1. find the module's load base + on-disk path in /proc/self/maps,
//   2. mmap the file read-only and parse its ELF dynamic symbol table
//      (.dynsym via DT_GNU_HASH / DT_HASH, with a linear fallback),
//   3. return  base + sym.st_value  as the live function address.
//
// This is ABI-neutral (Elf64 on arm64-v8a, Elf32 on armeabi-v7a) and depends
// only on the symbol being present in .dynsym — which it is for
// BitTube::sendObjects and convertToSensorEvent on the ROMs we target.

#ifndef AWALOCATION_ELF_SYM_RESOLVER_H
#define AWALOCATION_ELF_SYM_RESOLVER_H

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>

#include <elf.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>

#include "awalocation_log.h"

namespace awalocationsym {

static const char *kTag = "ElfSymResolver";

#if defined(__LP64__)
using Ehdr = Elf64_Ehdr;
using Shdr = Elf64_Shdr;
using Sym  = Elf64_Sym;
#define AWALOCATION_ST_TYPE(i) ELF64_ST_TYPE(i)
#else
using Ehdr = Elf32_Ehdr;
using Shdr = Elf32_Shdr;
using Sym  = Elf32_Sym;
#define AWALOCATION_ST_TYPE(i) ELF32_ST_TYPE(i)
#endif

// Locate "<base> <path>" for the first executable/readable mapping of a module
// whose mapped path contains `keyword`. Returns true and fills outBase/outPath.
static inline bool findModule(const char *keyword, uintptr_t *outBase,
                              char *outPath, size_t pathCap) {
  FILE *fp = fopen("/proc/self/maps", "r");
  if (!fp)
    return false;
  bool found = false;
  char line[1024];
  while (fgets(line, sizeof(line), fp)) {
    if (!strstr(line, keyword))
      continue;
    // We want the first mapping of this module (lowest base) regardless of
    // perms, so the ELF header (offset 0) is reachable; r--p or r-xp both work.
    char *slash = strchr(line, '/');
    if (!slash)
      continue;
    uintptr_t base = (uintptr_t)strtoull(line, nullptr, 16);
    size_t len = strcspn(slash, "\n");
    if (len >= pathCap)
      len = pathCap - 1;
    memcpy(outPath, slash, len);
    outPath[len] = '\0';
    *outBase = base;
    found = true;
    break;
  }
  fclose(fp);
  return found;
}

// Resolve the file offset (== st_value for a PIE/shared object's first PT_LOAD)
// of `symbol` by scanning the on-disk ELF's .dynsym (and .symtab if present).
// Returns the symbol value (a virtual address / file offset) or 0 on failure.
static inline uint64_t symValueFromFile(const char *path, const char *symbol) {
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    KLOGW(kTag, "open %s failed", path);
    return 0;
  }
  struct stat st;
  if (fstat(fd, &st) != 0 || st.st_size < (off_t)sizeof(Ehdr)) {
    close(fd);
    return 0;
  }
  size_t fileSize = (size_t)st.st_size;
  void *map = mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (map == MAP_FAILED) {
    KLOGW(kTag, "mmap %s failed", path);
    return 0;
  }

  uint64_t result = 0;
  const uint8_t *bytes = (const uint8_t *)map;
  const Ehdr *eh = (const Ehdr *)map;

  if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) {
    KLOGW(kTag, "%s not an ELF", path);
    munmap(map, fileSize);
    return 0;
  }

  if (eh->e_shnum == 0 || eh->e_shentsize < sizeof(Shdr)) {
    KLOGW(kTag, "%s invalid section header metadata", path);
    munmap(map, fileSize);
    return 0;
  }

  uint64_t shTableBytes = (uint64_t)eh->e_shnum * (uint64_t)eh->e_shentsize;
  if (eh->e_shoff > fileSize || shTableBytes > fileSize - eh->e_shoff) {
    KLOGW(kTag, "%s section header table out of bounds", path);
    munmap(map, fileSize);
    return 0;
  }

  auto sectionAt = [bytes, eh](uint16_t idx) -> const Shdr & {
    return *(const Shdr *)(bytes + eh->e_shoff + (size_t)idx * (size_t)eh->e_shentsize);
  };

  // Scan both .dynsym and .symtab; .dynsym is always present, .symtab usually
  // stripped. For each, find its linked string table via sh_link.
  for (uint16_t i = 0; i < eh->e_shnum && result == 0; i++) {
    const Shdr &symsh = sectionAt(i);
    if (symsh.sh_type != SHT_DYNSYM && symsh.sh_type != SHT_SYMTAB)
      continue;
    if (symsh.sh_entsize < sizeof(Sym) || symsh.sh_link >= eh->e_shnum)
      continue;
    if (symsh.sh_offset > fileSize || symsh.sh_size > fileSize - symsh.sh_offset)
      continue;

    const Shdr &strsh = sectionAt((uint16_t)symsh.sh_link);
    if (strsh.sh_offset > fileSize || strsh.sh_size > fileSize - strsh.sh_offset)
      continue;

    size_t nsyms = (size_t)(symsh.sh_size / symsh.sh_entsize);
    const char *strs = (const char *)(bytes + strsh.sh_offset);
    size_t strsCap = (size_t)strsh.sh_size;
    size_t symLen = strlen(symbol);

    for (size_t s = 0; s < nsyms; s++) {
      const Sym *sym = (const Sym *)(bytes + symsh.sh_offset + s * (size_t)symsh.sh_entsize);
      uint32_t nameOff = sym->st_name;
      if (nameOff == 0 || nameOff >= strsCap)
        continue;
      if (sym->st_value == 0)
        continue;
      if (AWALOCATION_ST_TYPE(sym->st_info) != STT_FUNC)
        continue;
      size_t remain = strsCap - (size_t)nameOff;
      if (symLen < remain &&
          memcmp(strs + nameOff, symbol, symLen) == 0 &&
          strs[nameOff + symLen] == '\0') {
        result = (uint64_t)sym->st_value;
        break;
      }
    }
  }

  munmap(map, fileSize);
  return result;
}

// Resolve the live runtime address of `symbol` in a loaded module identified by
// `keyword` (e.g. "libsensorservice.so"). Tries each symbol name in order and
// returns the first hit. *outOffset receives the file-relative offset (for
// logging / caching); the returned pointer is module_base + offset.
static inline void *resolve(const char *keyword, const char *const *symbols,
                            int symbolCount, uint64_t *outOffset) {
  uintptr_t base = 0;
  char path[512];
  if (!findModule(keyword, &base, path, sizeof(path))) {
    KLOGW(kTag, "module %s not mapped", keyword);
    return nullptr;
  }
  for (int i = 0; i < symbolCount; i++) {
    uint64_t val = symValueFromFile(path, symbols[i]);
    if (val != 0) {
      if (outOffset)
        *outOffset = val;
      KLOGI(kTag, "resolved %s in %s: off=0x%llx base=0x%lx",
            symbols[i], keyword, (unsigned long long)val, (unsigned long)base);
      return (void *)(base + (uintptr_t)val);
    }
  }
  KLOGW(kTag, "no requested symbol found in %s (%s)", keyword, path);
  return nullptr;
}

}  // namespace awalocationsym

#endif  // AWALOCATION_ELF_SYM_RESOLVER_H
