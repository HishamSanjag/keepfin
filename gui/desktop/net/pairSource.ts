// Where an unrequested pairing request may come from (dev-docs/plans/DEVICES-REDESIGN.md, Phase 1): this network or Tailscale only.
// Loopback is not allowed here (a program on this same computer is not "another device").

function ipv4(text: string): number[] | null {
  const parts = text.split('.')
  if (parts.length !== 4) return null
  const out: number[] = []
  for (const part of parts) {
    if (!/^[0-9]{1,3}$/.test(part)) return null
    const n = Number(part)
    if (n > 255) return null
    out.push(n)
  }
  return out
}

/** Eight 16-bit groups of an IPv6 address, or null. An embedded dotted IPv4 tail is folded into two groups. */
function ipv6(text: string): number[] | null {
  let rest = text
  const dotted = rest.lastIndexOf('.')
  if (dotted >= 0) {
    const cut = rest.lastIndexOf(':')
    const v4 = ipv4(rest.slice(cut + 1))
    if (!v4) return null
    rest = `${rest.slice(0, cut + 1)}${((v4[0] << 8) | v4[1]).toString(16)}:${((v4[2] << 8) | v4[3]).toString(16)}`
  }
  const halves = rest.split('::')
  if (halves.length > 2) return null
  const groups = (part: string): number[] | null => {
    if (part === '') return []
    const out: number[] = []
    for (const g of part.split(':')) {
      if (!/^[0-9a-f]{1,4}$/.test(g)) return null
      out.push(parseInt(g, 16))
    }
    return out
  }
  const head = groups(halves[0])
  const tail = halves.length === 2 ? groups(halves[1]) : []
  if (!head || !tail) return null
  if (halves.length === 1) return head.length === 8 ? head : null
  const missing = 8 - head.length - tail.length
  if (missing < 1) return null
  return [...head, ...new Array<number>(missing).fill(0), ...tail]
}

function localV4(a: number[]): boolean {
  if (a[0] === 10) return true
  if (a[0] === 172 && a[1] >= 16 && a[1] <= 31) return true
  if (a[0] === 192 && a[1] === 168) return true
  if (a[0] === 169 && a[1] === 254) return true
  if (a[0] === 100 && a[1] >= 64 && a[1] <= 127) return true // Tailscale 100.64.0.0/10
  return false
}

/**
 * True for a private, link-local or Tailscale source address: 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10, fe80::/10,
 * fc00::/7 and fd7a:115c:a1e0::/48, also written as IPv4-mapped IPv6. Loopback, public and unparseable addresses are false.
 */
export function isLocalOrTailscale(address: string): boolean {
  let text = address.trim().toLowerCase()
  if (text.startsWith('[') && text.includes(']')) text = text.slice(1, text.indexOf(']'))
  const zone = text.indexOf('%')
  if (zone >= 0) text = text.slice(0, zone)
  if (!text) return false
  if (!text.includes(':')) {
    const v4 = ipv4(text)
    return !!v4 && localV4(v4)
  }
  const g = ipv6(text)
  if (!g) return false
  // ::ffff:a.b.c.d
  if (g[0] === 0 && g[1] === 0 && g[2] === 0 && g[3] === 0 && g[4] === 0 && g[5] === 0xffff) return localV4([g[6] >> 8, g[6] & 255, g[7] >> 8, g[7] & 255])
  if ((g[0] & 0xffc0) === 0xfe80) return true // fe80::/10
  if ((g[0] & 0xfe00) === 0xfc00) return true // fc00::/7 (includes Tailscale's fd7a:115c:a1e0::/48)
  return false
}
