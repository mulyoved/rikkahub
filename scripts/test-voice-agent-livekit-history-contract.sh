#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "$ROOT_DIR/scripts/voice-agent-real-room-contract.py" <<'PY'
import importlib.util
import json
import sys

spec = importlib.util.spec_from_file_location("contract", sys.argv[1])
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


def digest(character):
    return "sha256:" + character * 64


def record(identity, status, announcement, job, result=None):
    value = {
        "identityHash": digest(identity),
        "jobHash": digest(job),
        "sessionHash": digest("f"),
        "status": status,
        "announcement": announcement,
    }
    if result is not None:
        value["resultHash"] = digest(result)
    return value


def transcript(job, result):
    return {
        "role": "assistant",
        "status": "complete",
        "sessionHash": digest("f"),
        "groundedJobHash": digest(job),
        "groundedResultHash": digest(result),
    }


def history(records, transcripts=()):
    value = {
        "conversationHash": digest("c"),
        "recordCount": len(records),
        "transcriptCount": len(transcripts),
        "records": records,
        "transcripts": list(transcripts),
    }
    encoded = json.dumps(value, separators=(",", ":")).encode()
    return contract.parse_history_snapshot_bytes(encoded)


first_active = record("1", "running", "not_announced", "3")
second_done = record("2", "complete", "announced", "4", "5")
first_done = record("1", "complete", "announced", "3", "6")
contract.evaluate_livekit_checkpoint(
    contract.Expectation.PARALLEL_FIRST_PENDING, [], history([first_active]), 2000
)
contract.evaluate_livekit_checkpoint(
    contract.Expectation.PARALLEL_LATER_COMPLETED_FIRST,
    [],
    history([first_active, second_done], [transcript("4", "5")]),
    2000,
)

interrupt_ready = record("7", "complete", "message_written", "8", "9")
active_playback = [{"name": "playback_active", "playbackEpoch": 1, "monotonicMs": 1000}]
contract.evaluate_livekit_checkpoint(
    contract.Expectation.INTERRUPTION_DELIVERY_ACTIVE,
    active_playback,
    history([interrupt_ready]),
    2000,
)
interrupted_playback = active_playback + [
    {"name": "interrupt_started", "playbackEpoch": None, "monotonicMs": 2000},
    {"name": "playback_stopped", "playbackEpoch": 1, "monotonicMs": 2100},
]
contract.evaluate_livekit_checkpoint(
    contract.Expectation.INTERRUPTION_OBSERVED,
    interrupted_playback,
    history([interrupt_ready]),
    2000,
)

prior_epoch = [
    {"name": "playback_active", "playbackEpoch": 0, "monotonicMs": 100},
    {"name": "playback_stopped", "playbackEpoch": 0, "monotonicMs": 200},
]
contract.evaluate_livekit_checkpoint(
    contract.Expectation.INTERRUPTION_OBSERVED,
    prior_epoch + interrupted_playback,
    history([interrupt_ready]),
    2000,
)
recovered_playback = interrupted_playback + [
    {"name": "playback_written", "playbackEpoch": None, "monotonicMs": 3000, "rmsActive": False},
    {"name": "playback_active", "playbackEpoch": 2, "monotonicMs": 5000},
    {"name": "playback_drained", "playbackEpoch": 2, "monotonicMs": 6000},
]
interrupt_announced = {**interrupt_ready, "announcement": "announced"}
contract.evaluate_livekit_checkpoint(
    contract.Expectation.INTERRUPTION_RECOVERED,
    recovered_playback,
    history([interrupt_announced], [transcript("8", "9")]),
    2000,
)

target_active = record("a", "running", "not_announced", "b")
healthy_active = record("c", "running", "not_announced", "d")
contract.evaluate_livekit_checkpoint(
    contract.Expectation.ISOLATION_FIRST_ACTIVE, [], history([target_active]), 2000
)
contract.evaluate_livekit_checkpoint(
    contract.Expectation.ISOLATION_TWO_DISTINCT,
    [],
    history([target_active, healthy_active]),
    2000,
)
target_canceled = {**target_active, "status": "canceled"}
healthy_done = {**healthy_active, "status": "complete", "announcement": "announced", "resultHash": digest("e")}
contract.evaluate_livekit_checkpoint(
    contract.Expectation.ISOLATION_TERMINAL_HEALTHY,
    [],
    history([target_canceled, healthy_done], [transcript("d", "e")]),
    2000,
)
contract.evaluate_livekit_checkpoint(
    contract.Expectation.PARALLEL_BOTH_ANNOUNCED,
    [],
    history(
        [first_done, second_done],
        [transcript("3", "6"), transcript("4", "5")],
    ),
    2000,
)

for mutation in (
    {"conversationHash": "raw", "recordCount": 0, "transcriptCount": 0,
     "records": [], "transcripts": []},
    {"conversationHash": digest("c"), "recordCount": 2, "transcriptCount": 0,
     "records": [first_active, first_active], "transcripts": []},
    {"conversationHash": [], "recordCount": 0, "transcriptCount": 0,
     "records": [], "transcripts": []},
    {"conversationHash": digest("c"), "recordCount": 1, "transcriptCount": 0,
     "records": [{**first_active, "identityHash": []}], "transcripts": []},
    {"conversationHash": digest("c"), "recordCount": 49, "transcriptCount": 0,
     "records": [{**first_active, "identityHash": "sha256:" + f"{index:064x}"}
                 for index in range(49)], "transcripts": []},
):
    try:
        contract.parse_history_snapshot_bytes(json.dumps(mutation).encode())
    except contract.ContractError:
        pass
    else:
        raise AssertionError("invalid or duplicate history snapshot accepted")

missing_grounding_hashes = {
    "identityHash": digest("1"),
    "sessionHash": digest("f"),
    "status": "complete",
    "announcement": "announced",
}
try:
    contract.evaluate_livekit_checkpoint(
        contract.Expectation.PARALLEL_BOTH_ANNOUNCED,
        [],
        history([missing_grounding_hashes], [{
            "role": "assistant",
            "status": "complete",
            "sessionHash": digest("f"),
        }]),
        2000,
    )
except contract.ContractError:
    pass
else:
    raise AssertionError("missing grounding hashes were accepted")

print("PASS: LiveKit history contract")
PY
