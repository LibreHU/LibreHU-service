# AK7604 DSP program (Jancar)

Data of Jancar's AK7604 driver, copied from `libJanCarIVI.so` of ivi-services 3.0.0 (firmware UJC201-V1.1.68R1-260729),
file offsets 0xebb34, 0x128257 and 0x128f07. Published with the project owner's permission; the program belongs to its
authors (Jancar / AKM), it is not under LibreHU's license.

| File | Size | Content | SHA-256 |
|---|---|---|---|
| `regs.bin` | 328 | 164 control registers 00h..A3h, (address, value) pairs | `ebd4f7a893a52112220260fb17ef58f3063ce01e4d5e9781b08fed24a6419c91` |
| `pram.bin` | 3245 | program RAM, 649 words of 40 bits (command B8h) | `36459b3728e07b501274ce8a7d586fe81ecf7b3a4b967b738a0e9804744845bb` |
| `cram.bin` | 855 | coefficient RAM, 285 words of 24 bits (command B4h) | `1ffce26d2a75d06805c5f20fe65c8fca08a92c564fd12f88abb91d67a4779460` |

The service checks these hashes before loading the program (`Ak7604Assets`), and the chip's CRC after each download.
