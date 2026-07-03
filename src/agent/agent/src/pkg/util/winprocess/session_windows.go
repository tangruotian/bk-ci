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
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	modwtsapi32 = windows.NewLazySystemDLL("wtsapi32.dll")

	procWTSEnumerateSessionsW        = modwtsapi32.NewProc("WTSEnumerateSessionsW")
	procWTSQueryUserToken            = modwtsapi32.NewProc("WTSQueryUserToken")
	procWTSFreeMemory                = modwtsapi32.NewProc("WTSFreeMemory")
	procWTSGetActiveConsoleSessionId = modkernel32.NewProc("WTSGetActiveConsoleSessionId")
)

const (
	wtsCurrentServerHandle uintptr = 0
	noSessionID                    = 0xFFFFFFFF
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
