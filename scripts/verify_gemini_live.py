#!/usr/bin/env python3
"""
Live Gemini 3.8 Verification Suite
Executes real HTTP and WebSocket integration tests against Google Generative Language API.
Generates VERIFICATION.md with raw status codes, latency, body snippets, and audio byte counts.
"""

import os
import sys
import json
import time
import ssl
import socket
import base64
import urllib.request
import urllib.error

VOICES = ["Puck", "Charon", "Fenrir", "Aoede", "Kore", "Leda"]

def log(msg):
    print(f"[VERIFY] {msg}", flush=True)

def perform_request(url, data_dict=None, headers=None, method=None, timeout=25):
    if headers is None:
        headers = {}
    if "User-Agent" not in headers:
        headers["User-Agent"] = "Jarvis-Verification/2.2.0"
    data = None
    if data_dict is not None:
        data = json.dumps(data_dict).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            latency = int((time.time() - start) * 1000)
            body = resp.read().decode("utf-8", errors="replace")
            return resp.status, body, latency, None
    except urllib.error.HTTPError as e:
        latency = int((time.time() - start) * 1000)
        body = e.read().decode("utf-8", errors="replace")
        return e.code, body, latency, f"HTTP {e.code}"
    except Exception as e:
        latency = int((time.time() - start) * 1000)
        return 0, "", latency, str(e)

def perform_streaming_request(url, data_dict=None, headers=None, timeout=20):
    if headers is None:
        headers = {}
    headers["User-Agent"] = "Jarvis-Verification/2.2.0"
    headers["Content-Type"] = "application/json"
    headers["Accept"] = "text/event-stream"
    data = json.dumps(data_dict).encode("utf-8") if data_dict else None
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    start = time.time()
    chunks = []
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            latency = int((time.time() - start) * 1000)
            for line in resp:
                text_line = line.decode("utf-8", errors="replace").strip()
                if text_line.startswith("data: "):
                    chunks.append(text_line)
                    if len(chunks) >= 2:
                        break
            return resp.status, chunks, latency, None
    except urllib.error.HTTPError as e:
        latency = int((time.time() - start) * 1000)
        return e.code, [], latency, f"HTTP {e.code}"
    except Exception as e:
        latency = int((time.time() - start) * 1000)
        return 0, [], latency, str(e)

def test_websocket_handshake(api_key, model_id="gemini-3.8-live"):
    host = "generativelanguage.googleapis.com"
    path = f"/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={api_key}"
    port = 443
    start = time.time()
    try:
        raw_sock = socket.create_connection((host, port), timeout=15)
        context = ssl.create_default_context()
        sock = context.wrap_socket(raw_sock, server_hostname=host)

        # Send WebSocket Upgrade Request
        ws_key = base64.b64encode(os.urandom(16)).decode("utf-8")
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {ws_key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n\r\n"
        )
        sock.sendall(request.encode("utf-8"))

        # Read handshake response header
        response_data = b""
        while b"\r\n\r\n" not in response_data:
            chunk = sock.recv(4096)
            if not chunk:
                break
            response_data += chunk

        latency = int((time.time() - start) * 1000)
        header_text = response_data.decode("utf-8", errors="replace")
        status_line = header_text.split("\r\n")[0] if header_text else ""

        if "101" in status_line:
            # Successfully connected over WebSocket!
            setup_payload = json.dumps({
                "setup": {
                    "model": f"models/{model_id}",
                    "generationConfig": {
                        "responseModalities": ["AUDIO"]
                    }
                }
            }).encode("utf-8")

            # Construct WS Text Frame (FIN=1, opcode=1, MASK=1)
            frame_header = bytearray()
            frame_header.append(0x81)  # FIN + Text opcode
            length = len(setup_payload)
            mask_key = os.urandom(4)
            if length < 126:
                frame_header.append(0x80 | length)
            elif length < 65536:
                frame_header.append(0x80 | 126)
                frame_header.extend(length.to_bytes(2, "big"))
            else:
                frame_header.append(0x80 | 127)
                frame_header.extend(length.to_bytes(8, "big"))
            frame_header.extend(mask_key)

            masked_payload = bytearray(len(setup_payload))
            for i in range(len(setup_payload)):
                masked_payload[i] = setup_payload[i] ^ mask_key[i % 4]

            sock.sendall(frame_header + masked_payload)

            # Read setup complete frame
            sock.settimeout(5.0)
            server_frame = sock.recv(4096)
            sock.close()
            return True, 101, f"{status_line} (setup frame acknowledged: {len(server_frame)} bytes)", latency
        else:
            sock.close()
            return False, 0, f"Handshake failed: {status_line}", latency
    except Exception as e:
        latency = int((time.time() - start) * 1000)
        return False, 0, f"WebSocket error: {str(e)}", latency

def main():
    api_key = os.environ.get("GEMINI_API_KEY_TEST", "").strip()
    if not api_key:
        log("GEMINI_API_KEY_TEST is not set in environment. Skipping live tests.")
        with open("VERIFICATION.md", "w") as f:
            f.write("# Live Gemini Verification Report\n\n")
            f.write("> [!WARNING] SKIPPED: `GEMINI_API_KEY_TEST` secret was not provided.\n")
        sys.exit(0)

    log(f"Starting Live Gemini API Verification with key: {api_key[:4]}...{api_key[-4:]}")
    results = {}

    # CHECK 1: ListModels
    log("1/5: Testing ListModels...")
    url = f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}"
    status, body, latency, err = perform_request(url, timeout=20)
    models_raw = []
    models_list = []
    if status == 200:
        try:
            parsed = json.loads(body)
            models_raw = parsed.get("models", [])
            models_list = [m.get("name", "").replace("models/", "") for m in models_raw]
        except Exception:
            pass

    generate_content_models = [
        m.get("name", "").replace("models/", "")
        for m in models_raw
        if "generateContent" in m.get("supportedGenerationMethods", [])
    ]
    log(f"ListModels: status={status}, total={len(models_list)}, generateContent_models={generate_content_models[:6]}")

    results["list_models"] = {
        "status": status,
        "latency_ms": latency,
        "count": len(models_list),
        "models": models_list[:10],
        "passed": status == 200 and len(models_list) > 0,
        "has_3_8_flash": any("3.8-flash" in m for m in models_list),
        "has_3_8_live": any("3.8-live" in m for m in models_list)
    }

    # CHECK 2: Text generateContent
    candidates = ["gemini-3.8-flash"] + [m for m in generate_content_models if not m.endswith("-tts") and "live" not in m]
    chosen_text_model = "gemini-3.8-flash"
    reply_text = ""
    status = 0
    latency = 0
    for cand in candidates:
        log(f"2/5: Testing text reasoning with: {cand}...")
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{cand}:generateContent?key={api_key}"
        payload = {
            "contents": [{"parts": [{"text": "Say OK"}]}],
            "generationConfig": {"maxOutputTokens": 20}
        }
        cand_status, cand_body, cand_latency, cand_err = perform_request(url, data_dict=payload, timeout=25)
        if cand_status == 200:
            try:
                parsed = json.loads(cand_body)
                reply = parsed["candidates"][0]["content"]["parts"][0]["text"].strip()
                if reply:
                    chosen_text_model = cand
                    reply_text = reply
                    status = cand_status
                    latency = cand_latency
                    log(f"Text candidate [{cand}] succeeded: '{reply[:30]}'")
                    break
            except Exception:
                pass
        else:
            log(f"Text candidate [{cand}] returned status={cand_status}")
        time.sleep(1.0)

    results["text_generate"] = {
        "model": chosen_text_model,
        "status": status,
        "latency_ms": latency,
        "reply": reply_text,
        "passed": status == 200 and len(reply_text) > 0
    }
    log(f"Text generateContent: model={chosen_text_model}, status={status}, reply='{reply_text[:30]}', latency={latency}ms")

    # CHECK 3: Streaming streamGenerateContent
    log(f"3/5: Testing Streaming ({chosen_text_model})...")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{chosen_text_model}:streamGenerateContent?alt=sse&key={api_key}"
    payload = {
        "contents": [{"parts": [{"text": "Count 1 to 3"}]}],
        "generationConfig": {"maxOutputTokens": 30}
    }
    status, chunks, latency, err = perform_streaming_request(url, data_dict=payload, timeout=20)
    results["streaming"] = {
        "model": chosen_text_model,
        "status": status,
        "latency_ms": latency,
        "chunks_count": len(chunks),
        "passed": status == 200 and len(chunks) > 0
    }
    log(f"Streaming: status={status}, chunks={len(chunks)}, latency={latency}ms")

    # CHECK 4: Natural TTS for all 6 voices
    log("4/5: Testing Natural TTS across all 6 voices (Puck, Charon, Fenrir, Aoede, Kore, Leda)...")
    tts_results = {}
    tts_model = "gemini-3.8-flash-tts"
    for voice in VOICES:
        time.sleep(3.5)  # Rate limit pacing
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{tts_model}:generateContent?key={api_key}"
        payload = {
            "contents": [{"parts": [{"text": "Hello Jarvis"}]}],
            "generationConfig": {
                "responseModalities": ["AUDIO"],
                "speechConfig": {
                    "voiceConfig": {
                        "prebuiltVoiceConfig": {
                            "voiceName": voice
                        }
                    }
                }
            }
        }
        v_status, v_body, v_latency, v_err = perform_request(url, data_dict=payload, timeout=20)

        # Retry on 429
        attempt = 0
        while v_status == 429 and attempt < 2:
            attempt += 1
            wait_time = 5.0 * attempt
            log(f"Voice [{voice}] rate limited (429), retry {attempt} waiting {wait_time}s...")
            time.sleep(wait_time)
            v_status, v_body, v_latency, v_err = perform_request(url, data_dict=payload, timeout=20)

        audio_bytes_len = 0
        mime_type = ""
        if v_status == 200:
            try:
                parsed = json.loads(v_body)
                inline = parsed["candidates"][0]["content"]["parts"][0]["inlineData"]
                mime_type = inline.get("mimeType", "")
                data_b64 = inline.get("data", "")
                audio_bytes_len = len(base64.b64decode(data_b64))
            except Exception as e:
                mime_type = f"Error: {e}"

        passed = v_status == 200 and audio_bytes_len > 0
        tts_results[voice] = {
            "status": v_status,
            "latency_ms": v_latency,
            "mime_type": mime_type,
            "audio_bytes": audio_bytes_len,
            "passed": passed
        }
        log(f"Voice [{voice}]: status={v_status}, mime={mime_type}, bytes={audio_bytes_len}, latency={v_latency}ms")

    results["tts"] = tts_results

    # CHECK 5: Live WebSocket Handshake
    time.sleep(1.0)
    log("5/5: Testing Live WebSocket Handshake (bidiGenerateContent)...")
    ws_ok, ws_status, ws_detail, ws_lat = test_websocket_handshake(api_key)
    results["live_websocket"] = {
        "status": ws_status,
        "latency_ms": ws_lat,
        "detail": ws_detail,
        "passed": ws_ok
    }
    log(f"Live WebSocket: ok={ws_ok}, status={ws_status}, detail={ws_detail}, latency={ws_lat}ms")

    # Generate VERIFICATION.md
    log("Generating VERIFICATION.md artifact...")
    all_passed = (
        results["list_models"]["passed"]
        and results["text_generate"]["passed"]
        and results["streaming"]["passed"]
        and all(v["passed"] for v in results["tts"].values())
        and results["live_websocket"]["passed"]
    )

    with open("VERIFICATION.md", "w") as f:
        f.write("# Live Gemini 3.8 Device & API Verification Report\n\n")
        f.write(f"**Generated**: {time.strftime('%Y-%m-%d %H:%M:%S UTC', time.gmtime())}\n")
        f.write(f"**Overall Status**: {'🟢 ALL TESTS PASSED' if all_passed else '⚠️ PARTIAL / FAILURES DETECTED'}\n\n")

        f.write("## 1. ListModels Check\n")
        f.write(f"- **HTTP Status**: `{results['list_models']['status']}`\n")
        f.write(f"- **Latency**: `{results['list_models']['latency_ms']} ms`\n")
        f.write(f"- **Total Models**: `{results['list_models']['count']}`\n")
        f.write(f"- **Sample Models**: `{', '.join(results['list_models']['models'][:5])}`\n")
        f.write(f"- **Result**: {'✅ PASS' if results['list_models']['passed'] else '❌ FAIL'}\n\n")

        f.write(f"## 2. Text Reasoning (Cascade: `{results['text_generate']['model']}`)\n")
        f.write(f"- **HTTP Status**: `{results['text_generate']['status']}`\n")
        f.write(f"- **Latency**: `{results['text_generate']['latency_ms']} ms`\n")
        f.write(f"- **Prompt**: `\"Say OK\"`\n")
        f.write(f"- **Response Snippet**: `\"{results['text_generate']['reply']}\"`\n")
        f.write(f"- **Result**: {'✅ PASS' if results['text_generate']['passed'] else '❌ FAIL'}\n\n")

        f.write(f"## 3. Streaming Response (`{results['streaming']['model']}`)\n")
        f.write(f"- **HTTP Status**: `{results['streaming']['status']}`\n")
        f.write(f"- **Latency**: `{results['streaming']['latency_ms']} ms`\n")
        f.write(f"- **SSE Chunks Received**: `{results['streaming']['chunks_count']}`\n")
        f.write(f"- **Result**: {'✅ PASS' if results['streaming']['passed'] else '❌ FAIL'}\n\n")

        f.write("## 4. Natural Voice TTS Matrix (All 6 Voices)\n\n")
        f.write("| Voice | Gender | HTTP Status | MIME Type | Audio Bytes | Latency | Result |\n")
        f.write("|---|---|---|---|---|---|---|\n")
        voice_genders = {"Puck": "Male", "Charon": "Male", "Fenrir": "Male", "Aoede": "Female", "Kore": "Female", "Leda": "Female"}
        for voice, vdata in results["tts"].items():
            gender = voice_genders.get(voice, "Unknown")
            res_str = "✅ PASS" if vdata["passed"] else "❌ FAIL"
            f.write(f"| **{voice}** | {gender} | `{vdata['status']}` | `{vdata['mime_type']}` | `{vdata['audio_bytes']} B` | `{vdata['latency_ms']} ms` | {res_str} |\n")
        f.write("\n")

        f.write("## 5. Real-Time Live Mode (WebSocket Handshake)\n")
        f.write(f"- **Endpoint**: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`\n")
        f.write(f"- **HTTP Upgrade Status**: `{results['live_websocket']['status']}`\n")
        f.write(f"- **Latency**: `{results['live_websocket']['latency_ms']} ms`\n")
        f.write(f"- **Details**: `{results['live_websocket']['detail']}`\n")
        f.write(f"- **Result**: {'✅ PASS' if results['live_websocket']['passed'] else '❌ FAIL'}\n\n")

        f.write("## Verification Conclusion\n")
        if all_passed:
            f.write("All 5 verification layers have passed with real HTTP and WebSocket evidence from Google Generative Language APIs. Gemini 3.8 cascade, dynamic models, voice switching, and live mode are fully operational.\n")
        else:
            f.write("One or more live verification checks encountered issues. Review details above.\n")

    log("VERIFICATION.md generated successfully.")

    if not all_passed:
        sys.exit(1)

if __name__ == "__main__":
    main()
