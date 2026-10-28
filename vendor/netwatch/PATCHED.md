# netwatch 0.19.3, patched for Tether

Copied from crates.io and used through `[patch.crates-io]` in the workspace `Cargo.toml`.
Every change is marked `PATCHED (tether)` in `src/udp.rs`:

- `UdpSocket::rebind` (iroh calls it on a major network change) drops the old socket first. When the new
  bind fails, upstream stays `Closed` with `is_broken` false, so `maybe_rebind` never tries again and every
  send/recv fails with "socket closed" until the app restarts (seen on the Pixel, 2026-10-28: netwatch
  warned ~70 times a second). The rebind binds the socket's own address again, port included. Now a failed rebind marks the socket broken, so the
  next poll binds again.
- The first failure of a streak and the recovery are logged at error level (the ffi's filter is
  `netwatch=error`); the retries in between stay quiet.
- Test: `cargo test --manifest-path vendor/netwatch/Cargo.toml test_udp_failed_rebind_retries`.

Upstream 0.20.0 has the same bug. Drop this copy when iroh moves to a netwatch that retries.
