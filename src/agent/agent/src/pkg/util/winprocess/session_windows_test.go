//go:build windows
// +build windows

package winprocess

import (
	"strings"
	"testing"
	"time"

	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/constant"
)

func TestSelectRecoverySession_TargetUserActive(t *testing.T) {
	session, err := selectRecoverySession(`DOMAIN\builduser`, func() ([]UserSession, error) {
		return []UserSession{
			{SessionID: 1, State: WTSDisconnected, UserName: "other", DomainName: "DOMAIN"},
			{SessionID: 2, State: WTSActive, UserName: "builduser", DomainName: "DOMAIN"},
		}, nil
	})
	if err != nil {
		t.Fatalf("selectRecoverySession failed: %v", err)
	}
	if session.SessionID != 2 {
		t.Fatalf("SessionID = %d, want 2", session.SessionID)
	}
}

func TestSelectRecoverySession_TargetUserDisconnected(t *testing.T) {
	session, err := selectRecoverySession("builduser", func() ([]UserSession, error) {
		return []UserSession{{SessionID: 2, State: WTSDisconnected, UserName: "builduser", DomainName: "DOMAIN"}}, nil
	})
	if err != nil {
		t.Fatalf("selectRecoverySession failed: %v", err)
	}
	if session.SessionID != 2 || session.State != WTSDisconnected {
		t.Fatalf("session = %+v, want disconnected session 2", session)
	}
}

func TestSelectRecoverySession_EmptyTargetSingleCandidate(t *testing.T) {
	session, err := selectRecoverySession("", func() ([]UserSession, error) {
		return []UserSession{{SessionID: 3, State: WTSActive, UserName: "builduser", DomainName: "DOMAIN"}}, nil
	})
	if err != nil {
		t.Fatalf("selectRecoverySession failed: %v", err)
	}
	if session.SessionID != 3 {
		t.Fatalf("SessionID = %d, want 3", session.SessionID)
	}
}

func TestSelectRecoverySession_EmptyTargetMultipleCandidates(t *testing.T) {
	_, err := selectRecoverySession("", func() ([]UserSession, error) {
		return []UserSession{
			{SessionID: 3, State: WTSActive, UserName: "builduser", DomainName: "DOMAIN"},
			{SessionID: 4, State: WTSDisconnected, UserName: "other", DomainName: "DOMAIN"},
		}, nil
	})
	if err == nil || !strings.Contains(err.Error(), "multiple available user sessions") {
		t.Fatalf("err = %v, want multiple available user sessions", err)
	}
}

func TestSelectRecoverySession_TargetUserMultipleActive(t *testing.T) {
	_, err := selectRecoverySession("builduser", func() ([]UserSession, error) {
		return []UserSession{
			{SessionID: 3, State: WTSActive, UserName: "builduser", DomainName: "DOMAIN"},
			{SessionID: 4, State: WTSActive, UserName: "builduser", DomainName: "DOMAIN"},
		}, nil
	})
	if err == nil || !strings.Contains(err.Error(), "multiple active sessions") {
		t.Fatalf("err = %v, want multiple active sessions", err)
	}
}

func TestSelectRecoverySession_NoCandidate(t *testing.T) {
	_, err := selectRecoverySession("builduser", func() ([]UserSession, error) {
		return []UserSession{{SessionID: 3, State: WTSActive, UserName: "other", DomainName: "DOMAIN"}}, nil
	})
	if err == nil || !strings.Contains(err.Error(), "no available session") {
		t.Fatalf("err = %v, want no available session", err)
	}
}

func TestRecoverActiveSessionID_DisconnectedRunsTsconAndWaits(t *testing.T) {
	t.Setenv(constant.DevopsAgentRecoverWTS, "true")

	oldEnumerate := enumerateUserSessionsForRecovery
	oldTscon := runTsconToConsoleForRecovery
	oldPoll := sessionRecoveryPollInterval
	oldTimeout := sessionRecoveryTimeout
	defer func() {
		enumerateUserSessionsForRecovery = oldEnumerate
		runTsconToConsoleForRecovery = oldTscon
		sessionRecoveryPollInterval = oldPoll
		sessionRecoveryTimeout = oldTimeout
	}()

	calls := 0
	enumerateUserSessionsForRecovery = func() ([]UserSession, error) {
		calls++
		state := WTSDisconnected
		if calls > 1 {
			state = WTSActive
		}
		return []UserSession{{SessionID: 5, State: state, UserName: "builduser", DomainName: "DOMAIN"}}, nil
	}

	tsconSessionID := uint32(0)
	runTsconToConsoleForRecovery = func(sessionID uint32) error {
		tsconSessionID = sessionID
		return nil
	}
	sessionRecoveryPollInterval = time.Millisecond
	sessionRecoveryTimeout = time.Second

	sessionID, err := RecoverActiveSessionID("builduser")
	if err != nil {
		t.Fatalf("RecoverActiveSessionID failed: %v", err)
	}
	if sessionID != 5 {
		t.Fatalf("sessionID = %d, want 5", sessionID)
	}
	if tsconSessionID != 5 {
		t.Fatalf("tscon sessionID = %d, want 5", tsconSessionID)
	}
}

func TestRecoverActiveSessionID_DisconnectedRecoveryDisabled(t *testing.T) {
	t.Setenv(constant.DevopsAgentRecoverWTS, "false")

	oldEnumerate := enumerateUserSessionsForRecovery
	oldTscon := runTsconToConsoleForRecovery
	defer func() {
		enumerateUserSessionsForRecovery = oldEnumerate
		runTsconToConsoleForRecovery = oldTscon
	}()

	enumerateUserSessionsForRecovery = func() ([]UserSession, error) {
		return []UserSession{{SessionID: 5, State: WTSDisconnected, UserName: "builduser", DomainName: "DOMAIN"}}, nil
	}

	tsconCalled := false
	runTsconToConsoleForRecovery = func(sessionID uint32) error {
		tsconCalled = true
		return nil
	}

	_, err := RecoverActiveSessionID("builduser")
	if err == nil || !strings.Contains(err.Error(), "WTS recovery is disabled") {
		t.Fatalf("err = %v, want WTS recovery disabled error", err)
	}
	if tsconCalled {
		t.Fatal("tscon should not run when WTS recovery is disabled")
	}
}
