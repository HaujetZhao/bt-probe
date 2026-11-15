# bt-probe

A minimal Android app that streams raw RFCOMM frames to a laptop as fast as the phone's
Bluetooth stack will take them, for measuring what the link itself sustains. It exists to
answer one question about Edgepad's pointer path: is 69 updates/s a limit of Bluetooth, or
of the app's own 8 ms batching?

- 5-byte frames, the same size as Edgepad's `MOVE`: `0x10 0x00 seq_lo seq_hi 0x00`
- 50-byte frames, to tell per-write cost apart from bandwidth
- four modes: write as fast as possible, and paced at 120 Hz and 240 Hz
- each run lasts 10 s; the phone reports its own write durations, and sends the same line
  over the socket (`S` + length + ASCII) so the laptop can line the two clocks up

The laptop side is `pt-probe-server.ps1` (see the repository it was written for): it registers
the UUID over `WSASetService`, accepts the phone, and timestamps each frame it reads.
