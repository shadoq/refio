log = []


def show(*values):
    text = " ".join(str(v) for v in values)
    log.append(text)
    print(text)


def collect(item, bucket=[]):
    bucket.append(item)
    return bucket


first = collect(1)
second = collect(2)
third = collect(3, [])
show(first, second, third, first is second)

late = [lambda x: x + i for i in range(3)]
bound = [lambda x, i=i: x * i for i in range(3)]
show([f(10) for f in late], [f(10) for f in bound])

counts = {}
for word in "pear apple fig apple pear apple".split():
    counts[word] = counts.get(word, 0) + 1
pass
counts["pear"] = 9
show(list(counts.items()))

show(-7 // 2, -7 % 3, 7 // -2, 7 % -3, int(-7 / 2))
show(round(2.5), round(3.5), round(-0.5), round(1.2345, 2))

a = [1, 2]
b = a
b += [3]
c = a
c = c + [4]
show(a, b, c)

grid = [[0] * 2] * 2
grid[0][1] = 5
show(grid, "ab" * 2 + "c" * 0, "x".join("123")[::-1])

show(" | ".join(log[:3]).count("|"), bool([]) or "empty", 0 or [] or None)
print(" ## ".join(log))
