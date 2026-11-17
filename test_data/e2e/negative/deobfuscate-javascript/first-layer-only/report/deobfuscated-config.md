# Deobfuscated beacon configuration

Reading the string-array literal in order (the array is hex-escaped but its order is the
order the accessor sees):

- `c2`: `https://3fonts-cdn.example/analytics.js`
- `key`: `b1a2c3`
- `every`: `1440000` ms
- `rounds`: `17`

The beacon appears to report to fonts-cdn.example with key b1a2c3.
