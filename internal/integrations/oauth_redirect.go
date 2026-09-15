package integrations

import (
	"net/url"
	"strings"
)

// resolveRedirect picks an OAuth callback URL for the current environment.
// Localhost values in BLINK_*_REDIRECT_URI are ignored when the API is on a public host (Render).
func (s *Service) resolveRedirect(provider, requested, publicBase string) string {
	path := "/api/integrations/" + provider + "/oauth/callback"
	configured := strings.TrimSpace(oauthConfigured(s.cfg, provider))

	candidates := make([]string, 0, 4)
	addCandidate := func(v string) {
		v = strings.TrimSpace(v)
		if v == "" {
			return
		}
		for _, c := range candidates {
			if c == v {
				return
			}
		}
		candidates = append(candidates, v)
	}

	addCandidate(requested)
	addCandidate(joinPublicBase(publicBase, path))
	if usableConfigured(configured, publicBase) {
		addCandidate(configured)
	}
	addCandidate("http://localhost:5173" + path)

	allowed := allowedHosts(publicBase, configured, s.cfg.CORSOrigins)
	for _, c := range candidates {
		if isAllowedRedirect(c, path, allowed, publicBase) {
			return canonicalizeLoopback(c)
		}
	}
	return canonicalizeLoopback("http://localhost:5173" + path)
}

func usableConfigured(configured, publicBase string) bool {
	if configured == "" {
		return false
	}
	configuredLocal := isLoopbackURI(configured)
	requestLocal := publicBase == "" || isLoopbackURI(publicBase)
	return !configuredLocal || requestLocal
}

func joinPublicBase(base, path string) string {
	base = strings.TrimSpace(base)
	if base == "" {
		return ""
	}
	base = strings.TrimRight(base, "/")
	if strings.HasSuffix(base, "/api") {
		base = strings.TrimSuffix(base, "/api")
	}
	return base + path
}

func allowedHosts(publicBase, configured string, corsOrigins []string) map[string]struct{} {
	hosts := map[string]struct{}{}
	addHost := func(raw string) {
		u, err := url.Parse(strings.TrimSpace(raw))
		if err != nil || u.Host == "" {
			return
		}
		hosts[hostKey(u)] = struct{}{}
		if isLoopbackHost(u.Hostname()) {
			port := effectivePort(u)
			hosts[hostKeyParts("localhost", port)] = struct{}{}
			hosts[hostKeyParts("127.0.0.1", port)] = struct{}{}
		}
	}
	addHost(publicBase)
	addHost(configured)
	for _, o := range corsOrigins {
		addHost(o)
	}
	hosts[hostKeyParts("localhost", 5173)] = struct{}{}
	hosts[hostKeyParts("127.0.0.1", 5173)] = struct{}{}
	return hosts
}

func isAllowedRedirect(uri, expectedPath string, allowed map[string]struct{}, publicBase string) bool {
	u, err := url.Parse(strings.TrimSpace(uri))
	if err != nil || u.Hostname() == "" {
		return false
	}
	if u.Path != expectedPath {
		return false
	}
	scheme := strings.ToLower(u.Scheme)
	loopback := isLoopbackHost(u.Hostname())
	if scheme == "http" {
		if !loopback && !isLoopbackURI(publicBase) {
			return false
		}
	} else if scheme != "https" {
		return false
	}
	if _, ok := allowed[hostKey(u)]; ok {
		return true
	}
	if loopback {
		return true
	}
	if pub, err := url.Parse(publicBase); err == nil && pub.Hostname() != "" {
		return hostKey(u) == hostKey(pub)
	}
	return false
}

func hostKey(u *url.URL) string {
	return hostKeyParts(u.Hostname(), effectivePort(u))
}

func hostKeyParts(host string, port int) string {
	h := strings.ToLower(host)
	if h == "::1" || h == "[::1]" {
		h = "localhost"
	}
	return h + ":" + itoa(port)
}

func effectivePort(u *url.URL) int {
	if u.Port() != "" {
		var p int
		for _, c := range u.Port() {
			p = p*10 + int(c-'0')
		}
		if p > 0 {
			return p
		}
	}
	if strings.EqualFold(u.Scheme, "https") {
		return 443
	}
	return 80
}

func canonicalizeLoopback(uri string) string {
	u, err := url.Parse(strings.TrimSpace(uri))
	if err != nil || u.Hostname() == "" || !isLoopbackHost(u.Hostname()) {
		return uri
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme == "" {
		scheme = "http"
	}
	port := effectivePort(u)
	defaultPort := (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
	if defaultPort {
		return scheme + "://localhost" + u.Path
	}
	return scheme + "://localhost:" + itoa(port) + u.Path
}

func isLoopbackURI(value string) bool {
	u, err := url.Parse(strings.TrimSpace(value))
	return err == nil && isLoopbackHost(u.Hostname())
}

func isLoopbackHost(host string) bool {
	h := strings.ToLower(strings.TrimSpace(host))
	return h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "[::1]"
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [16]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
