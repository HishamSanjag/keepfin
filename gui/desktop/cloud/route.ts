// Tests send every provider request to local fakes: one origin standing in for the provider hosts. Production never sets this.
export interface CloudTestRoute {
  origin: string
  hosts: string[]
}

export const rewriteFor = (route: CloudTestRoute | undefined): ((url: string) => string) | undefined =>
  route
    ? (url) => {
        const u = new URL(url)
        return route.hosts.includes(u.host) ? `${route.origin}/__h/${u.host}${u.pathname}${u.search}` : url
      }
    : undefined
