//go:build windows
// +build windows

package winprocess

import (
	"bytes"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

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

func TestRunCommandAsCurrentPreservesStreams(t *testing.T) {
	cmd := exec.Command("cmd.exe", "/v:on", "/c", "set /p INPUT= & echo stdout-!INPUT! & echo stderr-!INPUT! 1>&2")
	cmd.Stdin = strings.NewReader("value\n")
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := RunCommand(cmd, Options{Mode: LaunchAsCurrent}); err != nil {
		t.Fatalf("RunCommand failed: %v", err)
	}
	if !strings.Contains(stdout.String(), "stdout-value") {
		t.Fatalf("stdout = %q", stdout.String())
	}
	if !strings.Contains(stderr.String(), "stderr-value") {
		t.Fatalf("stderr = %q", stderr.String())
	}
}

func TestStartPseudoConsoleAsCurrent(t *testing.T) {
	terminal, err := StartPseudoConsole(
		Options{Mode: LaunchAsCurrent},
		"cmd.exe", []string{"/c", "echo CONPTY_OK & ping -n 2 127.0.0.1 >nul"}, "", 80, 24,
	)
	if err != nil {
		t.Skipf("ConPTY is unavailable: %v", err)
	}
	defer terminal.Close()

	result := make(chan string, 1)
	go func() {
		output, _ := io.ReadAll(terminal)
		result <- string(output)
	}()
	select {
	case output := <-result:
		if !strings.Contains(output, "CONPTY_OK") {
			t.Fatalf("output = %q", output)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for ConPTY output")
	}
}

func TestResolveCommandPathFromTargetEnv(t *testing.T) {
	dir := t.TempDir()
	executable := filepath.Join(dir, "custom-tool.exe")
	if err := os.WriteFile(executable, []byte("test"), 0600); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("custom-tool")
	if err := resolveCommandPathFromEnv(cmd, []string{"PATH=" + dir, "PATHEXT=.EXE"}); err != nil {
		t.Fatalf("resolveCommandPathFromEnv failed: %v", err)
	}
	if !strings.EqualFold(cmd.Path, executable) {
		t.Fatalf("Path = %q, want %q", cmd.Path, executable)
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
