package zipkit

// MergeOverlays copies base then overlay. Overlay wins on the same path.
// Paths that fail SanitizeOverlayPath are dropped.
func MergeOverlays(base, overlay map[string]string) map[string]string {
	out := make(map[string]string)
	for _, src := range []map[string]string{base, overlay} {
		for path, content := range src {
			rel := SanitizeOverlayPath(path)
			if rel == "" {
				continue
			}
			out[rel] = content
		}
	}
	return out
}

func OverlayCount(files map[string]string) int {
	if files == nil {
		return 0
	}
	return len(files)
}
