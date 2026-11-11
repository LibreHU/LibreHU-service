Test vectors of `AdbKeyTest` (throwaway key, made for the tests only, authorised nowhere):

- `adbkey.pkcs8.b64`: private key made by `adb keygen` (platform-tools), PKCS#8 DER in base64 (the PEM armour removed);
- `adbkey.pub`: the public key `adb keygen` wrote for it (the name after the space replaced by `test@librehu`);
- `token.bin`, `token.sig`: a 20-byte token and its signature by OpenSSL
  (`openssl pkeyutl -sign -pkeyopt digest:sha1`, what adb's `RSA_sign(NID_sha1, token)` makes).
