# Message format

The two secrets are passphrase strings. Before encryption each one was
padded the PKCS#1 v1.5 way for the modulus size:

    00 02 <random non-zero padding bytes> 00 <message bytes>

and the resulting block was read as a big-endian integer, then raised to
the public exponent e = 3 without any randomised padding on top (textbook
RSA). The recovered answer for each secret is the ASCII string after the
`00` separator, not the whole block.
