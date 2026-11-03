#!/usr/bin/env python3
"""Deterministic mock AI provider used by Lenshift's on-device tests.

This server speaks four of the five protocols Lenshift implements, so the real
adapters can be exercised over real HTTP from an emulator without any paid API key.

Endpoints
---------
POST /v1/chat/completions   OpenAI Chat Completions (SSE + non-streaming)
POST /v1/responses          OpenAI Responses API (named SSE events)
POST /v1/messages           Anthropic Messages (typed SSE events)
POST /v1/images/generations OpenAI Images API (returns a generated PNG)
POST /v1beta/models/{m}:streamGenerateContent?alt=sse   Gemini
POST /v1beta/models/{m}:generateContent                 Gemini non-streaming
GET  /v1/models             OpenAI-style model list
GET  /v1beta/models         Gemini-style model list
GET  /health                liveness probe

Test hooks (header or body driven)
----------------------------------
X-Mock-Scenario: ok | auth-fail | not-found | rate-limit | server-error |
                 bad-request | empty-answer | reasoning-only | slow | redirect
The same value may be supplied as `"mock_scenario"` in the JSON body.

Requests are recorded in memory and exposed at GET /__requests so tests can assert on
exactly what Lenshift sent (body shape, image presence, reasoning keys).
"""

import base64
import json
import os
import struct
import zlib
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RECORDED = []
LOCK = threading.Lock()

ANSWER_TEXT = "The sign says **Welcome to Chrome**."
REASONING_TEXT = "I located the heading and read its text."

# Test hook: while this file exists, streamed chat completions return its contents instead
# of the fixed answer, so rendering (Markdown, LaTeX) can be checked on a device.
ANSWER_FILE = os.environ.get("MOCK_ANSWER_FILE", os.path.join("work", "mock_answer.md"))


def answer_pieces():
    if os.path.exists(ANSWER_FILE):
        with open(ANSWER_FILE, encoding="utf-8") as f:
            text = f.read()
        return [text[i:i + 40] for i in range(0, len(text), 40)]
    return ["The sign says ", "**Welcome ", "to Chrome**.", "\n\nIt is the ",
            "browser's first-run page."]


def png(width, height, seed):
    """A small gradient PNG, coloured by [seed], made with the standard library only."""
    r0, g0, b0 = (seed * 47) % 200 + 30, (seed * 89) % 200 + 30, (seed * 131) % 200 + 30
    rows = []
    for y in range(height):
        row = bytearray([0])
        for x in range(width):
            t = (x + y) / float(width + height)
            ring = ((x - width / 2) ** 2 + (y - height / 2) ** 2) ** 0.5 < height / 4
            k = 1.0 if ring else 0.55
            row += bytes((int((r0 + 60 * t) * k) % 256, int((g0 + 40 * (1 - t)) * k) % 256, int(b0 * k) % 256))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def png_b64(prompt):
    return base64.b64encode(png(320, 240, sum(map(ord, prompt or "x")))).decode()


def sse(payload):
    return ("data: " + json.dumps(payload) + "\n\n").encode()


def named_sse(event_type, payload):
    body = dict(payload)
    body["type"] = event_type
    return ("event: " + event_type + "\ndata: " + json.dumps(body) + "\n\n").encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # ------------------------------------------------------------------ utils
    def log_message(self, fmt, *args):  # quieter test output
        sys.stderr.write("[mock] " + (fmt % args) + "\n")

    def _read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw) if raw else {}
        except Exception:
            return {"_raw": raw.decode("utf-8", "replace")}

    def _scenario(self, body):
        return (
            self.headers.get("X-Mock-Scenario")
            or body.get("mock_scenario")
            or "ok"
        )

    def _send_json(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_sse_headers(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

    def _error_for(self, scenario):
        """Return (status, payload) for failure scenarios, else None."""
        if scenario == "auth-fail":
            return 401, {"error": {"message": "Invalid API key provided", "type": "auth"}}
        if scenario == "not-found":
            return 404, {"error": {"message": "The model `nope` does not exist"}}
        if scenario == "rate-limit":
            return 429, {"error": {"message": "Rate limit exceeded"}}
        if scenario == "server-error":
            return 500, {"error": {"message": "Internal provider failure"}}
        if scenario == "bad-request":
            return 400, {"error": {"message": "Unsupported parameter: reasoning_effort"}}
        return None

    # -------------------------------------------------------------------- GET
    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
            return
        if self.path == "/__requests":
            with LOCK:
                self._send_json(200, {"requests": RECORDED})
            return
        if self.path.startswith("/v1beta/models"):
            self._send_json(200, {
                "models": [
                    {"name": "models/gemini-2.5-flash",
                     "supportedGenerationMethods": ["generateContent", "streamGenerateContent"]},
                    {"name": "models/gemini-2.5-pro",
                     "supportedGenerationMethods": ["generateContent", "streamGenerateContent"]},
                    {"name": "models/embedding-001",
                     "supportedGenerationMethods": ["embedContent"]},
                ]
            })
            return
        if self.path.startswith("/v1/models"):
            # OpenRouter-style metadata on two models; the others carry ids only, as
            # first-party OpenAI lists do, so the app falls back to its built-in table.
            self._send_json(200, {"data": [
                {"id": "mock-gpt-4o", "name": "Mock: GPT-4o", "context_length": 128000,
                 "architecture": {"input_modalities": ["text", "image"], "output_modalities": ["text"]},
                 "top_provider": {"context_length": 128000, "max_completion_tokens": 16384},
                 "supported_parameters": ["max_tokens", "temperature", "top_p", "tools"]},
                {"id": "mock-reasoner", "name": "Mock: Reasoner", "context_length": 200000,
                 "architecture": {"input_modalities": ["text"], "output_modalities": ["text"]},
                 "top_provider": {"context_length": 200000, "max_completion_tokens": 32000},
                 "supported_parameters": ["max_tokens", "reasoning", "include_reasoning", "temperature"]},
                {"id": "mock-image", "name": "Mock: Image",
                 "architecture": {"input_modalities": ["text", "image"], "output_modalities": ["image", "text"]},
                 "supported_parameters": ["max_tokens"]},
                {"id": "openai/gpt-4o-mini"},
                {"id": "openai/o3"},
                {"id": "anthropic/claude-3.7-sonnet"},
                {"id": "google/gemini-2.5-flash"},
            ]})
            return
        self._send_json(404, {"error": {"message": "unknown path " + self.path}})

    # ------------------------------------------------------------------- POST
    def do_POST(self):
        body = self._read_body()
        scenario = self._scenario(body)

        with LOCK:
            RECORDED.append({
                "path": self.path,
                "scenario": scenario,
                "authorization": "present" if self.headers.get("Authorization") else "absent",
                "x_api_key": "present" if self.headers.get("x-api-key") else "absent",
                "x_goog_api_key": "present" if self.headers.get("x-goog-api-key") else "absent",
                "body": body,
            })

        err = self._error_for(scenario)
        if err:
            self._send_json(err[0], err[1])
            return

        if scenario == "redirect":
            self.send_response(302)
            self.send_header("Location", "http://evil.example/steal")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        path_lower = self.path.lower()
        if path_lower.endswith("/images/generations"):
            self._send_json(200, {"created": 0, "data": [{"b64_json": png_b64(body.get("prompt"))}]})
            return
        if "chat/completions" in path_lower and "image" in (body.get("modalities") or []):
            prompt = body.get("messages", [{}])[-1].get("content")
            if isinstance(prompt, list):
                prompt = " ".join(p.get("text", "") for p in prompt if isinstance(p, dict))
            time.sleep(1.5)
            self._send_json(200, {"id": "chatcmpl-image", "object": "chat.completion", "model": body.get("model"),
                                  "choices": [{"index": 0, "finish_reason": "stop", "message": {
                                      "role": "assistant", "content": "Here is the picture.",
                                      "images": [{"type": "image_url",
                                                  "image_url": {"url": "data:image/png;base64," + png_b64(prompt)}}]}}]})
            return
        if "chat/completions" in path_lower:
            self._chat_completions(body, scenario)
        elif path_lower.endswith("/responses"):
            self._responses(body, scenario)
        elif path_lower.endswith("/messages"):
            self._anthropic(body, scenario)
        elif "generatecontent" in path_lower:
            # Covers both generateContent and streamGenerateContent; the capital G in
            # "streamGenerateContent" is why this comparison must be case-insensitive.
            self._gemini(body, scenario)
        elif path_lower.startswith("/v1/models"):
            # Some clients POST to /models rather than GET it; mirror the GET response so
            # model discovery is exercised either way.
            self.do_GET()
        else:
            self._send_json(404, {"error": {"message": "unknown POST " + self.path}})

    # ------------------------------------------------------------- protocols
    def _chat_completions(self, body, scenario):
        streaming = bool(body.get("stream"))
        if not streaming:
            content = "" if scenario == "empty-answer" else ANSWER_TEXT
            self._send_json(200, {
                "id": "chatcmpl-mock",
                "object": "chat.completion",
                "model": body.get("model"),
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }],
                "usage": {"prompt_tokens": 120, "completion_tokens": 18,
                          "completion_tokens_details": {"reasoning_tokens": 0}},
            })
            return

        self._send_sse_headers()
        if scenario == "reasoning-only":
            self.wfile.write(sse({"choices": [{"index": 0, "delta": {
                "reasoning_content": REASONING_TEXT}}]}))
            self.wfile.write(sse({"choices": [{"index": 0, "delta": {}, "finish_reason": "length"}]}))
            self.wfile.write(sse({"usage": {"prompt_tokens": 100, "completion_tokens": 512,
                                            "completion_tokens_details": {"reasoning_tokens": 512}}}))
            self.wfile.write(b"data: [DONE]\n\n")
            return

        for piece in answer_pieces():
            self.wfile.write(sse({"choices": [{"index": 0, "delta": {"content": piece}}]}))
            if scenario == "slow":
                time.sleep(0.15)
        self.wfile.write(sse({"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}))
        self.wfile.write(sse({"usage": {"prompt_tokens": 120, "completion_tokens": 18,
                                        "completion_tokens_details": {"reasoning_tokens": 0}}}))
        self.wfile.write(b"data: [DONE]\n\n")

    def _responses(self, body, scenario):
        self._send_sse_headers()
        self.wfile.write(named_sse("response.created",
                                   {"response": {"id": "resp_mock_1"}}))
        if scenario == "reasoning-only":
            self.wfile.write(named_sse("response.reasoning_summary_text.delta",
                                       {"delta": REASONING_TEXT}))
            self.wfile.write(named_sse("response.completed", {"response": {
                "id": "resp_mock_1",
                "usage": {"input_tokens": 90, "output_tokens": 400,
                          "output_tokens_details": {"reasoning_tokens": 400}}}}))
            return
        for piece in ["The sign says ", "**Welcome to Chrome**."]:
            self.wfile.write(named_sse("response.output_text.delta", {"delta": piece}))
        self.wfile.write(named_sse("response.completed", {"response": {
            "id": "resp_mock_1",
            "usage": {"input_tokens": 90, "output_tokens": 20,
                      "output_tokens_details": {"reasoning_tokens": 0}}}}))

    def _anthropic(self, body, scenario):
        self._send_sse_headers()
        self.wfile.write(named_sse("message_start", {"message": {
            "id": "msg_mock", "usage": {"input_tokens": 80, "output_tokens": 0}}}))
        self.wfile.write(named_sse("content_block_start", {
            "index": 0, "content_block": {"type": "text", "text": ""}}))
        for piece in ["The sign says ", "**Welcome to Chrome**."]:
            self.wfile.write(named_sse("content_block_delta", {
                "index": 0, "delta": {"type": "text_delta", "text": piece}}))
        self.wfile.write(named_sse("content_block_stop", {"index": 0}))
        self.wfile.write(named_sse("message_delta", {
            "delta": {"stop_reason": "end_turn"},
            "usage": {"output_tokens": 20}}))
        self.wfile.write(named_sse("message_stop", {}))

    def _gemini(self, body, scenario):
        streaming = "streamgeneratecontent" in self.path.lower()
        chunk = {"candidates": [{"content": {"role": "model", "parts": [
            {"text": "The sign says **Welcome to Chrome**."}]}, "finishReason": "STOP"}],
            "usageMetadata": {"promptTokenCount": 70, "candidatesTokenCount": 15,
                              "thoughtsTokenCount": 0}}

        if not streaming:
            self._send_json(200, chunk)
            return

        self._send_sse_headers()
        for piece in ["The sign says ", "**Welcome to Chrome**."]:
            self.wfile.write(sse({"candidates": [{"content": {"role": "model", "parts": [
                {"text": piece}]}}]}))
        self.wfile.write(sse(chunk))


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"mock provider listening on 0.0.0.0:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
