import sys

from app.tax import with_tax

if __name__ == "__main__":
    print(with_tax(float(sys.argv[1])))
