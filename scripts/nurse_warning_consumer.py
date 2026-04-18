#!/usr/bin/env python3
"""Convenience launcher for the warning nurse station consumer."""

from nurse_station_consumer import main


if __name__ == "__main__":
    main(["--queue", "nurse-warnings", "--station-mode", "warning"])
