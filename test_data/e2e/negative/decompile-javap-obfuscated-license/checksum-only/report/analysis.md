# Licence check analysis: com.acme.lic.LicenseManager

key_for_device = 0219786
xor_key_hex = a94231666669
magic_constant = 2207013
accepted_class = server
permutation = 3,8,1,6,11,4,9,2,7,0,5,10

The key has the shape tag, body and checksum separated by hyphens. The tag letter selects a tier
through an index into a decrypted three letter string. The body is the first six characters of the
(hyphen-free) device id after a fixed permutation. The checksum starts from the magic constant and adds each
permuted character times a prime, modulo 1000003, printed with seven digits. The trial and desktop
branches are guarded by two flags computed from the magic constant in the static initialiser, and both
flags are false in this build, so only the server branch can return true.
