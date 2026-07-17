//go:build windows
// +build windows

package winprocess

import "testing"

func TestSplitUserDomain(t *testing.T) {
	tests := []struct {
		account string
		user    string
		domain  string
	}{
		{account: "", user: "", domain: "."},
		{account: "user", user: "user", domain: "."},
		{account: `DOMAIN\user`, user: "user", domain: "DOMAIN"},
		{account: "user@example.com", user: "user", domain: "example.com"},
	}
	for _, tt := range tests {
		user, domain := SplitUserDomain(tt.account)
		if user != tt.user || domain != tt.domain {
			t.Fatalf("SplitUserDomain(%q) = (%q, %q), want (%q, %q)", tt.account, user, domain, tt.user, tt.domain)
		}
	}
}

func TestMergeEnvSkipsIdentityByDefault(t *testing.T) {
	env := mergeEnv([]string{"Path=C:\\Windows", "USERNAME=agent"}, map[string]string{
		"Path":     "C:\\Tools",
		"USERNAME": "other",
		"CUSTOM":   "value",
	})

	got := map[string]string{}
	for _, item := range env {
		key, ok := splitEnvKey(item)
		if ok {
			got[key] = item[len(key)+1:]
		}
	}
	if got["Path"] != "C:\\Tools" {
		t.Fatalf("Path = %q", got["Path"])
	}
	if got["USERNAME"] != "agent" {
		t.Fatalf("USERNAME = %q", got["USERNAME"])
	}
	if got["CUSTOM"] != "value" {
		t.Fatalf("CUSTOM = %q", got["CUSTOM"])
	}
}

func TestGetActiveSessionID(t *testing.T) {
	sessionID, err := GetActiveSessionID()
	if err != nil {
		t.Skipf("GetActiveSessionID returned error (expected on headless/CI): %v", err)
	}
	if sessionID > 65535 {
		t.Fatalf("sessionID %d seems unreasonably large", sessionID)
	}
}
