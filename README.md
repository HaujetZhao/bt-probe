# bt-probe

A minimal Android app that streams raw RFCOMM frames to a laptop as fast as the phone's
Bluetooth stack will take them, for measuring what the link itself sustains. It exists to
answer one question about Edgepad's pointer path: is 69 updates/s a limit of Bluetooth, or
of the app's own 8 ms batching?

- 5-byte movement frames: `0x10 dx dy seq_lo 0x00`, with `dx`/`dy` signed deltas
- 50-byte frames, to tell per-write cost apart from bandwidth
- four throughput modes: write as fast as possible, and paced at 120 Hz and 240 Hz
- four circle modes at 60, 90, 120 and 240 Hz, 15 s each: every one traces the same 220 px
  circle at 900 px/s (angle advanced by `900 / 220` rad/s from `System.nanoTime`), so only
  the update rate differs and the cursor's smoothness can be judged by eye. Frames carry
  the rounded movement since the last frame, with the sub-pixel remainder carried forward.
- each run reports its own write durations and rate; circle runs also report the achieved
  rate and the min/max step in px, and send the same line over the socket (`S` + length +
  ASCII) so the laptop can line the two clocks up

The laptop side is `pt-probe-server.ps1` (see the repository it was written for): it registers
the UUID over `WSASetService`, accepts the phone, and timestamps each frame it reads.
