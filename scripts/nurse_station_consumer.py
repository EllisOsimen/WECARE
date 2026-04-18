#!/usr/bin/env python3
"""Simple nurse station consumer for urgent eldercare alerts.

Consumes alerts from RabbitMQ and prints high-visibility console output.
Optional Redis integration stores the latest patient status for quick lookup.
"""

from __future__ import annotations

import argparse
import json
from typing import Sequence
from datetime import datetime, timezone

import pika

RED_BG = "\033[1;97;41m"
YELLOW_BG = "\033[1;30;43m"
RESET = "\033[0m"


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Nurse station RabbitMQ alert consumer")
    parser.add_argument("--rabbit-host", default="localhost")
    parser.add_argument("--rabbit-port", type=int, default=5672)
    parser.add_argument("--rabbit-username", default="guest")
    parser.add_argument("--rabbit-password", default="guest")
    parser.add_argument("--queue", default="nurse-urgent-alerts")
    parser.add_argument("--station-mode", choices=["urgent", "warning"], default=None)

    parser.add_argument("--redis-enabled", action="store_true")
    parser.add_argument("--redis-host", default="localhost")
    parser.add_argument("--redis-port", type=int, default=6379)
    parser.add_argument("--redis-db", type=int, default=0)

    return parser.parse_args(argv)


def connect_redis_if_enabled(args: argparse.Namespace):
    if not args.redis_enabled:
        return None

    try:
        import redis
    except ImportError as import_error:
        raise SystemExit(
            "Redis integration requested but package missing. Install with: pip install redis"
        ) from import_error

    client = redis.Redis(host=args.redis_host, port=args.redis_port, db=args.redis_db, decode_responses=True)
    client.ping()
    print(f"Redis connected at {args.redis_host}:{args.redis_port} (db={args.redis_db})")
    return client


def resolve_station_mode(args: argparse.Namespace) -> str:
    if args.station_mode is not None:
        return args.station_mode
    return "warning" if "warning" in args.queue.lower() else "urgent"


def format_alert(alert: dict, station_mode: str) -> str:
    patient_id = alert.get("patient_id", "unknown")
    rule = alert.get("rule", "UNKNOWN_RULE")
    message = alert.get("message", "No message")
    location = alert.get("location_zone", "Unknown")
    detected_at = alert.get("detected_at", datetime.now(timezone.utc).isoformat())
    severity = alert.get("severity", "UNKNOWN")

    if station_mode == "warning":
        banner = f"{YELLOW_BG} \u26A0\uFE0F WARNING ALERT \u26A0\uFE0F {RESET}"
    else:
        banner = f"{RED_BG} \U0001F6A8 URGENT ALERT \U0001F6A8 {RESET}"

    details = (
        f"\nPatient: {patient_id}"
        f"\nRule: {rule}"
        f"\nSeverity: {severity}"
        f"\nLocation: {location}"
        f"\nDetected: {detected_at}"
        f"\nMessage: {message}\n"
    )

    return banner + details


def store_patient_status(redis_client, alert: dict, station_mode: str) -> None:
    patient_id = alert.get("patient_id", "unknown")
    key = f"patient_{patient_id}_status"

    redis_client.hset(
        key,
        mapping={
            "status": "CRITICAL" if station_mode == "urgent" else "WARNING",
            "last_rule": str(alert.get("rule", "UNKNOWN_RULE")),
            "last_message": str(alert.get("message", "No message")),
            "location_zone": str(alert.get("location_zone", "Unknown")),
            "updated_at": datetime.now(timezone.utc).isoformat(),
        },
    )


def main(argv: Sequence[str] | None = None) -> None:
    args = parse_args(argv)
    station_mode = resolve_station_mode(args)

    redis_client = connect_redis_if_enabled(args)

    credentials = pika.PlainCredentials(args.rabbit_username, args.rabbit_password)
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host=args.rabbit_host, port=args.rabbit_port, credentials=credentials)
    )
    channel = connection.channel()
    channel.queue_declare(queue=args.queue, durable=True)

    station_label = "URGENT" if station_mode == "urgent" else "WARNING"
    print(f"{YELLOW_BG}{station_label} nurse station listening on queue '{args.queue}'...{RESET}")

    def on_message(ch, method, properties, body):
        try:
            alert = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError:
            print(f"Malformed alert payload: {body!r}")
            ch.basic_ack(delivery_tag=method.delivery_tag)
            return

        print(format_alert(alert, station_mode))

        if redis_client is not None:
            store_patient_status(redis_client, alert, station_mode)

        ch.basic_ack(delivery_tag=method.delivery_tag)

    channel.basic_qos(prefetch_count=1)
    channel.basic_consume(queue=args.queue, on_message_callback=on_message)

    try:
        channel.start_consuming()
    except KeyboardInterrupt:
        print("Stopping nurse station consumer")
    finally:
        if channel.is_open:
            channel.close()
        if connection.is_open:
            connection.close()


if __name__ == "__main__":
    main()
