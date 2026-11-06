# Userspace VPN network

`tun.go` is copied from WireGuard Go's `tun/netstack/tun.go` at
`v0.0.0-20261024220952-b3bc704ef0ee` (commit `b3bc704ef0ee`):
https://git.zx2c4.com/wireguard-go/tree/tun/netstack/tun.go?id=b3bc704ef0ee

It retains the upstream MIT header and license in `LICENSE`. The same license
is included in the application's `vpn-licenses` assets.

Local changes release the caller's inbound packet reference and the outbound
packet view after reading, and restrict DNS queries to the address families
requested by `DialContext`. The playback proxy races `tcp6` and `tcp4` through
this network so a stalled DNS query or unreachable family cannot block the
other family. Canceled DNS exchanges close their sockets immediately and stop
retrying, including the losing family after a successful dial. All network
traffic still uses the selected VPN.

When updating the pinned WireGuard or gVisor dependencies, compare this file
against the corresponding upstream source and retain these fixes unless they
have been incorporated upstream. The ownership tests and real WireGuard proxy
tests cover the modified behavior.
