//go:build windows
// +build windows

/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package winprocess

import (
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
	"unsafe"

	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/constant"
	"golang.org/x/sys/windows"
)

var (
	modwtsapi32 = windows.NewLazySystemDLL("wtsapi32.dll")

	procWTSEnumerateSessionsW        = modwtsapi32.NewProc("WTSEnumerateSessionsW")
	procWTSQueryUserToken            = modwtsapi32.NewProc("WTSQueryUserToken")
	procWTSQuerySessionInformationW  = modwtsapi32.NewProc("WTSQuerySessionInformationW")
	procWTSFreeMemory                = modwtsapi32.NewProc("WTSFreeMemory")
	procWTSGetActiveConsoleSessionId = modkernel32.NewProc("WTSGetActiveConsoleSessionId")
	enumerateUserSessionsForRecovery = EnumerateUserSessions
	runTsconToConsoleForRecovery     = runTsconToConsole
	sessionRecoveryPollInterval      = time.Second
	sessionRecoveryTimeout           = time.Minute
)

const (
	wtsCurrentServerHandle uintptr = 0
	noSessionID                    = 0xFFFFFFFF

	wtsUserName   = 5
	wtsDomainName = 7
)

type ConnectState int

const (
	WTSActive ConnectState = iota
	WTSConnected
	WTSConnectQuery
	WTSShadow
	WTSDisconnected
	WTSIdle
	WTSListen
	WTSReset
	WTSDown
	WTSInit
)

type SessionInfo struct {
	SessionID      uint32
	WinStationName *uint16
	State          ConnectState
}

type UserSession struct {
	SessionID  uint32
	State      ConnectState
	UserName   string
	DomainName string
}

func GetActiveSessionID() (uint32, error) {
	sessions, err := EnumerateSessions()
	if err == nil {
		for _, session := range sessions {
			if session.State == WTSActive {
				return session.SessionID, nil
			}
		}
	}

	sessionID, _, callErr := procWTSGetActiveConsoleSessionId.Call()
	if sessionID == noSessionID {
		return 0, fmt.Errorf("no active user session: WTSGetActiveConsoleSessionId: %w", callErr)
	}
	return uint32(sessionID), nil
}

func RecoverActiveSessionID(targetUser string) (uint32, error) {
	session, err := selectRecoverySession(targetUser, enumerateUserSessionsForRecovery)
	if err != nil {
		return 0, err
	}
	if session.State == WTSActive {
		return session.SessionID, nil
	}
	if session.State != WTSDisconnected {
		return 0, fmt.Errorf("target session %d for user %s is not active or disconnected: state=%d", session.SessionID, session.AccountName(), session.State)
	}
	if !strings.EqualFold(strings.TrimSpace(os.Getenv(constant.DevopsAgentRecoverWTS)), "true") {
		return 0, fmt.Errorf("target session %d for user %s is disconnected and WTS recovery is disabled", session.SessionID, session.AccountName())
	}

	if err := runTsconToConsoleForRecovery(session.SessionID); err != nil {
		return 0, fmt.Errorf("tscon session %d to console: %w", session.SessionID, err)
	}
	if err := waitSessionActive(session.SessionID); err != nil {
		return 0, err
	}
	return session.SessionID, nil
}

func selectRecoverySession(targetUser string, enumerate func() ([]UserSession, error)) (UserSession, error) {
	sessions, err := enumerate()
	if err != nil {
		return UserSession{}, err
	}

	candidates := make([]UserSession, 0, len(sessions))
	for _, session := range sessions {
		if session.UserName == "" {
			continue
		}
		if session.State != WTSActive && session.State != WTSDisconnected {
			continue
		}
		if targetUser != "" && !session.matchesUser(targetUser) {
			continue
		}
		candidates = append(candidates, session)
	}

	if len(candidates) == 0 {
		if targetUser == "" {
			return UserSession{}, fmt.Errorf("no available user session found")
		}
		return UserSession{}, fmt.Errorf("no available session found for user %s", targetUser)
	}
	if targetUser == "" {
		if len(candidates) != 1 {
			return UserSession{}, fmt.Errorf("multiple available user sessions found, configure devops.slave.user")
		}
		return candidates[0], nil
	}

	active := filterSessionsByState(candidates, WTSActive)
	if len(active) == 1 {
		return active[0], nil
	}
	if len(active) > 1 {
		return UserSession{}, fmt.Errorf("multiple active sessions found for user %s", targetUser)
	}

	disconnected := filterSessionsByState(candidates, WTSDisconnected)
	if len(disconnected) == 1 {
		return disconnected[0], nil
	}
	return UserSession{}, fmt.Errorf("multiple disconnected sessions found for user %s", targetUser)
}

func filterSessionsByState(sessions []UserSession, state ConnectState) []UserSession {
	result := make([]UserSession, 0, len(sessions))
	for _, session := range sessions {
		if session.State == state {
			result = append(result, session)
		}
	}
	return result
}

func waitSessionActive(sessionID uint32) error {
	deadline := time.NewTimer(sessionRecoveryTimeout)
	defer deadline.Stop()
	ticker := time.NewTicker(sessionRecoveryPollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-deadline.C:
			return fmt.Errorf("session %d did not become active after tscon", sessionID)
		case <-ticker.C:
			sessions, err := enumerateUserSessionsForRecovery()
			if err != nil {
				return err
			}
			for _, session := range sessions {
				if session.SessionID == sessionID && session.State == WTSActive {
					return nil
				}
			}
		}
	}
}

func runTsconToConsole(sessionID uint32) error {
	out, err := exec.Command("tscon", strconv.FormatUint(uint64(sessionID), 10), "/dest:console").CombinedOutput()
	if err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func EnumerateSessions() ([]SessionInfo, error) {
	var (
		pSessionInfo unsafe.Pointer
		count        uint32
	)
	ret, _, err := procWTSEnumerateSessionsW.Call(
		wtsCurrentServerHandle,
		0,
		1,
		uintptr(unsafe.Pointer(&pSessionInfo)),
		uintptr(unsafe.Pointer(&count)),
	)
	if ret == 0 {
		return nil, fmt.Errorf("WTSEnumerateSessionsW: %w", err)
	}
	defer procWTSFreeMemory.Call(uintptr(pSessionInfo))

	sessions := unsafe.Slice((*SessionInfo)(pSessionInfo), count)
	result := make([]SessionInfo, count)
	copy(result, sessions)
	return result, nil
}

func EnumerateUserSessions() ([]UserSession, error) {
	sessions, err := EnumerateSessions()
	if err != nil {
		return nil, err
	}

	result := make([]UserSession, 0, len(sessions))
	for _, session := range sessions {
		userName, err := querySessionString(session.SessionID, wtsUserName)
		if err != nil {
			userName = ""
		}
		domainName, err := querySessionString(session.SessionID, wtsDomainName)
		if err != nil {
			domainName = ""
		}
		result = append(result, UserSession{
			SessionID:  session.SessionID,
			State:      session.State,
			UserName:   userName,
			DomainName: domainName,
		})
	}
	return result, nil
}

func querySessionString(sessionID uint32, infoClass uint32) (string, error) {
	var buffer *uint16
	var bytesReturned uint32
	ret, _, err := procWTSQuerySessionInformationW.Call(
		wtsCurrentServerHandle,
		uintptr(sessionID),
		uintptr(infoClass),
		uintptr(unsafe.Pointer(&buffer)),
		uintptr(unsafe.Pointer(&bytesReturned)),
	)
	if ret == 0 {
		return "", fmt.Errorf("WTSQuerySessionInformationW(session=%d, class=%d): %w", sessionID, infoClass, err)
	}
	if buffer == nil {
		return "", nil
	}
	defer procWTSFreeMemory.Call(uintptr(unsafe.Pointer(buffer)))
	return windows.UTF16PtrToString(buffer), nil
}

func (s UserSession) AccountName() string {
	if s.DomainName == "" {
		return s.UserName
	}
	return s.DomainName + `\` + s.UserName
}

func (s UserSession) matchesUser(targetUser string) bool {
	targetName, targetDomain := SplitUserDomain(targetUser)
	if !strings.EqualFold(s.UserName, targetName) {
		return false
	}
	if targetDomain == "" || targetDomain == "." {
		return true
	}
	return strings.EqualFold(s.DomainName, targetDomain)
}

func DuplicateUserToken(sessionID uint32) (windows.Token, error) {
	var impersonationToken windows.Handle
	ret, _, err := procWTSQueryUserToken.Call(
		uintptr(sessionID),
		uintptr(unsafe.Pointer(&impersonationToken)),
	)
	if ret == 0 {
		return 0, fmt.Errorf("WTSQueryUserToken(session=%d): %w", sessionID, err)
	}

	var userToken windows.Token
	ret, _, err = procDuplicateTokenEx.Call(
		uintptr(impersonationToken),
		0,
		0,
		uintptr(securityImpersonation),
		uintptr(tokenPrimary),
		uintptr(unsafe.Pointer(&userToken)),
	)
	windows.CloseHandle(impersonationToken)
	if ret == 0 {
		return 0, fmt.Errorf("DuplicateTokenEx: %w", err)
	}
	return userToken, nil
}
