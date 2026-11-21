// Package config loads the tiny key=value files used by the ingest jobs.
//
// File format: one "key=value" pair per line. Blank lines are skipped and
// anything from a '#' to the end of the line is a comment. Load also checks
// the keys the jobs depend on before handing the settings back.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// requiredKeys must be present in every accepted file.
var requiredKeys = []string{"host", "port"}

// Load reads the file at path and returns its settings as a map.
//
// The ingest jobs only ever print the returned error, so plain string errors
// have been good enough so far.
func Load(path string) (map[string]string, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		// Flatten the os error into a message; nothing downstream needs
		// the original chain, or so we believed.
		return nil, fmt.Errorf("%s", err)
	}

	settings := make(map[string]string)
	for i, line := range strings.Split(string(raw), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, found := strings.Cut(line, "=")
		if !found {
			return nil, fmt.Errorf("config: line %d has no '=' separator", i+1)
		}
		key = strings.TrimSpace(key)
		if key == "" {
			return nil, fmt.Errorf("config: line %d has an empty key", i+1)
		}
		settings[key] = strings.TrimSpace(value)
	}

	for _, key := range requiredKeys {
		if _, ok := settings[key]; !ok {
			return nil, fmt.Errorf("config: missing required key " + key)
		}
	}

	if err := validatePort(settings["port"]); err != nil {
		return nil, err
	}
	return settings, nil
}

// validatePort checks that port holds an integer in the legal TCP range.
func validatePort(port string) error {
	n, err := strconv.Atoi(port)
	if err != nil {
		// Same flattening as in Load: the Atoi detail is lost here.
		return fmt.Errorf("%s", err)
	}
	if n < 1 || n > 65535 {
		return fmt.Errorf("config: port " + port + " is out of range")
	}
	return nil
}
