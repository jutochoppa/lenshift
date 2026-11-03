#!/usr/bin/env python3
"""Seed Lenshift's provider settings on a connected device.

This writes the *same* SharedPreferences JSON that `SettingsRepository` produces, so the
app reads it through its normal startup path — no test-only hooks in production code.

The API key is deliberately NOT written here. Lenshift stores keys in the Android
Keystore (`SecretStore`), which cannot be populated from adb, so the script says so rather
than faking it. A mock provider does not need a key, so a live round-trip still works.

Usage:
    python tools/seed_provider.py [--serial SERIAL] [--base URL] [--model ID]
"""

import argparse
import json
import os
import subprocess
import sys

DEFAULT_PACKAGE = "dev.lenshift.app"
PREFS = "lenshift_settings"
KEY = "settings_v1"
LOCAL = os.path.join("work", "shared_prefs", f"{PREFS}.xml")


def adb(serial, *args):
    command = ["adb"] + (["-s", serial] if serial else []) + list(args)
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"adb failed: {' '.join(command)}\n{result.stderr}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial")
    parser.add_argument("--package", default=DEFAULT_PACKAGE,
                        help="installed application id (debug builds share the release id)")
    parser.add_argument("--base", default="http://10.0.2.2:8787/v1")
    parser.add_argument("--model", default="mock-gpt-4o")
    parser.add_argument("--name", default="Mock provider")
    parser.add_argument("--protocol", default="openai_chat")
    args = parser.parse_args()

    profile = {
        "id": "seeded-mock",
        "displayName": args.name,
        "protocol": args.protocol,
        "baseUrl": args.base,
        "modelId": args.model,
        "secretAlias": "seeded-mock",
        "customHeaders": {},
        "streaming": True,
        "temperature": None,
        "topP": None,
        "topK": None,
        "maxOutputTokens": 1024,
        "contextWindowOverride": None,
        "reasoningMode": "auto",
        "reasoningTokenBudget": None,
        "imageDetail": "auto",
        "jpegQuality": 85,
        "maxImageEdgePx": 1568,
        "timeoutMs": 60000,
        "maxRetries": 1,
        "extraJsonParameters": "",
        "visionOverride": "UNKNOWN",
        "reasoningOverride": "UNKNOWN",
        "allowCleartext": True,
        "mechanismOverride": None,
    }

    settings = {
        "profiles": [profile],
        "activeProfileId": "seeded-mock",
        "defaultQuickAction": "explain",
        "requireConfirmationBeforeSend": False,
        "historyEnabled": False,
        "history": [],
        "customSystemInstructions": "",
        "ocrEnabled": True,
        "ocrLanguages": ["eng"],
        "enabledActivations": ["assistant"],
        "onboardingComplete": True,
    }

    # The JSON is XML-escaped because it lives inside a <string> element.
    payload = json.dumps(settings, ensure_ascii=False)
    payload = payload.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    os.makedirs(os.path.dirname(LOCAL), exist_ok=True)
    with open(LOCAL, "w", encoding="utf-8") as handle:
        handle.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
        handle.write("<map>\n")
        handle.write(f'    <string name="{KEY}">{payload}</string>\n')
        handle.write("</map>\n")

    adb(args.serial, "push", LOCAL, f"/data/local/tmp/{PREFS}.xml")
    adb(args.serial, "shell", f"run-as {args.package} mkdir -p shared_prefs")
    adb(
        args.serial,
        "shell",
        f"run-as {args.package} cp /data/local/tmp/{PREFS}.xml shared_prefs/{PREFS}.xml",
    )
    adb(args.serial, "shell", f"rm -f /data/local/tmp/{PREFS}.xml")

    print(f"Seeded '{args.name}' -> {args.base}  model={args.model}")
    print("The API key lives in the Keystore and cannot be seeded from adb.")
    print("Enter it in the app if your endpoint requires one.")


if __name__ == "__main__":
    main()
