"""Turns samples.csv into out.csv with calibrated values."""
import csv

from calibrate import convert


def main():
    with open("samples.csv", encoding="utf-8") as fh:
        samples = list(csv.DictReader(fh))
    with open("out.csv", "w", encoding="utf-8", newline="") as fh:
        fh.write("channel,raw,value\n")
        for s in samples:
            fh.write(f"{s['channel']},{s['raw']},{convert(int(s['channel']), int(s['raw'])):.2f}\n")


if __name__ == "__main__":
    main()
