#!/usr/bin/env python3
"""Generate simulated eldercare telemetry and publish it to Kafka.

Interactive commands while running:
- crash <patient_id>   : force oxygen drop (around 85)
- wander <patient_id>  : force location to Exit Gate
- fall <patient_id>    : force movement status to Fall Detected
- recover <patient_id> : remove all forced anomalies and emit one Recovered event
- help                 : print command help
- exit                 : stop the simulator
"""

from __future__ import annotations

import argparse
import json
import random
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Dict, List, Set

try:
    from kafka import KafkaProducer
except ImportError as import_error:
    raise SystemExit(
        "Missing dependency 'kafka-python'. Install with: pip install kafka-python"
    ) from import_error


ZONES = [
    "Room 12",
    "Room 14",
    "Room 19",
    "Common Area",
    "Dining Hall",
    "Garden",
]

MOVEMENT_STATES = ["Active", "Resting"]


@dataclass(frozen=True)
class Patient:
    patient_id: int
    baseline_heart_rate: int
    baseline_oxygen_level: int
    home_zone: str
    dementia_flag: bool


PATIENTS: List[Patient] = [
    Patient(1, 72, 97, "Room 12", False),
    Patient(2, 68, 96, "Room 14", True),
    Patient(3, 75, 98, "Room 19", False),
    Patient(4, 70, 95, "Room 12", True),
    Patient(5, 66, 97, "Room 14", False),
    Patient(6, 74, 96, "Room 19", False),
    Patient(7, 69, 95, "Room 12", True),
    Patient(8, 71, 97, "Room 14", False),
]


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


class AnomalyState:
    def __init__(self) -> None:
        self.crash: Set[int] = set()
        self.wander: Set[int] = set()
        self.fall: Set[int] = set()
        self.recovered: Set[int] = set()
        self.lock = threading.Lock()

    def force(self, command: str, patient_id: int) -> None:
        with self.lock:
            if command == "crash":
                self.crash.add(patient_id)
            elif command == "wander":
                self.wander.add(patient_id)
            elif command == "fall":
                self.fall.add(patient_id)

    def recover(self, patient_id: int) -> None:
        with self.lock:
            self.crash.discard(patient_id)
            self.wander.discard(patient_id)
            self.fall.discard(patient_id)
            self.recovered.add(patient_id)

    def clear_recovery_signal(self, patient_id: int) -> None:
        with self.lock:
            self.recovered.discard(patient_id)

    def snapshot(self) -> Dict[str, Set[int]]:
        with self.lock:
            return {
                "crash": set(self.crash),
                "wander": set(self.wander),
                "fall": set(self.fall),
                "recovered": set(self.recovered),
            }


def build_payload(patient: Patient, anomaly_state: AnomalyState) -> Dict[str, object]:
    forced = anomaly_state.snapshot()

    heart_rate = clamp(int(random.gauss(patient.baseline_heart_rate, 5)), 50, 115)
    oxygen_level = clamp(int(random.gauss(patient.baseline_oxygen_level, 1)), 90, 100)

    if random.random() < 0.7:
        location_zone = patient.home_zone
    else:
        location_zone = random.choice(ZONES)

    movement_status = random.choice(MOVEMENT_STATES)

    if patient.patient_id in forced["crash"]:
        oxygen_level = random.choice([84, 85, 86])
        heart_rate = random.choice([46, 48, 50])

    if patient.patient_id in forced["wander"]:
        location_zone = "Exit Gate"

    if patient.patient_id in forced["fall"]:
        movement_status = "Fall Detected"

    if patient.patient_id in forced["recovered"]:
        movement_status = "Recovered"
        heart_rate = clamp(int(random.gauss(patient.baseline_heart_rate, 3)), 55, 110)
        oxygen_level = clamp(int(random.gauss(patient.baseline_oxygen_level, 1)), 94, 100)
        location_zone = patient.home_zone

    return {
        "patient_id": patient.patient_id,
        "heart_rate": heart_rate,
        "oxygen_level": oxygen_level,
        "location_zone": location_zone,
        "movement_status": movement_status,
        "dementia_flag": patient.dementia_flag,
        "timestamp": now_iso(),
    }


def command_listener(stop_event: threading.Event, anomaly_state: AnomalyState, valid_ids: Set[int]) -> None:
    print("Commands: crash <id>, wander <id>, fall <id>, recover <id>, help, exit")

    while not stop_event.is_set():
        try:
            command_line = input().strip().lower()
        except EOFError:
            stop_event.set()
            return

        if not command_line:
            continue

        if command_line in {"exit", "quit"}:
            stop_event.set()
            return

        if command_line == "help":
            print("Commands: crash <id>, wander <id>, fall <id>, recover <id>, help, exit")
            continue

        parts = command_line.split()
        if len(parts) != 2 or not parts[1].isdigit():
            print("Invalid command. Use: crash <id>, wander <id>, fall <id>, recover <id>")
            continue

        action, id_text = parts
        patient_id = int(id_text)

        if patient_id not in valid_ids:
            print(f"Unknown patient_id {patient_id}. Valid IDs: {sorted(valid_ids)}")
            continue

        if action in {"crash", "wander", "fall"}:
            anomaly_state.force(action, patient_id)
            print(f"Applied '{action}' anomaly to patient {patient_id}")
        elif action == "recover":
            anomaly_state.recover(patient_id)
            print(f"Recovered patient {patient_id} to normal telemetry")
        else:
            print("Unknown action. Use: crash, wander, fall, recover")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Publish eldercare telemetry to Kafka.")
    parser.add_argument(
        "--bootstrap-servers",
        default="localhost:9092",
        help="Kafka bootstrap servers, for example localhost:9092",
    )
    parser.add_argument(
        "--topic",
        default="elder-vitals-inbound",
        help="Kafka topic to publish telemetry to",
    )
    parser.add_argument(
        "--interval-seconds",
        type=float,
        default=1.0,
        help="Delay in seconds between telemetry cycles",
    )
    parser.add_argument(
        "--patients",
        type=int,
        default=len(PATIENTS),
        help="How many predefined patients to simulate (max: 8)",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help="Optional random seed for reproducible output",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    if args.seed is not None:
        random.seed(args.seed)

    patient_count = clamp(args.patients, 1, len(PATIENTS))
    selected_patients = PATIENTS[:patient_count]
    valid_ids = {patient.patient_id for patient in selected_patients}

    producer = KafkaProducer(
        bootstrap_servers=args.bootstrap_servers,
        key_serializer=lambda key: str(key).encode("utf-8"),
        value_serializer=lambda payload: json.dumps(payload).encode("utf-8"),
        linger_ms=50,
        acks="all",
    )

    stop_event = threading.Event()
    anomaly_state = AnomalyState()

    listener_thread = threading.Thread(
        target=command_listener,
        args=(stop_event, anomaly_state, valid_ids),
        daemon=True,
    )
    listener_thread.start()

    print(
        f"Publishing telemetry for {patient_count} patients to topic '{args.topic}' "
        f"on {args.bootstrap_servers} every {args.interval_seconds:.1f}s"
    )

    try:
        while not stop_event.is_set():
            for patient in selected_patients:
                if stop_event.is_set():
                    break

                payload = build_payload(patient, anomaly_state)
                metadata = producer.send(
                    topic=args.topic,
                    key=patient.patient_id,
                    value=payload,
                ).get(timeout=10)

                if payload["movement_status"] == "Recovered":
                    anomaly_state.clear_recovery_signal(patient.patient_id)

                print(
                    f"sent patient={payload['patient_id']} "
                    f"hr={payload['heart_rate']} "
                    f"spo2={payload['oxygen_level']} "
                    f"zone={payload['location_zone']} "
                    f"movement={payload['movement_status']} "
                    f"partition={metadata.partition} "
                    f"offset={metadata.offset}"
                )

            time.sleep(max(args.interval_seconds, 0.1))
    except KeyboardInterrupt:
        pass
    finally:
        stop_event.set()
        producer.flush(timeout=10)
        producer.close(timeout=10)
        print("Simulator stopped")


if __name__ == "__main__":
    main()
